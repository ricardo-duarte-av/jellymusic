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
import pt.aguiarvieira.jellymusic.domain.repository.MusicRepository
import pt.aguiarvieira.jellymusic.ui.common.ContentState
import pt.aguiarvieira.jellymusic.ui.common.toContentState
import javax.inject.Inject

data class BrowseUiState(
    val libraryName: String = "",
    val albums: ContentState<List<Album>> = ContentState.Loading,
    val artists: ContentState<List<Artist>> = ContentState.Loading,
    val playlists: ContentState<List<Playlist>> = ContentState.Loading,
)

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowseUiState())
    val state = _state.asStateFlow()

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            val library = settingsStore.selectedLibrary.first() ?: MusicLibrary.all()
            val libraryId = library.id
            _state.update {
                it.copy(
                    libraryName = library.name,
                    albums = ContentState.Loading,
                    artists = ContentState.Loading,
                    playlists = ContentState.Loading,
                )
            }
            launch {
                _state.update { it.copy(albums = musicRepository.getAlbums(libraryId).toContentState()) }
            }
            launch {
                _state.update { it.copy(artists = musicRepository.getArtists(libraryId).toContentState()) }
            }
            launch {
                _state.update { it.copy(playlists = musicRepository.getPlaylists(libraryId).toContentState()) }
            }
        }
    }
}
