package pt.aguiarvieira.jellymusic.ui.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import pt.aguiarvieira.jellymusic.core.network.NetworkMonitor
import pt.aguiarvieira.jellymusic.data.db.DownloadDao
import pt.aguiarvieira.jellymusic.data.db.TrackDownloadEntity
import pt.aguiarvieira.jellymusic.data.download.MusicDownloadManager
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.AlbumDownloadStatus
import pt.aguiarvieira.jellymusic.domain.model.DownloadState
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.domain.model.TrackDownloadStatus
import javax.inject.Inject

/**
 * Shared download state + actions for the browse/detail screens: per-track and per-album status maps
 * (observed from Room) and enqueue/remove operations (delegated to [MusicDownloadManager]).
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadManager: MusicDownloadManager,
    private val networkMonitor: NetworkMonitor,
    dao: DownloadDao,
    settingsStore: SettingsStore,
) : ViewModel() {

    val trackStatuses: StateFlow<Map<String, TrackDownloadStatus>> =
        dao.observeTracks()
            .map { rows -> rows.associate { it.trackId to it.toStatus() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val albumStatuses: StateFlow<Map<String, AlbumDownloadStatus>> =
        combine(dao.observeAlbums(), dao.observeTracks()) { albums, tracks ->
            albums.associate { album ->
                val albumTracks = tracks.filter { it.albumId == album.albumId }
                album.albumId to AlbumDownloadStatus(
                    total = album.totalTracks,
                    completed = albumTracks.count { it.state == DownloadState.COMPLETED.name },
                    downloading = albumTracks.any {
                        it.state == DownloadState.QUEUED.name || it.state == DownloadState.DOWNLOADING.name
                    },
                    failed = albumTracks.any { it.state == DownloadState.FAILED.name },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Default transcode choice for the download dialog, mirroring the current streaming setting. */
    val transcodeDefault: StateFlow<Boolean> =
        settingsStore.streamSettings
            .map { it.transcode }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun isMetered(): Boolean = networkMonitor.isActiveNetworkMetered()

    fun downloadTrack(track: Track, transcode: Boolean) = downloadManager.downloadTrack(track, transcode)

    fun downloadAlbum(
        albumId: String,
        albumName: String,
        albumArtist: String?,
        artworkUrl: String?,
        transcode: Boolean,
    ) = downloadManager.downloadAlbum(albumId, albumName, albumArtist, artworkUrl, transcode)

    fun removeTrack(trackId: String) = downloadManager.removeTrack(trackId)
    fun removeAlbum(albumId: String) = downloadManager.removeAlbum(albumId)

    private fun TrackDownloadEntity.toStatus(): TrackDownloadStatus {
        val progress = when {
            state == DownloadState.COMPLETED.name -> 1f
            totalBytes > 0 -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            else -> -1f // indeterminate
        }
        val downloadState = runCatching { DownloadState.valueOf(state) }.getOrDefault(DownloadState.FAILED)
        return TrackDownloadStatus(downloadState, progress)
    }
}
