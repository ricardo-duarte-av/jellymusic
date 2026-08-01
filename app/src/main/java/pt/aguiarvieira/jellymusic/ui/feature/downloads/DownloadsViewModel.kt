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
import pt.aguiarvieira.jellymusic.data.db.AlbumDownloadEntity
import pt.aguiarvieira.jellymusic.data.db.DownloadDao
import pt.aguiarvieira.jellymusic.data.db.PlaylistDownloadEntity
import pt.aguiarvieira.jellymusic.data.db.TrackDownloadEntity
import pt.aguiarvieira.jellymusic.data.download.MusicDownloadManager
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.AlbumDownloadStatus
import pt.aguiarvieira.jellymusic.domain.model.DownloadState
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
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
            // Group tracks by album once (O(tracks)) instead of re-scanning the whole track list per
            // album (O(albums × tracks)); this recomputes several times a second during downloads.
            val tracksByAlbum = tracks.groupBy { it.albumId }
            albums.associate { album ->
                val albumTracks = tracksByAlbum[album.albumId].orEmpty()
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

    val playlistStatuses: StateFlow<Map<String, AlbumDownloadStatus>> =
        combine(dao.observePlaylists(), dao.observeTracks()) { playlists, tracks ->
            val stateByTrack = tracks.associate { it.trackId to it.state }
            playlists.associate { playlist ->
                val members = playlist.trackIds
                playlist.playlistId to AlbumDownloadStatus(
                    total = playlist.totalTracks,
                    completed = members.count { stateByTrack[it] == DownloadState.COMPLETED.name },
                    downloading = members.any {
                        stateByTrack[it] == DownloadState.QUEUED.name || stateByTrack[it] == DownloadState.DOWNLOADING.name
                    },
                    failed = members.any { stateByTrack[it] == DownloadState.FAILED.name },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Downloaded/queued albums, playlists and tracks, for the Downloads manager screen. */
    val downloadedAlbums: StateFlow<List<AlbumDownloadEntity>> =
        dao.observeAlbums().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloadedPlaylists: StateFlow<List<PlaylistDownloadEntity>> =
        dao.observePlaylists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloadedTracks: StateFlow<List<TrackDownloadEntity>> =
        dao.observeTracks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The Offline > download transcode settings: seeds the download dialog's switch and tells the
     * user which format a transcoded download would land in.
     */
    val downloadSettings: StateFlow<StreamSettings> =
        settingsStore.downloadSettings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StreamSettings())

    fun isMetered(): Boolean = networkMonitor.isActiveNetworkMetered()

    fun downloadTrack(track: Track, transcode: Boolean) = downloadManager.downloadTrack(track, transcode)

    fun downloadAlbum(
        albumId: String,
        albumName: String,
        albumArtist: String?,
        artworkUrl: String?,
        transcode: Boolean,
    ) = downloadManager.downloadAlbum(albumId, albumName, albumArtist, artworkUrl, transcode)

    fun downloadPlaylist(playlistId: String, playlistName: String, artworkUrl: String?, transcode: Boolean) =
        downloadManager.downloadPlaylist(playlistId, playlistName, artworkUrl, transcode)

    fun removeTrack(trackId: String) = downloadManager.removeTrack(trackId)
    fun removeAlbum(albumId: String) = downloadManager.removeAlbum(albumId)
    fun removePlaylist(playlistId: String) = downloadManager.removePlaylist(playlistId)

    private fun TrackDownloadEntity.toStatus(): TrackDownloadStatus {
        val downloadState = runCatching { DownloadState.valueOf(state) }.getOrDefault(DownloadState.FAILED)
        // The transcoding endpoint sends no Content-Length, so estimate the size from the target
        // bitrate × duration to still show a determinate bar.
        val denom = when {
            totalBytes > 0 -> totalBytes
            transcoded && bitrateKbps != null && durationMs != null && durationMs > 0 ->
                bitrateKbps.toLong() * 1000L / 8L * (durationMs / 1000L)
            else -> 0L
        }
        val progress = when {
            downloadState == DownloadState.COMPLETED -> 1f
            downloadedBytes > 0 && denom > 0 -> (downloadedBytes.toFloat() / denom).coerceIn(0f, 0.99f)
            else -> -1f // queued or unknown size → indeterminate
        }
        // The local copy's format, shown on track rows once downloaded.
        val localFormat = when {
            downloadState != DownloadState.COMPLETED -> null
            transcoded -> listOfNotNull(codec, bitrateKbps?.toString()).joinToString(" ").ifEmpty { null }
            else -> "Original"
        }
        return TrackDownloadStatus(downloadState, progress, localFormat)
    }
}
