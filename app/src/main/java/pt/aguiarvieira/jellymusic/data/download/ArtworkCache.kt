package pt.aguiarvieira.jellymusic.data.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists album artwork to disk so downloaded content shows covers offline. Keyed by item id
 * (usually the album id, shared across its tracks), stored under filesDir/artwork/<id>.img.
 */
@Singleton
class ArtworkCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File
        get() = File(context.filesDir, "artwork").apply { mkdirs() }

    private fun fileFor(itemId: String) = File(dir, "$itemId.img")

    /**
     * Downloads [remoteUrl] to disk keyed by [itemId], once. Returns the absolute local path (or the
     * existing one), or null if there's nothing to cache / the fetch failed.
     */
    suspend fun cache(itemId: String, remoteUrl: String?): String? = withContext(Dispatchers.IO) {
        if (remoteUrl.isNullOrBlank()) return@withContext null
        val out = fileFor(itemId)
        // A non-empty file is a real cover; a zero-length one is the debris of an interrupted write
        // and must not be treated as a hit, or the item would show a broken cover forever (this file
        // path is baked into artworkPath in Room and rendered as file://…, so nothing else would ever
        // re-fetch it). Drop it and download again.
        if (out.exists()) {
            if (out.length() > 0) return@withContext out.absolutePath
            out.delete()
        }
        val tmp = File(dir, "$itemId.tmp")
        runCatching {
            val connection = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
            }
            try {
                connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (out.exists()) out.delete()
                // Report success only when the cover is in place under its final name — returning a
                // path for a rename that didn't happen is what poisons the cache in the first place.
                check(tmp.renameTo(out)) { "Could not move artwork for $itemId into place" }
            } finally {
                connection.disconnect()
            }
            out.absolutePath
        }.also {
            // Nothing reads the scratch file after this point; leaving it behind on failure just
            // accumulates orphan .tmp files in filesDir/artwork.
            tmp.delete()
        }.getOrNull()
    }

    fun delete(itemId: String?) {
        if (!itemId.isNullOrBlank()) fileFor(itemId).delete()
    }
}
