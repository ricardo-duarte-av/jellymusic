package pt.aguiarvieira.jellymusic.data.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
 * Which pending queue a worker run drains. MANUAL tracks download on any connection; FAVORITE tracks
 * are auto-downloaded favourites and (via their worker's constraints) only on Wi-Fi by default.
 */
enum class DownloadScope { MANUAL, FAVORITE }

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
    suspend fun processAll(scope: DownloadScope, onTrack: suspend (TrackDownloadEntity) -> Unit) {
        while (true) {
            val next = when (scope) {
                DownloadScope.MANUAL -> dao.nextPending()
                DownloadScope.FAVORITE -> dao.nextPendingFavorite()
            } ?: break
            onTrack(next)
            runCatching { process(next) }
                .onFailure { e ->
                    // Stop draining if the worker was cancelled; only log genuine per-track failures.
                    if (e is CancellationException) throw e
                    Logx.w("Downloads", "Download failed for ${next.trackId}", e)
                }
        }
    }

    /**
     * Downloads one track. Every step here blocks — the HTTP read, the file write, the Room writes —
     * and [DownloadWorker] runs `doWork` on `Dispatchers.Default` (CoroutineWorker's default), which
     * is the CPU-bound pool shared with the rest of the app. Confine the whole thing to IO so a
     * download can't occupy a Default worker thread for the length of a file.
     */
    private suspend fun process(entity: TrackDownloadEntity): Unit = withContext(Dispatchers.IO) {
        val settings = settingsFor(entity)
        val url = urlBuilder.audioStreamUrl(entity.trackId, settings)
        if (url == null) {
            dao.updateProgress(entity.trackId, DownloadState.FAILED.name, 0, 0, null, System.currentTimeMillis())
            return@withContext
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
            dao.updateProgress(
                entity.trackId, DownloadState.DOWNLOADING.name, 0, total, null, System.currentTimeMillis(),
            )

            val written = connection.inputStream.use { input ->
                partFile.outputStream().use { output -> copyTracking(entity, input, output, total) }
            }
            if (written == null) {
                // Removed by the user mid-flight; its row is already gone, so just drop the scratch file.
                partFile.delete()
                return@withContext
            }
            publish(entity, partFile, outFile, written, total)
        } catch (c: CancellationException) {
            // A stopped worker (execution-time limit / lost constraints) cancels the coroutine
            // mid-download; re-queue the track so a later run resumes it. Persist under NonCancellable
            // since the coroutine is already cancelled.
            partFile.delete()
            withContext(NonCancellable) {
                dao.updateProgress(entity.trackId, DownloadState.QUEUED.name, 0, 0, null, System.currentTimeMillis())
            }
            throw c
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            partFile.delete()
            withContext(NonCancellable) {
                dao.updateProgress(entity.trackId, DownloadState.FAILED.name, 0, 0, null, System.currentTimeMillis())
            }
            throw t
        } finally {
            connection.disconnect()
        }
    }

    /** The format a download was requested in — the transcode settings frozen onto its row, or direct play. */
    private fun settingsFor(entity: TrackDownloadEntity): StreamSettings =
        if (entity.transcoded) {
            StreamSettings(
                transcode = true,
                codec = entity.codec?.let { runCatching { AudioCodec.valueOf(it) }.getOrNull() } ?: AudioCodec.OPUS,
                maxBitrateKbps = entity.bitrateKbps ?: 320,
            )
        } else {
            StreamSettings(transcode = false)
        }

    /**
     * Streams [input] into [output], publishing progress to Room on a [PROGRESS_EMIT_INTERVAL_MS]
     * tick. Returns the number of bytes written, or null if the user removed the download mid-flight.
     *
     * The progress write and the "was this cancelled?" check deliberately share one tick. Both used to
     * run per 64KB buffer, which meant a SQLite query for every chunk — hundreds for a single FLAC.
     * At 300ms the abort still feels instant while costing a fraction of the queries.
     */
    private suspend fun copyTracking(
        entity: TrackDownloadEntity,
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long,
    ): Long? {
        val buffer = ByteArray(BUFFER_SIZE)
        var written = 0L
        var lastEmit = System.currentTimeMillis()
        var read = input.read(buffer)
        while (read >= 0) {
            output.write(buffer, 0, read)
            written += read
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastEmit >= PROGRESS_EMIT_INTERVAL_MS) {
                if (dao.getTrack(entity.trackId) == null) return null
                dao.updateProgress(entity.trackId, DownloadState.DOWNLOADING.name, written, total, null, nowMs)
                lastEmit = nowMs
            }
            read = input.read(buffer)
        }
        output.flush()
        return written
    }

    /**
     * Moves the finished scratch file into its final name and marks the row COMPLETED.
     *
     * The rename must be checked: claiming COMPLETED after a rename that didn't happen persists a
     * filePath resolving to nothing, and the track would render as downloaded forever while silently
     * streaming instead. Failing here surfaces as an ordinary per-track failure.
     */
    private suspend fun publish(
        entity: TrackDownloadEntity,
        partFile: File,
        outFile: File,
        written: Long,
        total: Long,
    ) {
        if (outFile.exists()) outFile.delete()
        if (!partFile.renameTo(outFile)) {
            partFile.delete()
            error("Could not move ${partFile.name} into place as ${outFile.name}")
        }
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
