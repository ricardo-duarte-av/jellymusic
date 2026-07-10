package pt.aguiarvieira.jellymusic.ui.feature.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.aguiarvieira.jellymusic.ui.common.ContentState
import pt.aguiarvieira.jellymusic.ui.components.AlbumCard
import pt.aguiarvieira.jellymusic.ui.components.ArtistCard
import pt.aguiarvieira.jellymusic.ui.components.PlaylistCard
import pt.aguiarvieira.jellymusic.ui.feature.player.MiniPlayer

private enum class BrowseTab(val label: String, val icon: ImageVector) {
    ALBUMS("Albums", Icons.Filled.Album),
    ARTISTS("Artists", Icons.Filled.Person),
    PLAYLISTS("Playlists", Icons.AutoMirrored.Filled.QueueMusic),
}

@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BrowseShell(
    onAlbumClick: (String, String) -> Unit,
    onArtistClick: (String, String) -> Unit,
    onPlaylistClick: (String, String) -> Unit,
    onExpandPlayer: () -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(BrowseTab.ALBUMS) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            BrowseTab.entries.forEach { tab ->
                item(
                    selected = tab == selectedTab,
                    onClick = { selectedTab = tab },
                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                    label = { Text(tab.label) },
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = {
                    Column {
                        Text(
                            text = selectedTab.label,
                            style = MaterialTheme.typography.titleLargeEmphasized,
                        )
                        if (state.libraryName.isNotEmpty()) {
                            Text(
                                text = state.libraryName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    BrowseTab.ALBUMS -> ContentSection(state.albums, "No albums found") { albums ->
                        Grid {
                            items(albums, key = { it.id }) { album ->
                                AlbumCard(album, onClick = { onAlbumClick(album.id, album.name) })
                            }
                        }
                    }

                    BrowseTab.ARTISTS -> ContentSection(state.artists, "No artists found") { artists ->
                        Grid {
                            items(artists, key = { it.id }) { artist ->
                                ArtistCard(artist, onClick = { onArtistClick(artist.id, artist.name) })
                            }
                        }
                    }

                    BrowseTab.PLAYLISTS -> ContentSection(state.playlists, "No playlists found") { playlists ->
                        Grid {
                            items(playlists, key = { it.id }) { playlist ->
                                PlaylistCard(playlist, onClick = { onPlaylistClick(playlist.id, playlist.name) })
                            }
                        }
                    }
                }
            }

            MiniPlayer(onExpand = onExpandPlayer)
        }
    }
}

@Composable
private fun Grid(content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        content = content,
    )
}

@Composable
private fun <T> ContentSection(
    state: ContentState<List<T>>,
    emptyMessage: String,
    content: @Composable (List<T>) -> Unit,
) {
    when (state) {
        ContentState.Loading -> Centered { CircularProgressIndicator() }
        is ContentState.Error -> Centered {
            Text(state.message, color = MaterialTheme.colorScheme.error)
        }

        is ContentState.Data -> if (state.value.isEmpty()) {
            Centered { Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            content(state.value)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
