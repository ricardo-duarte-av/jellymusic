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
import pt.aguiarvieira.jellymusic.core.image.ImageCacheManager
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
    private val imageCache: ImageCacheManager,
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
     * Re-fetch tracks + favourite from the server. Picks up a cover art change made on the server
     * (the fresh tracks carry the album's new image tag, so the URL changes and Coil fetches the new
     * cover). Also evicts the current artwork from Coil's cache and cache-busts the reloaded URLs, so
     * a poisoned/corrupt cached image recovers on refresh instead of needing an app cache clear.
     */
    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            _tracks.value = musicRepository.getAlbumTracks(args.albumId).refreshArtwork(imageCache)
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
    private val imageCache: ImageCacheManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val args = savedStateHandle.toRoute<Routes.PlaylistDetail>()
    val title: String = args.playlistName

    private val _tracks = MutableStateFlow<ContentState<List<Track>>>(ContentState.Loading)
    val tracks = _tracks.asStateFlow()

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite = _isFavorite.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    init {
        viewModelScope.launch {
            _tracks.value = musicRepository.getPlaylistTracks(args.playlistId).toContentState()
        }
        viewModelScope.launch {
            musicRepository.getFavorite(args.playlistId).onSuccess { _isFavorite.value = it }
        }
    }

    /**
     * Re-fetch the playlist's tracks + favourite from the server (pull-to-refresh). Evicts the
     * current artwork from Coil's cache and cache-busts the reloaded URLs so a poisoned cached cover
     * recovers on refresh.
     */
    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            _tracks.value = musicRepository.getPlaylistTracks(args.playlistId).refreshArtwork(imageCache)
            musicRepository.getFavorite(args.playlistId).onSuccess { _isFavorite.value = it }
            _isRefreshing.value = false
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
    private val imageCache: ImageCacheManager,
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

    /**
     * Re-fetch the artist's albums + favourite from the server (pull-to-refresh). Evicts the current
     * album covers from Coil's cache and cache-busts the reloaded URLs so a poisoned cached cover
     * recovers on refresh.
     */
    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            _albums.value = musicRepository.getArtistAlbums(args.artistId).refreshAlbumArtwork(imageCache)
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

/**
 * On a successful refetch, evicts the items' current artwork from Coil's cache and returns them with
 * a one-shot cache-bust on each URL. Eviction un-poisons a corrupt cached image for every screen;
 * the cache-bust makes the reloaded screen re-request rather than re-show the failed placeholder
 * (Coil latches a failed URL until it changes). On failure the result is passed through untouched.
 */
private suspend fun Result<List<Track>>.refreshArtwork(
    imageCache: ImageCacheManager,
): ContentState<List<Track>> {
    val tracks = getOrNull() ?: return toContentState()
    imageCache.evict(tracks.mapNotNull { it.artworkUrl })
    val bust = System.currentTimeMillis()
    return ContentState.Data(tracks.map { it.copy(artworkUrl = it.artworkUrl?.cacheBust(bust)) })
}

/** [refreshArtwork] for an album list (artist viewer). */
private suspend fun Result<List<Album>>.refreshAlbumArtwork(
    imageCache: ImageCacheManager,
): ContentState<List<Album>> {
    val albums = getOrNull() ?: return toContentState()
    imageCache.evict(albums.mapNotNull { it.artworkUrl })
    val bust = System.currentTimeMillis()
    return ContentState.Data(albums.map { it.copy(artworkUrl = it.artworkUrl?.cacheBust(bust)) })
}

/** Appends a one-shot query param so Coil treats this as a fresh URL, bypassing its cache. */
private fun String.cacheBust(nonce: Long): String =
    this + (if (contains('?')) "&" else "?") + "cb=" + nonce
