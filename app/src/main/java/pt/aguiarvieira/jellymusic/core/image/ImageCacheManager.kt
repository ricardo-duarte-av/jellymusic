package pt.aguiarvieira.jellymusic.core.image

import android.content.Context
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evicts artwork from Coil's caches. Used to recover from a poisoned cache entry — a truncated or
 * otherwise corrupt image that Coil persisted, which then fails to decode on every subsequent load
 * (the item shows the placeholder icon until the cache is cleared).
 *
 * [evict] targets the specific URLs of one item (album/artist/playlist) on pull-to-refresh; the
 * caller pairs it with a fresh cache-bust URL so the on-screen images actually re-request (Coil's
 * memory cache is keyed by URL, and a failed load latches until the URL changes). [clearAll] is the
 * nuclear option behind Settings → Clear image cache.
 */
@Singleton
class ImageCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val imageLoader get() = SingletonImageLoader.get(context)

    /** Evicts [urls] from Coil's memory + disk caches so the next load re-fetches from the server. */
    suspend fun evict(urls: Collection<String>) {
        val targets = urls.filter { it.isNotBlank() }.toSet()
        if (targets.isEmpty()) return
        val loader = imageLoader
        loader.memoryCache?.let { mem ->
            // Memory keys carry size/param extras (the 200dp hero and the 48dp rows share one base
            // URL), so match on the base key and drop every sized variant.
            mem.keys.filter { it.key in targets }.forEach { mem.remove(it) }
        }
        withContext(Dispatchers.IO) {
            loader.diskCache?.let { disk -> targets.forEach { disk.remove(it) } }
        }
    }

    /** Clears the entire image cache (memory + disk). */
    suspend fun clearAll() {
        val loader = imageLoader
        loader.memoryCache?.clear()
        withContext(Dispatchers.IO) { loader.diskCache?.clear() }
    }
}
