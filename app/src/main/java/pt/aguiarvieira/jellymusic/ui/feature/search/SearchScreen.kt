package pt.aguiarvieira.jellymusic.ui.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.aguiarvieira.jellymusic.domain.model.SearchResults
import pt.aguiarvieira.jellymusic.ui.components.ArtworkImage
import pt.aguiarvieira.jellymusic.ui.components.TrackRow
import pt.aguiarvieira.jellymusic.ui.feature.player.MiniPlayer
import pt.aguiarvieira.jellymusic.ui.feature.player.PlaybackViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onAlbumClick: (String, String) -> Unit,
    onArtistClick: (String, String) -> Unit,
    onPlaylistClick: (String, String) -> Unit,
    onExpandPlayer: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
    playbackViewModel: PlaybackViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text("Search music") },
                        singleLine = true,
                        trailingIcon = if (query.isNotEmpty()) {
                            {
                                IconButton(onClick = { viewModel.onQueryChange("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                }
                            }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Search,
                            autoCorrectEnabled = false,
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = { MiniPlayer(onExpand = onExpandPlayer) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                SearchUiState.Idle -> Centered {
                    Text(
                        "Search songs, albums, artists and playlists",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SearchUiState.Loading -> Centered { CircularProgressIndicator() }

                is SearchUiState.Error -> Centered {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                }

                is SearchUiState.Results -> if (s.results.isEmpty) {
                    Centered {
                        Text("No results", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    ResultsList(
                        results = s.results,
                        onPlayTrack = { track ->
                            keyboard?.hide()
                            playbackViewModel.play(listOf(track), 0)
                        },
                        onAlbumClick = onAlbumClick,
                        onArtistClick = onArtistClick,
                        onPlaylistClick = onPlaylistClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultsList(
    results: SearchResults,
    onPlayTrack: (pt.aguiarvieira.jellymusic.domain.model.Track) -> Unit,
    onAlbumClick: (String, String) -> Unit,
    onArtistClick: (String, String) -> Unit,
    onPlaylistClick: (String, String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (results.tracks.isNotEmpty()) {
            item { SectionHeader("Songs") }
            items(results.tracks, key = { "t-${it.id}" }) { track ->
                TrackRow(track = track, onClick = { onPlayTrack(track) }, showArtwork = true)
            }
        }
        if (results.albums.isNotEmpty()) {
            item { SectionHeader("Albums") }
            items(results.albums, key = { "al-${it.id}" }) { album ->
                ResultRow(
                    title = album.name,
                    subtitle = album.artist,
                    artworkUrl = album.artworkUrl,
                    onClick = { onAlbumClick(album.id, album.name) },
                )
            }
        }
        if (results.artists.isNotEmpty()) {
            item { SectionHeader("Artists") }
            items(results.artists, key = { "ar-${it.id}" }) { artist ->
                ResultRow(
                    title = artist.name,
                    subtitle = null,
                    artworkUrl = artist.artworkUrl,
                    fallback = Icons.Filled.Person,
                    onClick = { onArtistClick(artist.id, artist.name) },
                )
            }
        }
        if (results.playlists.isNotEmpty()) {
            item { SectionHeader("Playlists") }
            items(results.playlists, key = { "pl-${it.id}" }) { playlist ->
                ResultRow(
                    title = playlist.name,
                    subtitle = playlist.trackCount?.let { "$it tracks" },
                    artworkUrl = playlist.artworkUrl,
                    fallback = Icons.AutoMirrored.Filled.QueueMusic,
                    onClick = { onPlaylistClick(playlist.id, playlist.name) },
                )
            }
        }
    }
}

@Composable
private fun ResultRow(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    onClick: () -> Unit,
    fallback: ImageVector = Icons.Filled.MusicNote,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = {
            ArtworkImage(
                url = artworkUrl,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                fallbackIcon = fallback,
            )
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
