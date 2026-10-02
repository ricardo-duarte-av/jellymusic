package pt.aguiarvieira.jellymusic.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/** How often a read waiting on the download checks for new data. */
private const val POLL_MS = 50L

/** A read gives up (and the player retries) when the download makes no progress for this long. */
private const val STALL_TIMEOUT_MS = 30_000L

/**
 * How long opening a transcode waits for the whole file before playing what has arrived so far.
 * Playing a partial transcode works, but it can't be seeked (its length is unknown).
 */
private const val TRANSCODE_WAIT_MS = 10_000L

/**
 * Plays a [StreamCache] track (a `jellymusic-stream://` URI) from the cache, reading each fragment
 * as the download commits it. It never touches the network itself: if a byte isn't there yet, it
 * waits for [StreamCache]'s download, asking for the track to be fetched first. A download that
 * fails or stalls surfaces as an [IOException], which the player retries like any load error.
 */
@OptIn(UnstableApi::class)
class StreamCacheDataSource(private val streamCache: StreamCache) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var trackId: String? = null
    private var key: String? = null
    private var openedAtMs = 0L
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    private var file: RandomAccessFile? = null
    private var fileSpan: CacheSpan? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val id = StreamCache.trackIdOf(dataSpec.uri) ?: throw IOException("No track id in ${dataSpec.uri}")
        transferInitializing(dataSpec)
        openedAtMs = System.currentTimeMillis()
        val quality = streamCache.pinFor(id)
        val key = StreamCache.keyFor(id, quality)
        trackId = id
        this.key = key
        streamCache.readerOpened(id)
        streamCache.demand(id, retry = true)

        if (quality.transcode) {
            waitUntil(TRANSCODE_WAIT_MS) { streamCache.isComplete(key) }
        } else {
            // An original's length is stored as soon as its download connects.
            waitUntil(STALL_TIMEOUT_MS, failOnTimeout = true) { contentLength(key) != C.LENGTH_UNSET.toLong() }
        }

        val total = contentLength(key)
        position = dataSpec.position
        if (total != C.LENGTH_UNSET.toLong() && position > total) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total != C.LENGTH_UNSET.toLong() -> total - position
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val key = checkNotNull(key)
        var lastProgressAt = System.currentTimeMillis()
        var lastCached = -1L
        while (true) {
            val span = spanAt(key, position)
            if (span != null) {
                val want = minOf(
                    length.toLong(),
                    span.position + span.length - position,
                    if (bytesRemaining == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else bytesRemaining,
                ).toInt()
                val raf = fileFor(span)
                raf.seek(position - span.position)
                val read = raf.read(buffer, offset, want)
                if (read <= 0) throw IOException("Cache file ended early: ${span.file}")
                position += read
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
                bytesTransferred(read)
                return read
            }

            val total = contentLength(key)
            if (total != C.LENGTH_UNSET.toLong() && position >= total) return C.RESULT_END_OF_INPUT
            if (streamCache.failedSince(key, openedAtMs)) throw IOException("Download of $key failed")

            // Not downloaded this far yet: wait for the download, failing if it stops moving.
            val cached = streamCache.cache().getCachedBytes(key, 0, C.LENGTH_UNSET.toLong())
            val now = System.currentTimeMillis()
            if (cached != lastCached) {
                lastCached = cached
                lastProgressAt = now
            } else if (now - lastProgressAt > STALL_TIMEOUT_MS) {
                throw IOException("Download of $key stalled at $cached bytes")
            }
            // Nothing arriving: make sure this track is next in line (it may have been preempted).
            if (now - lastProgressAt > 2_000) streamCache.demand(checkNotNull(trackId))
            sleep()
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closeFile()
        trackId?.let { streamCache.readerClosed(it) }
        trackId = null
        key = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /** The cached span holding byte [pos] of [key], or null when that byte isn't downloaded yet. */
    private fun spanAt(key: String, pos: Long): CacheSpan? {
        fileSpan?.let { if (pos >= it.position && pos < it.position + it.length) return it }
        val cache = streamCache.cache()
        val span = cache.getCachedSpans(key).lastOrNull { it.position <= pos } ?: return null
        if (pos >= span.position + span.length) return null
        // Mark it used for the least-recently-used eviction. That returns either this span (cached),
        // or a hole we've just locked and must release at once so the download isn't blocked.
        runCatching {
            cache.startReadWriteNonBlocking(key, pos, span.length)?.let { if (!it.isCached) cache.releaseHoleSpan(it) }
        }
        return span
    }

    private fun fileFor(span: CacheSpan): RandomAccessFile {
        if (fileSpan === span) file?.let { return it }
        closeFile()
        val f = checkNotNull(span.file) { "Cached span without a file" }
        return RandomAccessFile(f, "r").also {
            file = it
            fileSpan = span
        }
    }

    private fun closeFile() {
        runCatching { file?.close() }
        file = null
        fileSpan = null
    }

    private fun contentLength(key: String): Long =
        ContentMetadata.getContentLength(streamCache.cache().getContentMetadata(key))

    /** Polls [condition] for up to [timeoutMs]; a download failure throws straight away. */
    private fun waitUntil(timeoutMs: Long, failOnTimeout: Boolean = false, condition: () -> Boolean) {
        val key = checkNotNull(key)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (streamCache.failedSince(key, openedAtMs)) throw IOException("Download of $key failed")
            if (System.currentTimeMillis() > deadline) {
                if (failOnTimeout) throw IOException("Timed out waiting for $key")
                return
            }
            sleep()
        }
    }

    private fun sleep() {
        try {
            Thread.sleep(POLL_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException()
        }
    }

    /**
     * The player's data sources: `jellymusic-stream://` tracks come from the cache, anything else
     * (downloaded files, artwork) from [fallback].
     */
    class Factory(
        private val streamCache: StreamCache,
        private val fallback: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            RoutingDataSource(StreamCacheDataSource(streamCache), fallback.createDataSource())
    }

    private class RoutingDataSource(
        private val stream: DataSource,
        private val other: DataSource,
    ) : DataSource {
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            stream.addTransferListener(transferListener)
            other.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val target = if (StreamCache.isStreamUri(dataSpec.uri)) stream else other
            active = target
            return target.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(active).read(buffer, offset, length)

        override fun getUri(): Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                active?.close()
            } finally {
                active = null
            }
        }
    }
}
