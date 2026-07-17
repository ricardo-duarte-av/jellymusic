package pt.aguiarvieira.jellymusic.ui.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.AlbumDownloadStatus
import pt.aguiarvieira.jellymusic.domain.model.AlbumSort
import pt.aguiarvieira.jellymusic.domain.model.MusicLibrary
import pt.aguiarvieira.jellymusic.ui.common.ContentState
import pt.aguiarvieira.jellymusic.ui.components.AlbumCard
import pt.aguiarvieira.jellymusic.ui.components.ArtistCard
import pt.aguiarvieira.jellymusic.ui.components.PlaylistCard
import pt.aguiarvieira.jellymusic.ui.feature.downloads.DownloadDialogs
import pt.aguiarvieira.jellymusic.ui.feature.downloads.DownloadTarget
import pt.aguiarvieira.jellymusic.ui.feature.downloads.DownloadsViewModel
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
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
    downloadsViewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val albumItems = viewModel.albumsPaging.collectAsLazyPagingItems()
    val albumStatuses by downloadsViewModel.albumStatuses.collectAsStateWithLifecycle()
    val transcodeDefault by downloadsViewModel.transcodeDefault.collectAsStateWithLifecycle()
    var pendingDownload by remember { mutableStateOf<DownloadTarget?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(BrowseTab.ALBUMS) }
    // Grid column count, adjustable by pinch (shared across tabs).
    var columns by rememberSaveable { mutableIntStateOf(2) }
    val onZoom: (Int) -> Unit = { delta -> columns = (columns + delta).coerceIn(1, 4) }

    // Lazily load Artists/Playlists the first time their tab is shown (Albums loads eagerly).
    // Re-keyed on the library so a library switch reloads the currently-visible tab.
    LaunchedEffect(selectedTab, state.selectedLibrary.id, state.favoritesOnly) {
        when (selectedTab) {
            BrowseTab.ARTISTS -> viewModel.ensureArtists()
            BrowseTab.PLAYLISTS -> viewModel.ensurePlaylists()
            BrowseTab.ALBUMS -> Unit
        }
    }

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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LibrarySelector(
                                selected = state.selectedLibrary,
                                libraries = state.libraries,
                                onSelect = viewModel::selectLibrary,
                            )
                            // Sort/order sit right next to the library dropdown (Albums tab only).
                            if (selectedTab == BrowseTab.ALBUMS) {
                                AlbumSortControls(
                                    sort = state.albumSort,
                                    descending = state.albumSortDescending,
                                    onSortSelected = viewModel::setAlbumSort,
                                    onToggleOrder = viewModel::toggleAlbumSortOrder,
                                )
                            }
                            // Favourites filter — narrows the current tab to favourites (all tabs).
                            FavoritesFilterChip(
                                selected = state.favoritesOnly,
                                onClick = viewModel::toggleFavoritesOnly,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    BrowseTab.ALBUMS -> AlbumsPagingContent(
                        albums = albumItems,
                        columns = columns,
                        onZoom = onZoom,
                        onAlbumClick = onAlbumClick,
                        albumStatuses = albumStatuses,
                        onRequestDownload = { album ->
                            pendingDownload = DownloadTarget.Album(album.id, album.name, album.artist, album.artworkUrl)
                        },
                        onRemoveAlbum = downloadsViewModel::removeAlbum,
                    )

                    BrowseTab.ARTISTS -> ContentSection(state.artists, "No artists found") { artists ->
                        PullToRefreshBox(
                            isRefreshing = state.refreshing,
                            onRefresh = viewModel::refreshArtists,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Grid(columns = columns, onZoom = onZoom) {
                                items(artists, key = { it.id }) { artist ->
                                    ArtistCard(artist, onClick = { onArtistClick(artist.id, artist.name) })
                                }
                            }
                        }
                    }

                    BrowseTab.PLAYLISTS -> ContentSection(state.playlists, "No playlists found") { playlists ->
                        PullToRefreshBox(
                            isRefreshing = state.refreshing,
                            onRefresh = viewModel::refreshPlaylists,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Grid(columns = columns, onZoom = onZoom) {
                                items(playlists, key = { it.id }) { playlist ->
                                    PlaylistCard(playlist, onClick = { onPlaylistClick(playlist.id, playlist.name) })
                                }
                            }
                        }
                    }
                }
            }

            MiniPlayer(onExpand = onExpandPlayer)
        }
    }

    DownloadDialogs(
        target = pendingDownload,
        transcodeDefault = transcodeDefault,
        isMetered = downloadsViewModel::isMetered,
        onConfirm = { target, transcode ->
            if (target is DownloadTarget.Album) {
                downloadsViewModel.downloadAlbum(target.id, target.name, target.artist, target.artworkUrl, transcode)
            }
            pendingDownload = null
        },
        onDismiss = { pendingDownload = null },
    )
}

/** Heart filter chip that narrows the active tab to the user's favourites. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesFilterChip(selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text("Favourites") },
        leadingIcon = {
            Icon(
                imageVector = if (selected) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
    )
}

/** Compact sort field + order controls, sized to sit inline next to the library dropdown. */
@Composable
private fun AlbumSortControls(
    sort: AlbumSort,
    descending: Boolean,
    onSortSelected: (AlbumSort) -> Unit,
    onToggleOrder: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Sort,
            contentDescription = "Sort by ${sort.label}",
            tint = tint,
            modifier = Modifier
                .clickable { expanded = true }
                .padding(4.dp)
                .size(24.dp),
        )
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
    Icon(
        imageVector = if (descending) Icons.Filled.VerticalAlignTop else Icons.Filled.VerticalAlignBottom,
        contentDescription = if (descending) "Sort descending" else "Sort ascending",
        tint = tint,
        modifier = Modifier
            .clickable { onToggleOrder() }
            .padding(4.dp)
            .size(24.dp),
    )
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

/** Paged album grid with pull-to-refresh and load-state handling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumsPagingContent(
    albums: LazyPagingItems<Album>,
    columns: Int,
    onZoom: (Int) -> Unit,
    onAlbumClick: (String, String) -> Unit,
    albumStatuses: Map<String, AlbumDownloadStatus>,
    onRequestDownload: (Album) -> Unit,
    onRemoveAlbum: (String) -> Unit,
) {
    val refresh = albums.loadState.refresh
    when {
        // Have cached/loaded data: always show the grid (a background refresh shows as the pull
        // indicator rather than replacing the grid with a spinner).
        albums.itemCount > 0 -> PullToRefreshBox(
            isRefreshing = refresh is LoadState.Loading,
            onRefresh = { albums.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            Grid(columns = columns, onZoom = onZoom) {
                items(
                    count = albums.itemCount,
                    key = albums.itemKey { it.id },
                    contentType = albums.itemContentType { "album" },
                ) { index ->
                    albums[index]?.let { album ->
                        AlbumCard(
                            album = album,
                            onClick = { onAlbumClick(album.id, album.name) },
                            downloadStatus = albumStatuses[album.id],
                            onDownload = { onRequestDownload(album) },
                            onRemoveLocal = { onRemoveAlbum(album.id) },
                        )
                    }
                }
                if (albums.loadState.append is LoadState.Loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }

        refresh is LoadState.Loading -> Centered { CircularProgressIndicator() }
        refresh is LoadState.Error -> Centered {
            Text(
                text = refresh.error.message ?: "Couldn't load albums",
                color = MaterialTheme.colorScheme.error,
            )
        }

        else -> Centered {
            Text("No albums found", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
