package pt.aguiarvieira.jellymusic.ui.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val args = savedStateHandle.toRoute<Routes.AlbumDetail>()
    val title: String = args.albumName

    private val _tracks = MutableStateFlow<ContentState<List<Track>>>(ContentState.Loading)
    val tracks = _tracks.asStateFlow()

    init {
        viewModelScope.launch {
            _tracks.value = musicRepository.getAlbumTracks(args.albumId).toContentState()
        }
    }
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val args = savedStateHandle.toRoute<Routes.PlaylistDetail>()
    val title: String = args.playlistName

    private val _tracks = MutableStateFlow<ContentState<List<Track>>>(ContentState.Loading)
    val tracks = _tracks.asStateFlow()

    init {
        viewModelScope.launch {
            _tracks.value = musicRepository.getPlaylistTracks(args.playlistId).toContentState()
        }
    }
}

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val args = savedStateHandle.toRoute<Routes.ArtistDetail>()
    val title: String = args.artistName

    private val _albums = MutableStateFlow<ContentState<List<Album>>>(ContentState.Loading)
    val albums = _albums.asStateFlow()

    init {
        viewModelScope.launch {
            _albums.value = musicRepository.getArtistAlbums(args.artistId).toContentState()
        }
    }
}
