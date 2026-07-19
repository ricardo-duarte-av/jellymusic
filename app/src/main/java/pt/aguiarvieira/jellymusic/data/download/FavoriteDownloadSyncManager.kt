package pt.aguiarvieira.jellymusic.data.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pt.aguiarvieira.jellymusic.core.util.Logx
import pt.aguiarvieira.jellymusic.data.db.DownloadDao
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.domain.repository.MusicRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the user's Jellyfin favourites downloaded for offline listening. When the "Download
 * favourites" setting is on, [requestSync] reconciles the server's favourites against what's on
 * disk: newly-favourited items are queued, and items that stop being favourites are dropped
 * (favourite-only files are deleted; a track that's also a manual download is kept — manual wins).
 *
 * Favourites are collected across *all* libraries (favourite tracks + the tracks of favourite albums
 * and playlists) so switching library never deletes anything — it just re-triggers a reconcile.
 * Downloading is delegated to [MusicDownloadManager], whose favourite worker is Wi-Fi-only by default.
 */
@Singleton
class FavoriteDownloadSyncManager @Inject constructor(
    private val musicRepository: MusicRepository,
    private val settingsStore: SettingsStore,
    private val downloadDao: DownloadDao,
    private val downloadManager: MusicDownloadManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Serialize reconciles so overlapping triggers (app start + library switch) don't race.
    private val mutex = Mutex()

    /** Fire-and-forget reconcile; no-ops when the setting is off. Safe to call repeatedly. */
    fun requestSync() {
        scope.launch { mutex.withLock { sync() } }
    }

    /** Called when the setting is turned off: release every favourite claim (see [MusicDownloadManager]). */
    fun requestDisable() {
        scope.launch {
            mutex.withLock {
                downloadDao.favoriteDownloads().forEach { downloadManager.releaseFavorite(it.trackId) }
                downloadDao.favoriteAlbums().forEach { downloadManager.releaseFavoriteAlbumGroup(it.albumId) }
                downloadDao.favoritePlaylists().forEach { downloadManager.releaseFavoritePlaylistGroup(it.playlistId) }
            }
        }
    }

    private suspend fun sync() {
        if (!settingsStore.downloadFavorites.first()) return

        // Favourite tracks are the primary signal. If that fetch fails (offline / unauthenticated),
        // abort without reconciling so we never mass-delete downloads on a transient failure.
        val favoriteTracks = musicRepository.getFavoriteTracks(null).getOrElse {
            Logx.w("FavoriteSync", "Skipping sync: favourite tracks fetch failed", it)
            return
        }

        val transcode = settingsStore.streamSettings.first().transcode
        val desired = LinkedHashMap<String, Track>()
        favoriteTracks.forEach { desired[it.id] = it }

        // Expand favourite albums/playlists into their tracks + group metadata. [GroupIds.complete] is
        // false if any sub-fetch failed, in which case we only add — never remove — this cycle, so a
        // transient failure can't delete downloads.
        val albumIds = expandFavoriteAlbums(desired, transcode)
        val playlistIds = expandFavoritePlaylists(desired, transcode)
        val desiredComplete = albumIds.complete && playlistIds.complete

        val current = downloadDao.favoriteDownloads().associateBy { it.trackId }

        // Add: queue anything favourited that we don't already claim.
        desired.values.forEach { track ->
            if (track.id !in current) downloadManager.queueFavorite(track, transcode)
        }

        if (desiredComplete) removeStale(current.keys, desired.keys, albumIds.ids, playlistIds.ids)

        // Give previously-failed favourites another go on this sync.
        downloadDao.requeueFailedFavorites(System.currentTimeMillis())

        downloadManager.scheduleFavoriteWork(allowMetered = settingsStore.downloadFavoritesOnMetered.first())
    }

    /** IDs of the favourite groups we saw, and whether the fetch that produced them was complete. */
    private data class GroupIds(val ids: Set<String>, val complete: Boolean)

    /** Adds favourite-album tracks to [desired] and ensures each album's group row exists. */
    private suspend fun expandFavoriteAlbums(desired: MutableMap<String, Track>, transcode: Boolean): GroupIds {
        val albums = musicRepository.getAlbums(null, favoritesOnly = true).getOrNull()
            ?: return GroupIds(emptySet(), complete = false)
        val ids = mutableSetOf<String>()
        var complete = true
        albums.forEach { album ->
            val tracks = musicRepository.getAlbumTracks(album.id).getOrNull()
            if (tracks == null) {
                complete = false
            } else {
                ids += album.id
                // Group metadata so the album shows the downloaded badge like a manual download.
                downloadManager.ensureFavoriteAlbumGroup(album, tracks.size, transcode)
                tracks.forEach { desired.putIfAbsent(it.id, it) }
            }
        }
        return GroupIds(ids, complete)
    }

    /** Adds favourite-playlist tracks to [desired] and ensures each playlist's group row exists. */
    private suspend fun expandFavoritePlaylists(desired: MutableMap<String, Track>, transcode: Boolean): GroupIds {
        val playlists = musicRepository.getPlaylists(null, favoritesOnly = true).getOrNull()
            ?: return GroupIds(emptySet(), complete = false)
        val ids = mutableSetOf<String>()
        var complete = true
        playlists.forEach { playlist ->
            val tracks = musicRepository.getPlaylistTracks(playlist.id).getOrNull()
            if (tracks == null) {
                complete = false
            } else {
                ids += playlist.id
                downloadManager.ensureFavoritePlaylistGroup(playlist, tracks.map { it.id }, transcode)
                tracks.forEach { desired.putIfAbsent(it.id, it) }
            }
        }
        return GroupIds(ids, complete)
    }

    /** Release tracks and album/playlist groups that are no longer favourites. */
    private suspend fun removeStale(
        currentTrackIds: Set<String>,
        desiredTrackIds: Set<String>,
        favoriteAlbumIds: Set<String>,
        favoritePlaylistIds: Set<String>,
    ) {
        currentTrackIds.forEach { trackId ->
            if (trackId !in desiredTrackIds) downloadManager.releaseFavorite(trackId)
        }
        downloadDao.favoriteAlbums().forEach { group ->
            if (group.albumId !in favoriteAlbumIds) downloadManager.releaseFavoriteAlbumGroup(group.albumId)
        }
        downloadDao.favoritePlaylists().forEach { group ->
            if (group.playlistId !in favoritePlaylistIds) downloadManager.releaseFavoritePlaylistGroup(group.playlistId)
        }
    }
}
