package pt.aguiarvieira.jellymusic.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pt.aguiarvieira.jellymusic.data.db.AlbumDownloadEntity
import pt.aguiarvieira.jellymusic.data.db.DownloadDao
import pt.aguiarvieira.jellymusic.data.db.TrackDownloadEntity
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.AudioCodec
import pt.aguiarvieira.jellymusic.domain.model.DownloadState
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.domain.repository.MusicRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns download enqueue/removal and Room bookkeeping, and schedules the [DownloadWorker] to do the
 * actual downloading in the background (so it survives the process being killed).
 *
 * Playback prefers a local copy: [localFileUri] returns a `file://` URI for a completed download.
 */
@Singleton
class MusicDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val musicRepository: MusicRepository,
    private val settingsStore: SettingsStore,
    private val artworkCache: ArtworkCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** trackId → completed download row, kept in memory for fast playback resolution. */
    @Volatile
    private var completedRows: Map<String, TrackDownloadEntity> = emptyMap()

    private val downloadsDir: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    init {
        // Keep the completed-download map current for playback resolution.
        scope.launch {
            dao.observeCompletedTracks().collect { rows ->
                completedRows = rows.filter { it.filePath != null }.associateBy { it.trackId }
            }
        }
        // Pick up anything left pending by a previous session.
        scheduleWork()
    }

    /** Absolute `file://` URI for a completed, still-present download, else null. */
    fun localFileUri(trackId: String): String? {
        val path = completedRows[trackId]?.filePath ?: return null
        return if (File(path).exists()) "file://$path" else null
    }

    /** The actual format of a completed local download (for reporting what's being played), else null. */
    fun localFormat(trackId: String): StreamSettings? {
        val row = completedRows[trackId] ?: return null
        return if (row.transcoded) {
            StreamSettings(
                transcode = true,
                codec = row.codec?.let { runCatching { AudioCodec.valueOf(it) }.getOrNull() } ?: AudioCodec.OPUS,
                maxBitrateKbps = row.bitrateKbps ?: 320,
            )
        } else {
            StreamSettings(transcode = false)
        }
    }

    // --- Enqueue ---

    fun downloadTrack(track: Track, transcode: Boolean) {
        scope.launch {
            upsertQueued(track, track.albumId, transcode)
            scheduleWork()
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
                    artworkPath = artworkCache.cache(albumId, artworkUrl),
                    totalTracks = tracks.size,
                    transcoded = transcode,
                    requestedAt = System.currentTimeMillis(),
                ),
            )
            tracks.forEach { upsertQueued(it, albumId, transcode) }
            scheduleWork()
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
            artworkCache.delete(albumId)
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
                discNumber = track.discNumber,
                trackNumber = track.trackNumber,
                durationMs = track.durationMs,
                artworkUrl = track.artworkUrl,
                // Cache the cover (keyed by album so an album's tracks share one file).
                artworkPath = artworkCache.cache(albumId ?: track.id, track.artworkUrl),
                transcoded = transcode,
                codec = if (transcode) settings.codec.name else null,
                bitrateKbps = if (transcode) settings.maxBitrateKbps else null,
                normalizationGainDb = track.normalizationGainDb,
                state = DownloadState.QUEUED.name,
                filePath = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Enqueue the background download worker; a single unique instance drains the whole queue. */
    private fun scheduleWork() {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DownloadWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private fun deleteFilesFor(trackId: String) {
        downloadsDir.listFiles { f -> f.name.startsWith("$trackId.") }?.forEach { it.delete() }
    }
}
