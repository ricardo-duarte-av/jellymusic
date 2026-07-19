package pt.aguiarvieira.jellymusic.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pt.aguiarvieira.jellymusic.data.db.AlbumDownloadEntity
import pt.aguiarvieira.jellymusic.data.db.DownloadDao
import pt.aguiarvieira.jellymusic.data.db.PlaylistDownloadEntity
import pt.aguiarvieira.jellymusic.data.db.TrackDownloadEntity
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.AudioCodec
import pt.aguiarvieira.jellymusic.domain.model.DownloadState
import pt.aguiarvieira.jellymusic.domain.model.Playlist
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
            upsertQueued(track, track.albumId, transcode, manual = true)
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
                    manualRequest = true,
                    // Preserve an existing favourite claim so un-favourite later still cleans up right.
                    favoriteRequest = dao.getAlbum(albumId)?.favoriteRequest ?: false,
                    requestedAt = System.currentTimeMillis(),
                ),
            )
            tracks.forEach { upsertQueued(it, albumId, transcode, manual = true) }
            scheduleWork()
        }
    }

    /**
     * Download a whole playlist as a group. Writes a [PlaylistDownloadEntity] holding the member track
     * IDs (so progress can be computed and the group cleanly removed) and enqueues each track, keeping
     * its original album association so it still resolves for playback and album grouping.
     */
    fun downloadPlaylist(playlistId: String, playlistName: String, artworkUrl: String?, transcode: Boolean) {
        scope.launch {
            val tracks = musicRepository.getPlaylistTracks(playlistId).getOrNull().orEmpty()
            if (tracks.isEmpty()) return@launch
            dao.upsertPlaylist(
                PlaylistDownloadEntity(
                    playlistId = playlistId,
                    name = playlistName,
                    artworkUrl = artworkUrl,
                    artworkPath = artworkCache.cache(playlistId, artworkUrl),
                    totalTracks = tracks.size,
                    trackIds = tracks.map { it.id },
                    transcoded = transcode,
                    manualRequest = true,
                    // Preserve an existing favourite claim so un-favourite later still cleans up right.
                    favoriteRequest = dao.getPlaylist(playlistId)?.favoriteRequest ?: false,
                    requestedAt = System.currentTimeMillis(),
                ),
            )
            tracks.forEach { upsertQueued(it, it.albumId, transcode, manual = true) }
            scheduleWork()
        }
    }

    // --- Favourite auto-download (see FavoriteDownloadSyncManager) ---

    /**
     * Queue a favourite track for auto-download. If a row already exists (e.g. a manual download),
     * just stamps the favourite claim so it survives an un-favourite without re-downloading. Callers
     * must schedule the favourite work afterwards via [scheduleFavoriteWork].
     */
    suspend fun queueFavorite(track: Track, transcode: Boolean) {
        val existing = dao.getTrack(track.id)
        if (existing != null) {
            dao.setFavoriteRequest(track.id, true)
        } else {
            upsertQueued(track, track.albumId, transcode, manual = false, favorite = true)
        }
    }

    /** Drop a favourite's claim; physically removes the file only if it wasn't also a manual download. */
    suspend fun releaseFavorite(trackId: String) {
        val row = dao.getTrack(trackId) ?: return
        dao.setFavoriteRequest(trackId, false)
        if (!row.manualRequest) removeFavoriteTrack(trackId)
    }

    private suspend fun removeFavoriteTrack(trackId: String) {
        deleteFilesFor(trackId)
        dao.deleteTrack(trackId)
    }

    /**
     * Ensure a favourite album has a download-group row (so it renders the downloaded badge like a
     * manual album download). Track files are owned by the track-level reconcile — this only manages
     * the group metadata. If a group already exists (e.g. a manual download), just stamps the claim.
     */
    suspend fun ensureFavoriteAlbumGroup(album: Album, totalTracks: Int, transcode: Boolean) {
        val existing = dao.getAlbum(album.id)
        if (existing != null) {
            if (!existing.favoriteRequest) dao.setAlbumFavoriteRequest(album.id, true)
        } else {
            dao.upsertAlbum(
                AlbumDownloadEntity(
                    albumId = album.id,
                    name = album.name,
                    artist = album.artist,
                    artworkUrl = album.artworkUrl,
                    artworkPath = artworkCache.cache(album.id, album.artworkUrl),
                    totalTracks = totalTracks,
                    transcoded = transcode,
                    manualRequest = false,
                    favoriteRequest = true,
                    requestedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Drop a favourite album's group row; removes it only if it wasn't also a manual download. */
    suspend fun releaseFavoriteAlbumGroup(albumId: String) {
        val existing = dao.getAlbum(albumId) ?: return
        dao.setAlbumFavoriteRequest(albumId, false)
        if (!existing.manualRequest) dao.deleteAlbum(albumId)
    }

    /** Ensure a favourite playlist has a download-group row. See [ensureFavoriteAlbumGroup]. */
    suspend fun ensureFavoritePlaylistGroup(playlist: Playlist, trackIds: List<String>, transcode: Boolean) {
        val existing = dao.getPlaylist(playlist.id)
        if (existing != null) {
            if (!existing.favoriteRequest) dao.setPlaylistFavoriteRequest(playlist.id, true)
        } else {
            dao.upsertPlaylist(
                PlaylistDownloadEntity(
                    playlistId = playlist.id,
                    name = playlist.name,
                    artworkUrl = playlist.artworkUrl,
                    artworkPath = artworkCache.cache(playlist.id, playlist.artworkUrl),
                    totalTracks = trackIds.size,
                    trackIds = trackIds,
                    transcoded = transcode,
                    manualRequest = false,
                    favoriteRequest = true,
                    requestedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Drop a favourite playlist's group row; removes it only if it wasn't also a manual download. */
    suspend fun releaseFavoritePlaylistGroup(playlistId: String) {
        val existing = dao.getPlaylist(playlistId) ?: return
        dao.setPlaylistFavoriteRequest(playlistId, false)
        if (!existing.manualRequest) dao.deletePlaylist(playlistId)
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

    /**
     * Drop a playlist download group. A track is only physically removed if nothing else still needs
     * it — i.e. it isn't part of a downloaded album and isn't a member of any other downloaded
     * playlist. Shared tracks stay on disk so the other group keeps working.
     */
    fun removePlaylist(playlistId: String) {
        scope.launch {
            val entity = dao.getPlaylist(playlistId) ?: return@launch
            dao.deletePlaylist(playlistId)
            artworkCache.delete(playlistId)
            val albumIds = dao.downloadedAlbumIds().toSet()
            // Tracks still claimed by another remaining playlist group.
            val stillReferenced = dao.playlists().flatMap { it.trackIds }.toSet()
            entity.trackIds.forEach { trackId ->
                val track = dao.getTrack(trackId) ?: return@forEach
                val inDownloadedAlbum = track.albumId != null && track.albumId in albumIds
                if (!inDownloadedAlbum && trackId !in stillReferenced) {
                    deleteFilesFor(trackId)
                    dao.deleteTrack(trackId)
                }
            }
        }
    }

    // --- Internals ---

    private suspend fun upsertQueued(
        track: Track,
        albumId: String?,
        transcode: Boolean,
        manual: Boolean,
        favorite: Boolean = false,
    ) {
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
                manualRequest = manual,
                favoriteRequest = favorite,
                codec = if (transcode) settings.codec.name else null,
                bitrateKbps = if (transcode) settings.maxBitrateKbps else null,
                normalizationGainDb = track.normalizationGainDb,
                state = DownloadState.QUEUED.name,
                filePath = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Enqueue the background download worker; a single unique instance drains the manual queue. */
    private fun scheduleWork() {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setInputData(workDataOf(DownloadWorker.KEY_SCOPE to DownloadScope.MANUAL.name))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DownloadWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Enqueue the favourite-download worker, draining the favourite-only queue. Wi-Fi-only by default
     * ([allowMetered] = false → [NetworkType.UNMETERED]); [allowMetered] relaxes it to any connection.
     * REPLACE so a policy change (metered toggle) re-applies the new constraint immediately.
     */
    fun scheduleFavoriteWork(allowMetered: Boolean) {
        val networkType = if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
            .setInputData(workDataOf(DownloadWorker.KEY_SCOPE to DownloadScope.FAVORITE.name))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DownloadWorker.FAVORITE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun deleteFilesFor(trackId: String) {
        downloadsDir.listFiles { f -> f.name.startsWith("$trackId.") }?.forEach { it.delete() }
    }
}
