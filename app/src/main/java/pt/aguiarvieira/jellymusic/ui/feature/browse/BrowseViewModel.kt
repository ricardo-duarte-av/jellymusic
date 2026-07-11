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
)

private data class AlbumQuery(
    val libraryId: String,
    val sort: AlbumSort,
    val descending: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val libraryRepository: LibraryRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowseUiState())
    val state = _state.asStateFlow()

    private val albumQuery = MutableStateFlow(
        AlbumQuery(MusicLibrary.ALL_ID, AlbumSort.DEFAULT, false),
    )

    /** Paged albums for the current library + sort; the grid collects this. */
    val albumsPaging: Flow<PagingData<Album>> = albumQuery
        .flatMapLatest { q -> musicRepository.albumsPager(q.libraryId, q.sort, q.descending) }
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

    /** Loads Artists only if not already loaded (called when the Artists tab is viewed). */
    fun ensureArtists() {
        if (_state.value.artists !is ContentState.Loading) return
        val libraryId = _state.value.selectedLibrary.id
        viewModelScope.launch {
            _state.update { it.copy(artists = musicRepository.getArtists(libraryId).toContentState()) }
        }
    }

    /** Loads Playlists only if not already loaded (called when the Playlists tab is viewed). */
    fun ensurePlaylists() {
        if (_state.value.playlists !is ContentState.Loading) return
        viewModelScope.launch {
            _state.update {
                it.copy(playlists = musicRepository.getPlaylists(_state.value.selectedLibrary.id).toContentState())
            }
        }
    }
}
