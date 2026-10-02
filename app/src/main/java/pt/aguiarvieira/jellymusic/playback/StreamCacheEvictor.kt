package pt.aguiarvieira.jellymusic.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.io.File
import java.util.TreeSet

/** Free space always left on the device, whatever the configured cache size. */
private const val MIN_FREE_BYTES = 1L shl 30 // 1 GiB

/**
 * Least-recently-used eviction for the streaming cache, like media3's LeastRecentlyUsedCacheEvictor
 * but with two differences that matter for a multi-gigabyte cache:
 *  - the size limit is live ([maxBytes] follows the Settings choice) instead of fixed at construction;
 *  - it never lets the cache eat the last [MIN_FREE_BYTES] of the device, so a 50 GB setting on a
 *    nearly full phone shrinks itself instead of filling the disk.
 *
 * Spans of [protectedKeys] (the tracks playing and about to play) are skipped while evicting.
 */
@OptIn(UnstableApi::class)
internal class StreamCacheEvictor(
    private val cacheDir: File,
    private val protectedKeys: () -> Set<String>,
) : CacheEvictor {

    @Volatile
    var maxBytes: Long = 0L

    private val spans = TreeSet<CacheSpan> { a, b ->
        val byTime = a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
        if (byTime != 0) byTime else a.compareTo(b)
    }
    private var currentSize = 0L

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != androidx.media3.common.C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        synchronized(this) {
            spans.add(span)
            currentSize += span.length
        }
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        synchronized(this) {
            if (spans.remove(span)) currentSize -= span.length
        }
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /** Evicts until [required] more bytes fit under the limit. Also called when the limit shrinks. */
    fun evict(cache: Cache, required: Long) {
        val victims = mutableListOf<CacheSpan>()
        synchronized(this) {
            val limit = effectiveLimit()
            var size = currentSize
            if (size + required <= limit) return
            val keep = protectedKeys()
            for (span in spans) {
                if (size + required <= limit) break
                if (span.key in keep) continue
                victims += span
                size -= span.length
            }
        }
        // removeSpan calls back into onSpanRemoved, so do it outside our lock.
        victims.forEach { runCatching { cache.removeSpan(it) } }
    }

    private fun effectiveLimit(): Long {
        val roomOnDisk = currentSize + cacheDir.usableSpace - MIN_FREE_BYTES
        return minOf(maxBytes, roomOnDisk).coerceAtLeast(0L)
    }
}
