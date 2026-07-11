package pt.aguiarvieira.jellymusic.data.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import pt.aguiarvieira.jellymusic.core.util.Logx
import pt.aguiarvieira.jellymusic.data.db.AlbumDownloadEntity
import pt.aguiarvieira.jellymusic.data.db.DownloadDao
import pt.aguiarvieira.jellymusic.data.db.TrackDownloadEntity
import pt.aguiarvieira.jellymusic.data.jellyfin.StreamUrlBuilder
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.AudioCodec
import pt.aguiarvieira.jellymusic.domain.model.DownloadState
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.domain.repository.MusicRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads tracks to a portable file per track and tracks their state in Room. Downloads run in an
 * application-scoped coroutine (one at a time, sequentially) while the app process is alive; they do
 * not yet survive the process being killed.
 *
 * Playback prefers a local copy: [localFileUri] returns a `file://` URI for a completed download.
 */
@Singleton
class MusicDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val urlBuilder: StreamUrlBuilder,
    private val musicRepository: MusicRepository,
    private val settingsStore: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processorMutex = Mutex()

    /** trackId → absolute file path of a completed download, kept in memory for fast playback lookup. */
    @Volatile
    private var completedPaths: Map<String, String> = emptyMap()

    private val downloadsDir: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    init {
        // Keep the local-file map current for playback resolution.
        scope.launch {
            dao.observeCompletedTracks().collect { rows ->
                completedPaths = rows
                    .mapNotNull { row -> row.filePath?.let { row.trackId to it } }
                    .toMap()
            }
        }
        // Resume any downloads left pending by a previous session.
        kickProcessor()
    }

    /** Absolute `file://` URI for a completed, still-present download, else null. */
    fun localFileUri(trackId: String): String? {
        val path = completedPaths[trackId] ?: return null
        return if (File(path).exists()) "file://$path" else null
    }

    // --- Enqueue ---

    fun downloadTrack(track: Track, transcode: Boolean) {
        scope.launch {
            upsertQueued(track, track.albumId, transcode)
            kickProcessor()
        }
    }

    fun downloadAlbum(albumId: String, albumName: String, albumArtist: String?, artworkUrl: String?, transcode: Boolean) {
        scope.launch {
            val tracks = musicRepository.getAlbumTracks(albumId).getOrNull().orEmpty()
            if (tracks.isEmpty()) return@launch
            dao.upsertAlbum(
                AlbumDownloadEntity(
                    albumId = albumId,
                    name = albumName,
                    artist = albumArtist,
                    artworkUrl = artworkUrl,
                    totalTracks = tracks.size,
                    transcoded = transcode,
                    requestedAt = System.currentTimeMillis(),
                ),
            )
            tracks.forEach { upsertQueued(it, albumId, transcode) }
            kickProcessor()
        }
    }

    // --- Remove ---

    fun removeTrack(trackId: String) {
        scope.launch {
            deleteFilesFor(trackId)
            dao.deleteTrack(trackId)
        }
    }

    fun removeAlbum(albumId: String) {
        scope.launch {
            dao.deleteAlbum(albumId)
            // Delete every track file we hold for this album, then drop the rows.
            dao.completedTracks().filter { it.albumId == albumId }.forEach { deleteFilesFor(it.trackId) }
            dao.deleteTracksForAlbum(albumId)
        }
    }

    // --- Internals ---

    private suspend fun upsertQueued(track: Track, albumId: String?, transcode: Boolean) {
        // Don't re-queue a track that's already downloaded/queued.
        val existing = dao.getTrack(track.id)
        if (existing != null && existing.state != DownloadState.FAILED.name) return
        // Transcode reuses the current streaming codec/bitrate; only the on/off flag is per-download.
        val settings = if (transcode) settingsStore.streamSettings.first().copy(transcode = true) else StreamSettings(transcode = false)
        dao.upsertTrack(
            TrackDownloadEntity(
                trackId = track.id,
                albumId = albumId,
                title = track.name,
                artist = track.artist,
                album = track.album,
                trackNumber = track.trackNumber,
                durationMs = track.durationMs,
                artworkUrl = track.artworkUrl,
                transcoded = transcode,
                codec = if (transcode) settings.codec.name else null,
                bitrateKbps = if (transcode) settings.maxBitrateKbps else null,
                state = DownloadState.QUEUED.name,
                filePath = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun kickProcessor() {
        scope.launch {
            // Only one processor loop at a time.
            if (!processorMutex.tryLock()) return@launch
            try {
                while (true) {
                    val next = dao.nextPending() ?: break
                    runCatching { process(next) }
                        .onFailure { Logx.w("Downloads", "Download failed for ${next.trackId}", it) }
                }
            } finally {
                processorMutex.unlock()
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
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            val total = connection.contentLengthLong.coerceAtLeast(0L)
            dao.updateProgress(entity.trackId, DownloadState.DOWNLOADING.name, 0, total, null, now)

            connection.inputStream.use { input ->
                partFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
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
                        if (nowMs - lastEmit >= 300) {
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
        } catch (t: Throwable) {
            partFile.delete()
            dao.updateProgress(entity.trackId, DownloadState.FAILED.name, 0, 0, null, System.currentTimeMillis())
            throw t
        } finally {
            connection.disconnect()
        }
    }

    private fun deleteFilesFor(trackId: String) {
        downloadsDir.listFiles { f -> f.name.startsWith("$trackId.") }?.forEach { it.delete() }
    }
}
