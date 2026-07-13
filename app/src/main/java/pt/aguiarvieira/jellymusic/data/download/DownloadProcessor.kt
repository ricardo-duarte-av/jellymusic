package pt.aguiarvieira.jellymusic.data.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import pt.aguiarvieira.jellymusic.core.util.Logx
import pt.aguiarvieira.jellymusic.data.db.DownloadDao
import pt.aguiarvieira.jellymusic.data.db.TrackDownloadEntity
import pt.aguiarvieira.jellymusic.data.jellyfin.StreamUrlBuilder
import pt.aguiarvieira.jellymusic.domain.model.AudioCodec
import pt.aguiarvieira.jellymusic.domain.model.DownloadState
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val CONNECT_TIMEOUT_MS = 30_000
private const val BUFFER_SIZE = 64 * 1024
private const val PROGRESS_EMIT_INTERVAL_MS = 300L

/**
 * Downloads pending tracks to portable files, updating their Room state. Runs inside the
 * [DownloadWorker] so downloads continue in the background and survive process death.
 */
@Singleton
class DownloadProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val urlBuilder: StreamUrlBuilder,
) {
    private val downloadsDir: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    /** Drains the pending queue one track at a time. [onTrack] fires before each (for the notification). */
    suspend fun processAll(onTrack: suspend (TrackDownloadEntity) -> Unit) {
        while (true) {
            val next = dao.nextPending() ?: break
            onTrack(next)
            runCatching { process(next) }
                .onFailure { e ->
                    // Stop draining if the worker was cancelled; only log genuine per-track failures.
                    if (e is CancellationException) throw e
                    Logx.w("Downloads", "Download failed for ${next.trackId}", e)
                }
        }
    }

    private suspend fun process(entity: TrackDownloadEntity) {
        val now = System.currentTimeMillis()
        val settings = if (entity.transcoded) {
            StreamSettings(
                transcode = true,
                codec = entity.codec?.let { runCatching { AudioCodec.valueOf(it) }.getOrNull() } ?: AudioCodec.OPUS,
                maxBitrateKbps = entity.bitrateKbps ?: 320,
            )
        } else {
            StreamSettings(transcode = false)
        }
        val url = urlBuilder.audioStreamUrl(entity.trackId, settings)
        if (url == null) {
            dao.updateProgress(entity.trackId, DownloadState.FAILED.name, 0, 0, null, now)
            return
        }

        val ext = if (entity.transcoded) settings.codec.container else "audio"
        val partFile = File(downloadsDir, "${entity.trackId}.part")
        val outFile = File(downloadsDir, "${entity.trackId}.$ext")

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = CONNECT_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            val total = connection.contentLengthLong.coerceAtLeast(0L)
            dao.updateProgress(entity.trackId, DownloadState.DOWNLOADING.name, 0, total, null, now)

            connection.inputStream.use { input ->
                partFile.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = 0L
                    var lastEmit = System.currentTimeMillis()
                    while (true) {
                        // Bail out if the user removed this download mid-flight.
                        if (dao.getTrack(entity.trackId) == null) {
                            partFile.delete()
                            return
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastEmit >= PROGRESS_EMIT_INTERVAL_MS) {
                            dao.updateProgress(entity.trackId, DownloadState.DOWNLOADING.name, written, total, null, nowMs)
                            lastEmit = nowMs
                        }
                    }
                    output.flush()
                    if (outFile.exists()) outFile.delete()
                    partFile.renameTo(outFile)
                    dao.updateProgress(
                        entity.trackId,
                        DownloadState.COMPLETED.name,
                        written,
                        if (total > 0) total else written,
                        outFile.absolutePath,
                        System.currentTimeMillis(),
                    )
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            partFile.delete()
            // Persist the outcome under NonCancellable since a stopped worker cancels the coroutine
            // mid-download: re-queue that track so a later run resumes it, but fail on real errors.
            withContext(NonCancellable) {
                val state = if (t is CancellationException) DownloadState.QUEUED else DownloadState.FAILED
                dao.updateProgress(entity.trackId, state.name, 0, 0, null, System.currentTimeMillis())
            }
            throw t
        } finally {
            connection.disconnect()
        }
    }
}
