package pt.aguiarvieira.jellymusic.ui.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.aguiarvieira.jellymusic.domain.model.AlbumSort
import pt.aguiarvieira.jellymusic.domain.model.MusicLibrary
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
    // Grid column count, adjustable by pinch (shared across tabs).
    var columns by rememberSaveable { mutableIntStateOf(2) }
    val onZoom: (Int) -> Unit = { delta -> columns = (columns + delta).coerceIn(1, 4) }

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
                        LibrarySelector(
                            selected = state.selectedLibrary,
                            libraries = state.libraries,
                            onSelect = viewModel::selectLibrary,
                        )
                    }
                },
                actions = {
                    // Sort controls live on the top-bar row, alongside the library dropdown.
                    if (selectedTab == BrowseTab.ALBUMS) {
                        AlbumSortActions(
                            sort = state.albumSort,
                            descending = state.albumSortDescending,
                            onSortSelected = viewModel::setAlbumSort,
                            onToggleOrder = viewModel::toggleAlbumSortOrder,
                        )
                    }
                },
            )

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    BrowseTab.ALBUMS -> ContentSection(state.albums, "No albums found") { albums ->
                        Grid(columns = columns, onZoom = onZoom) {
                            items(albums, key = { it.id }) { album ->
                                AlbumCard(album, onClick = { onAlbumClick(album.id, album.name) })
                            }
                        }
                    }

                    BrowseTab.ARTISTS -> ContentSection(state.artists, "No artists found") { artists ->
                        Grid(columns = columns, onZoom = onZoom) {
                            items(artists, key = { it.id }) { artist ->
                                ArtistCard(artist, onClick = { onArtistClick(artist.id, artist.name) })
                            }
                        }
                    }

                    BrowseTab.PLAYLISTS -> ContentSection(state.playlists, "No playlists found") { playlists ->
                        Grid(columns = columns, onZoom = onZoom) {
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

/** Compact sort field dropdown + order toggle for the top-app-bar actions (Albums tab). */
@Composable
private fun AlbumSortActions(
    sort: AlbumSort,
    descending: Boolean,
    onSortSelected: (AlbumSort) -> Unit,
    onToggleOrder: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort by (${sort.label})")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AlbumSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSortSelected(option)
                        expanded = false
                    },
                    trailingIcon = if (option == sort) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
    IconButton(onClick = onToggleOrder) {
        Icon(
            imageVector = if (descending) Icons.Filled.VerticalAlignTop else Icons.Filled.VerticalAlignBottom,
            contentDescription = if (descending) "Sort descending" else "Sort ascending",
        )
    }
}

/** The library name in the top bar, tappable to switch between "All music" and a single library. */
@Composable
private fun LibrarySelector(
    selected: MusicLibrary,
    libraries: List<MusicLibrary>,
    onSelect: (MusicLibrary) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Choose library",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            libraries.forEach { library ->
                DropdownMenuItem(
                    text = { Text(library.name) },
                    onClick = {
                        onSelect(library)
                        expanded = false
                    },
                    trailingIcon = if (library.id == selected.id) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/**
 * Fixed-column grid whose column count is pinch-adjustable. Pinching out (fingers apart) drops a
 * column for bigger items; pinching in adds one. [onZoom] receives the step (-1 / +1) and the caller
 * clamps to 1..4.
 */
@Composable
private fun Grid(
    columns: Int,
    onZoom: (Int) -> Unit,
    content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Intercept two-finger pinches on the Initial pass and consume them, so the grid's own
            // vertical scroll (which fought the earlier detectTransformGestures) doesn't win.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var cumulative = 1f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                cumulative *= zoom
                                when {
                                    cumulative > PINCH_STEP -> {
                                        onZoom(-1)
                                        cumulative = 1f
                                    }

                                    cumulative < 1f / PINCH_STEP -> {
                                        onZoom(+1)
                                        cumulative = 1f
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            content = content,
        )
    }
}

/** Cumulative zoom factor within one pinch that triggers a one-column step. */
private const val PINCH_STEP = 1.3f

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
