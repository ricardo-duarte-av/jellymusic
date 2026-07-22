package pt.aguiarvieira.jellymusic.ui.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.aguiarvieira.jellymusic.data.download.FavoriteDownloadSyncManager
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.domain.repository.MusicRepository
import pt.aguiarvieira.jellymusic.ui.common.ContentState
import pt.aguiarvieira.jellymusic.ui.common.toContentState
import pt.aguiarvieira.jellymusic.ui.navigation.Routes
import javax.inject.Inject

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val favoriteSyncManager: FavoriteDownloadSyncManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val args = savedStateHandle.toRoute<Routes.AlbumDetail>()
    val title: String = args.albumName
    val albumId: String = args.albumId

    /** Cover URL from the grid, used to render the hero before tracks (its usual source) load. */
    val heroArtworkUrl: String? = args.artworkUrl

    private val _tracks = MutableStateFlow<ContentState<List<Track>>>(ContentState.Loading)
    val tracks = _tracks.asStateFlow()

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite = _isFavorite.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    init {
        viewModelScope.launch {
            _tracks.value = musicRepository.getAlbumTracks(args.albumId).toContentState()
        }
        viewModelScope.launch {
            musicRepository.getFavorite(args.albumId).onSuccess { _isFavorite.value = it }
        }
    }

    /**
     * Re-fetch tracks + favourite from the server. Picks up a cover art change made on the server:
     * the fresh tracks carry the album's new image tag, so the artwork URL changes and Coil fetches
     * the updated cover instead of serving its cache. See [ItemMappers].
     */
    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            _tracks.value = musicRepository.getAlbumTracks(args.albumId).toContentState()
            musicRepository.getFavorite(args.albumId).onSuccess { _isFavorite.value = it }
            _isRefreshing.value = false
        }
    }

    fun toggleFavorite() =
        toggleFavoriteIn(_isFavorite, musicRepository, favoriteSyncManager, args.albumId)

    fun toggleTrackFavorite(track: Track) =
        toggleTrackFavoriteIn(_tracks, musicRepository, favoriteSyncManager, track)
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val favoriteSyncManager: FavoriteDownloadSyncManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val args = savedStateHandle.toRoute<Routes.PlaylistDetail>()
    val title: String = args.playlistName

    private val _tracks = MutableStateFlow<ContentState<List<Track>>>(ContentState.Loading)
    val tracks = _tracks.asStateFlow()

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite = _isFavorite.asStateFlow()

    init {
        viewModelScope.launch {
            _tracks.value = musicRepository.getPlaylistTracks(args.playlistId).toContentState()
        }
        viewModelScope.launch {
            musicRepository.getFavorite(args.playlistId).onSuccess { _isFavorite.value = it }
        }
    }

    fun toggleFavorite() =
        toggleFavoriteIn(_isFavorite, musicRepository, favoriteSyncManager, args.playlistId)

    fun toggleTrackFavorite(track: Track) =
        toggleTrackFavoriteIn(_tracks, musicRepository, favoriteSyncManager, track)
}

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val favoriteSyncManager: FavoriteDownloadSyncManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val args = savedStateHandle.toRoute<Routes.ArtistDetail>()
    val title: String = args.artistName

    private val _albums = MutableStateFlow<ContentState<List<Album>>>(ContentState.Loading)
    val albums = _albums.asStateFlow()

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite = _isFavorite.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    init {
        viewModelScope.launch {
            _albums.value = musicRepository.getArtistAlbums(args.artistId).toContentState()
        }
        viewModelScope.launch {
            musicRepository.getFavorite(args.artistId).onSuccess { _isFavorite.value = it }
        }
    }

    /** Re-fetch the artist's albums + favourite from the server (pull-to-refresh). */
    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            _albums.value = musicRepository.getArtistAlbums(args.artistId).toContentState()
            musicRepository.getFavorite(args.artistId).onSuccess { _isFavorite.value = it }
            _isRefreshing.value = false
        }
    }

    fun toggleFavorite() =
        toggleFavoriteIn(_isFavorite, musicRepository, favoriteSyncManager, args.artistId)
}

/**
 * Optimistically flips [itemId]'s favourite state in [flow] and pushes it to the server, reverting
 * on failure. On success, kicks the favourite-download sync so the change is reflected offline.
 * Shared by the detail viewmodels' top-bar heart toggle.
 */
private fun ViewModel.toggleFavoriteIn(
    flow: MutableStateFlow<Boolean>,
    repository: MusicRepository,
    syncManager: FavoriteDownloadSyncManager,
    itemId: String,
) {
    val target = !flow.value
    flow.value = target
    viewModelScope.launch {
        repository.setFavorite(itemId, target)
            .onSuccess { syncManager.requestSync() }
            .onFailure { flow.value = !target }
    }
}

/** Optimistically flips one track's favourite state within a track-list state flow. */
private fun ViewModel.toggleTrackFavoriteIn(
    flow: MutableStateFlow<ContentState<List<Track>>>,
    repository: MusicRepository,
    syncManager: FavoriteDownloadSyncManager,
    track: Track,
) {
    val target = !track.isFavorite
    fun setInList(value: Boolean) = flow.update { state ->
        if (state is ContentState.Data) {
            ContentState.Data(state.value.map { if (it.id == track.id) it.copy(isFavorite = value) else it })
        } else {
            state
        }
    }
    setInList(target)
    viewModelScope.launch {
        repository.setFavorite(track.id, target)
            .onSuccess { syncManager.requestSync() }
            .onFailure { setInList(!target) }
    }
}
