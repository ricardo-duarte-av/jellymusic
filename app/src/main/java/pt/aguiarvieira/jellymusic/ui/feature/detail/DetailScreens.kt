package pt.aguiarvieira.jellymusic.ui.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.ui.common.ContentState
import pt.aguiarvieira.jellymusic.ui.components.AlbumCard
import pt.aguiarvieira.jellymusic.ui.components.ArtworkImage
import pt.aguiarvieira.jellymusic.ui.components.TrackRow
import pt.aguiarvieira.jellymusic.ui.feature.player.MiniPlayer
import pt.aguiarvieira.jellymusic.ui.feature.player.PlaybackViewModel

@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit,
    onExpandPlayer: () -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
    playbackViewModel: PlaybackViewModel = hiltViewModel(),
) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    TrackListDetail(
        title = viewModel.title,
        tracksState = tracks,
        onBack = onBack,
        onExpandPlayer = onExpandPlayer,
        onPlay = playbackViewModel::play,
    )
}

@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onExpandPlayer: () -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
    playbackViewModel: PlaybackViewModel = hiltViewModel(),
) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    TrackListDetail(
        title = viewModel.title,
        tracksState = tracks,
        onBack = onBack,
        onExpandPlayer = onExpandPlayer,
        onPlay = playbackViewModel::play,
        showTrackArtwork = true,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackListDetail(
    title: String,
    tracksState: ContentState<List<Track>>,
    onBack: () -> Unit,
    onExpandPlayer: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    showTrackArtwork: Boolean = false,
) {
    Scaffold(
        topBar = { DetailTopBar(title = title, onBack = onBack) },
        bottomBar = { MiniPlayer(onExpand = onExpandPlayer) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tracksState) {
                ContentState.Loading -> Centered { CircularProgressIndicator() }
                is ContentState.Error -> Centered {
                    Text(tracksState.message, color = MaterialTheme.colorScheme.error)
                }

                is ContentState.Data -> {
                    val tracks = tracksState.value
                    val heroArt = tracks.firstOrNull()?.artworkUrl
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                ArtworkImage(
                                    url = heroArt,
                                    contentDescription = title,
                                    modifier = Modifier.size(200.dp),
                                    shape = MaterialTheme.shapes.extraLarge,
                                )
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.headlineMediumEmphasized,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 16.dp),
                                )
                                Text(
                                    text = "${tracks.size} tracks",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                            TrackRow(
                                track = track,
                                onClick = { onPlay(tracks, index) },
                                showArtwork = showTrackArtwork,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistDetailScreen(
    onBack: () -> Unit,
    onAlbumClick: (String, String) -> Unit,
    onExpandPlayer: () -> Unit,
    viewModel: ArtistDetailViewModel = hiltViewModel(),
) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    ArtistDetailContent(
        title = viewModel.title,
        albumsState = albums,
        onBack = onBack,
        onAlbumClick = onAlbumClick,
        onExpandPlayer = onExpandPlayer,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistDetailContent(
    title: String,
    albumsState: ContentState<List<Album>>,
    onBack: () -> Unit,
    onAlbumClick: (String, String) -> Unit,
    onExpandPlayer: () -> Unit,
) {
    Scaffold(
        topBar = { DetailTopBar(title = title, onBack = onBack) },
        bottomBar = { MiniPlayer(onExpand = onExpandPlayer) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (albumsState) {
                ContentState.Loading -> Centered { CircularProgressIndicator() }
                is ContentState.Error -> Centered {
                    Text(albumsState.message, color = MaterialTheme.colorScheme.error)
                }

                is ContentState.Data -> if (albumsState.value.isEmpty()) {
                    Centered {
                        Text("No albums", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    ) {
                        items(albumsState.value, key = { it.id }) { album ->
                            AlbumCard(album, onClick = { onAlbumClick(album.id, album.name) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
