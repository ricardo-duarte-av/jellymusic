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

        val desired = LinkedHashMap<String, Track>()
        favoriteTracks.forEach { desired[it.id] = it }
        // Favourite albums/playlists expand to their tracks; expansion failures are tolerated.
        musicRepository.getAlbums(null, favoritesOnly = true).getOrNull()?.forEach { album ->
            musicRepository.getAlbumTracks(album.id).getOrNull()?.forEach { desired.putIfAbsent(it.id, it) }
        }
        musicRepository.getPlaylists(null, favoritesOnly = true).getOrNull()?.forEach { playlist ->
            musicRepository.getPlaylistTracks(playlist.id).getOrNull()?.forEach { desired.putIfAbsent(it.id, it) }
        }

        val transcode = settingsStore.streamSettings.first().transcode
        val current = downloadDao.favoriteDownloads().associateBy { it.trackId }

        // Add: queue anything favourited that we don't already claim.
        desired.values.forEach { track ->
            if (track.id !in current) downloadManager.queueFavorite(track, transcode)
        }
        // Remove: release claims on tracks that are no longer favourites.
        current.keys.forEach { trackId ->
            if (trackId !in desired) downloadManager.releaseFavorite(trackId)
        }
        // Give previously-failed favourites another go on this sync.
        downloadDao.requeueFailedFavorites(System.currentTimeMillis())

        downloadManager.scheduleFavoriteWork(allowMetered = settingsStore.downloadFavoritesOnMetered.first())
    }
}
