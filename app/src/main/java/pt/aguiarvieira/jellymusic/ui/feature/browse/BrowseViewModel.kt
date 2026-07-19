package pt.aguiarvieira.jellymusic.ui.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.aguiarvieira.jellymusic.data.download.FavoriteDownloadSyncManager
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.AlbumSort
import pt.aguiarvieira.jellymusic.domain.model.Artist
import pt.aguiarvieira.jellymusic.domain.model.MusicLibrary
import pt.aguiarvieira.jellymusic.domain.model.Playlist
import pt.aguiarvieira.jellymusic.domain.repository.LibraryRepository
import pt.aguiarvieira.jellymusic.domain.repository.MusicRepository
import pt.aguiarvieira.jellymusic.ui.common.ContentState
import pt.aguiarvieira.jellymusic.ui.common.toContentState
import javax.inject.Inject

data class BrowseUiState(
    val selectedLibrary: MusicLibrary = MusicLibrary.all(),
    val libraries: List<MusicLibrary> = listOf(MusicLibrary.all()),
    val albumSort: AlbumSort = AlbumSort.DEFAULT,
    val albumSortDescending: Boolean = false,
    val artists: ContentState<List<Artist>> = ContentState.Loading,
    val playlists: ContentState<List<Playlist>> = ContentState.Loading,
    /** True while a pull-to-refresh re-fetch of the current tab is in flight. */
    val refreshing: Boolean = false,
    /** When on, every tab narrows to the user's favourites (sort still applies within). */
    val favoritesOnly: Boolean = false,
)

private data class AlbumQuery(
    val libraryId: String,
    val sort: AlbumSort,
    val descending: Boolean,
    val favoritesOnly: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val libraryRepository: LibraryRepository,
    private val settingsStore: SettingsStore,
    private val favoriteSyncManager: FavoriteDownloadSyncManager,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowseUiState())
    val state = _state.asStateFlow()

    private val albumQuery = MutableStateFlow(
        AlbumQuery(MusicLibrary.ALL_ID, AlbumSort.DEFAULT, false),
    )

    /** Paged albums for the current library + sort; the grid collects this. */
    val albumsPaging: Flow<PagingData<Album>> = albumQuery
        .flatMapLatest { q -> musicRepository.albumsPager(q.libraryId, q.sort, q.descending, q.favoritesOnly) }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            // Default to "All music" unless the user has previously picked a library.
            val selected = settingsStore.selectedLibrary.first() ?: MusicLibrary.all()
            val sort = settingsStore.albumSort.first()
            val descending = settingsStore.albumSortDescending.first()
            _state.update {
                it.copy(selectedLibrary = selected, albumSort = sort, albumSortDescending = descending)
            }
            albumQuery.value = AlbumQuery(selected.id, sort, descending)
            loadLibraries()
        }
        // App start: reconcile downloaded favourites with the server (no-ops when the setting is off).
        favoriteSyncManager.requestSync()
    }

    /** Loads the picker options: "All music" plus every music library on the server. */
    private fun loadLibraries() {
        viewModelScope.launch {
            val libraries = libraryRepository.getMusicLibraries().getOrDefault(emptyList())
            _state.update { it.copy(libraries = listOf(MusicLibrary.all()) + libraries) }
        }
    }

    fun selectLibrary(library: MusicLibrary) {
        if (library.id == _state.value.selectedLibrary.id) return
        viewModelScope.launch {
            settingsStore.setSelectedLibrary(library)
            _state.update {
                it.copy(
                    selectedLibrary = library,
                    artists = ContentState.Loading,
                    playlists = ContentState.Loading,
                )
            }
            albumQuery.update { it.copy(libraryId = library.id) }
        }
        // Library switch: re-trigger a favourites reconcile (favourites span all libraries).
        favoriteSyncManager.requestSync()
    }

    fun setAlbumSort(sort: AlbumSort) {
        if (sort == _state.value.albumSort) return
        viewModelScope.launch {
            settingsStore.setAlbumSort(sort)
            _state.update { it.copy(albumSort = sort) }
            albumQuery.update { it.copy(sort = sort) }
        }
    }

    fun toggleAlbumSortOrder() {
        val descending = !_state.value.albumSortDescending
        viewModelScope.launch {
            settingsStore.setAlbumSortDescending(descending)
            _state.update { it.copy(albumSortDescending = descending) }
            albumQuery.update { it.copy(descending = descending) }
        }
    }

    /**
     * Toggles the "Favourites only" filter across all tabs. Resets Artists/Playlists to Loading so
     * the [ensureArtists]/[ensurePlaylists] re-fetch (driven by the tab's LaunchedEffect) reruns with
     * the new filter, and re-points the album pager.
     */
    fun toggleFavoritesOnly() {
        val favoritesOnly = !_state.value.favoritesOnly
        _state.update {
            it.copy(
                favoritesOnly = favoritesOnly,
                artists = ContentState.Loading,
                playlists = ContentState.Loading,
            )
        }
        albumQuery.update { it.copy(favoritesOnly = favoritesOnly) }
    }

    /** Loads Artists only if not already loaded (called when the Artists tab is viewed). */
    fun ensureArtists() {
        if (_state.value.artists !is ContentState.Loading) return
        val libraryId = _state.value.selectedLibrary.id
        val favoritesOnly = _state.value.favoritesOnly
        viewModelScope.launch {
            _state.update {
                it.copy(artists = musicRepository.getArtists(libraryId, favoritesOnly).toContentState())
            }
        }
    }

    /** Loads Playlists only if not already loaded (called when the Playlists tab is viewed). */
    fun ensurePlaylists() {
        if (_state.value.playlists !is ContentState.Loading) return
        val libraryId = _state.value.selectedLibrary.id
        val favoritesOnly = _state.value.favoritesOnly
        viewModelScope.launch {
            _state.update {
                it.copy(playlists = musicRepository.getPlaylists(libraryId, favoritesOnly).toContentState())
            }
        }
    }

    /**
     * Force a re-fetch of Artists from the server (pull-to-refresh). Unlike [ensureArtists] this
     * runs even when data is already loaded, and keeps the current list visible while refreshing —
     * the repository re-queries the server and only falls back to the cache if the server is
     * unreachable, so a refresh never blanks the screen on a transient failure.
     */
    fun refreshArtists() {
        if (_state.value.refreshing) return
        val libraryId = _state.value.selectedLibrary.id
        val favoritesOnly = _state.value.favoritesOnly
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val artists = musicRepository.getArtists(libraryId, favoritesOnly).toContentState()
            _state.update { it.copy(artists = artists, refreshing = false) }
        }
    }

    /** Force a re-fetch of Playlists from the server (pull-to-refresh). See [refreshArtists]. */
    fun refreshPlaylists() {
        if (_state.value.refreshing) return
        val libraryId = _state.value.selectedLibrary.id
        val favoritesOnly = _state.value.favoritesOnly
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val playlists = musicRepository.getPlaylists(libraryId, favoritesOnly).toContentState()
            _state.update { it.copy(playlists = playlists, refreshing = false) }
        }
    }
}
