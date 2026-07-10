package pt.aguiarvieira.jellymusic.ui.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.Album
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
    val albums: ContentState<List<Album>> = ContentState.Loading,
    val artists: ContentState<List<Artist>> = ContentState.Loading,
    val playlists: ContentState<List<Playlist>> = ContentState.Loading,
)

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val libraryRepository: LibraryRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowseUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Default to "All music" unless the user has previously picked a library.
            val selected = settingsStore.selectedLibrary.first() ?: MusicLibrary.all()
            _state.update { it.copy(selectedLibrary = selected) }
            loadLibraries()
            loadContent(selected.id)
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
            _state.update { it.copy(selectedLibrary = library) }
            loadContent(library.id)
        }
    }

    private fun loadContent(libraryId: String) {
        _state.update {
            it.copy(
                albums = ContentState.Loading,
                artists = ContentState.Loading,
                playlists = ContentState.Loading,
            )
        }
        viewModelScope.launch {
            _state.update { it.copy(albums = musicRepository.getAlbums(libraryId).toContentState()) }
        }
        viewModelScope.launch {
            _state.update { it.copy(artists = musicRepository.getArtists(libraryId).toContentState()) }
        }
        viewModelScope.launch {
            _state.update { it.copy(playlists = musicRepository.getPlaylists(libraryId).toContentState()) }
        }
    }
}
