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
        if (out.exists()) return@withContext out.absolutePath
        runCatching {
            val connection = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
            }
            try {
                val tmp = File(dir, "$itemId.tmp")
                connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (out.exists()) out.delete()
                tmp.renameTo(out)
            } finally {
                connection.disconnect()
            }
            out.absolutePath
        }.getOrNull()
    }

    fun delete(itemId: String?) {
        if (!itemId.isNullOrBlank()) fileFor(itemId).delete()
    }
}
