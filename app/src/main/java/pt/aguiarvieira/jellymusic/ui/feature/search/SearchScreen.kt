package pt.aguiarvieira.jellymusic.ui.feature.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.AlbumDownloadStatus
import pt.aguiarvieira.jellymusic.domain.model.Playlist
import pt.aguiarvieira.jellymusic.domain.model.SearchResults
import pt.aguiarvieira.jellymusic.domain.model.TrackDownloadStatus
import pt.aguiarvieira.jellymusic.ui.components.ArtworkImage
import pt.aguiarvieira.jellymusic.ui.components.SearchResultsSkeleton
import pt.aguiarvieira.jellymusic.ui.components.TrackRow
import pt.aguiarvieira.jellymusic.ui.feature.downloads.AlbumArtDownloadBadge
import pt.aguiarvieira.jellymusic.ui.feature.downloads.AlbumDownloadBar
import pt.aguiarvieira.jellymusic.ui.feature.downloads.DownloadDialogs
import pt.aguiarvieira.jellymusic.ui.feature.downloads.DownloadTarget
import pt.aguiarvieira.jellymusic.ui.feature.downloads.DownloadsViewModel
import pt.aguiarvieira.jellymusic.ui.feature.player.MiniPlayer
import pt.aguiarvieira.jellymusic.ui.feature.player.PlaybackViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onAlbumClick: (String, String, String?) -> Unit,
    onArtistClick: (String, String) -> Unit,
    onPlaylistClick: (String, String) -> Unit,
    onExpandPlayer: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
    playbackViewModel: PlaybackViewModel = hiltViewModel(),
    downloadsViewModel: DownloadsViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val trackStatuses by downloadsViewModel.trackStatuses.collectAsStateWithLifecycle()
    val albumStatuses by downloadsViewModel.albumStatuses.collectAsStateWithLifecycle()
    val playlistStatuses by downloadsViewModel.playlistStatuses.collectAsStateWithLifecycle()
    val transcodeDefault by downloadsViewModel.transcodeDefault.collectAsStateWithLifecycle()
    var pendingDownload by remember { mutableStateOf<DownloadTarget?>(null) }
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

                SearchUiState.Loading -> SearchResultsSkeleton()

                is SearchUiState.Error -> Centered {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                }

                is SearchUiState.Results -> if (s.results.isEmpty) {
                    Centered {
                        Text("No results", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    ResultsContent(
                        results = s.results,
                        onPlayTrack = { track ->
                            keyboard?.hide()
                            playbackViewModel.play(listOf(track), 0)
                        },
                        onAlbumClick = onAlbumClick,
                        onArtistClick = onArtistClick,
                        onPlaylistClick = onPlaylistClick,
                        trackStatuses = trackStatuses,
                        albumStatuses = albumStatuses,
                        playlistStatuses = playlistStatuses,
                        onDownloadTrack = { pendingDownload = DownloadTarget.TrackItem(it) },
                        onRemoveTrack = downloadsViewModel::removeTrack,
                        onDownloadAlbum = { album ->
                            pendingDownload =
                                DownloadTarget.Album(album.id, album.name, album.artist, album.artworkUrl)
                        },
                        onRemoveAlbum = downloadsViewModel::removeAlbum,
                        onDownloadPlaylist = { playlist ->
                            pendingDownload =
                                DownloadTarget.Playlist(playlist.id, playlist.name, playlist.artworkUrl)
                        },
                        onRemovePlaylist = downloadsViewModel::removePlaylist,
                    )
                }
            }
        }
    }

    DownloadDialogs(
        target = pendingDownload,
        transcodeDefault = transcodeDefault,
        isMetered = downloadsViewModel::isMetered,
        onConfirm = { target, transcode ->
            when (target) {
                is DownloadTarget.Album ->
                    downloadsViewModel.downloadAlbum(
                        target.id, target.name, target.artist, target.artworkUrl, transcode,
                    )
                is DownloadTarget.TrackItem ->
                    downloadsViewModel.downloadTrack(target.track, transcode)
                is DownloadTarget.Playlist ->
                    downloadsViewModel.downloadPlaylist(target.id, target.name, target.artworkUrl, transcode)
            }
            pendingDownload = null
        },
        onDismiss = { pendingDownload = null },
    )
}

/** The four result kinds, in the display order requested by the user. */
private enum class SearchKind(val label: String) {
    Playlists("Playlists"),
    Artists("Artists"),
    Albums("Albums"),
    Songs("Songs"),
}

@Composable
private fun ResultsContent(
    results: SearchResults,
    onPlayTrack: (pt.aguiarvieira.jellymusic.domain.model.Track) -> Unit,
    onAlbumClick: (String, String, String?) -> Unit,
    onArtistClick: (String, String) -> Unit,
    onPlaylistClick: (String, String) -> Unit,
    trackStatuses: Map<String, TrackDownloadStatus>,
    albumStatuses: Map<String, AlbumDownloadStatus>,
    playlistStatuses: Map<String, AlbumDownloadStatus>,
    onDownloadTrack: (pt.aguiarvieira.jellymusic.domain.model.Track) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onDownloadAlbum: (Album) -> Unit,
    onRemoveAlbum: (String) -> Unit,
    onDownloadPlaylist: (Playlist) -> Unit,
    onRemovePlaylist: (String) -> Unit,
) {
    // Which kinds are present in the current results (drives chip enabled state).
    val present = remember(results) {
        buildSet {
            if (results.playlists.isNotEmpty()) add(SearchKind.Playlists)
            if (results.artists.isNotEmpty()) add(SearchKind.Artists)
            if (results.albums.isNotEmpty()) add(SearchKind.Albums)
            if (results.tracks.isNotEmpty()) add(SearchKind.Songs)
        }
    }

    // Selected filter chips. Empty = show everything present.
    var selected by rememberSaveable { mutableStateOf(emptySet<SearchKind>()) }

    // A kind is visible when nothing is selected, or it's explicitly selected.
    fun visible(kind: SearchKind) = kind in present && (selected.isEmpty() || kind in selected)

    Column(Modifier.fillMaxSize()) {
        FilterChipsRow(
            present = present,
            selected = selected,
            onToggle = { kind ->
                selected = if (kind in selected) selected - kind else selected + kind
            },
        )
        LazyColumn(Modifier.fillMaxSize()) {
            if (visible(SearchKind.Playlists)) {
                item { SectionHeader("Playlists") }
                items(results.playlists, key = { "pl-${it.id}" }) { playlist ->
                    ResultRow(
                        title = playlist.name,
                        subtitle = playlist.trackCount?.let { "$it tracks" },
                        artworkUrl = playlist.artworkUrl,
                        fallback = Icons.AutoMirrored.Filled.QueueMusic,
                        onClick = { onPlaylistClick(playlist.id, playlist.name) },
                        downloadStatus = playlistStatuses[playlist.id],
                        onDownload = { onDownloadPlaylist(playlist) },
                        onRemoveLocal = { onRemovePlaylist(playlist.id) },
                    )
                }
            }
            if (visible(SearchKind.Artists)) {
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
            if (visible(SearchKind.Albums)) {
                item { SectionHeader("Albums") }
                items(results.albums, key = { "al-${it.id}" }) { album ->
                    ResultRow(
                        // Stable handle for the screenshot instrumentation test to open a
                        // specific album deterministically (see ScreenshotTest).
                        modifier = Modifier.testTag("searchAlbumRow"),
                        title = album.name,
                        subtitle = album.artist,
                        artworkUrl = album.artworkUrl,
                        onClick = { onAlbumClick(album.id, album.name, album.artworkUrl) },
                        downloadStatus = albumStatuses[album.id],
                        onDownload = { onDownloadAlbum(album) },
                        onRemoveLocal = { onRemoveAlbum(album.id) },
                    )
                }
            }
            if (visible(SearchKind.Songs)) {
                item { SectionHeader("Songs") }
                items(results.tracks, key = { "t-${it.id}" }) { track ->
                    TrackRow(
                        track = track,
                        onClick = { onPlayTrack(track) },
                        showArtwork = true,
                        downloadStatus = trackStatuses[track.id],
                        onDownload = { onDownloadTrack(track) },
                        onRemoveLocal = { onRemoveTrack(track.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChipsRow(
    present: Set<SearchKind>,
    selected: Set<SearchKind>,
    onToggle: (SearchKind) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchKind.entries.forEach { kind ->
            FilterChip(
                selected = kind in selected,
                onClick = { onToggle(kind) },
                enabled = kind in present,
                label = { Text(kind.label) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fallback: ImageVector = Icons.Filled.MusicNote,
    downloadStatus: AlbumDownloadStatus? = null,
    onDownload: (() -> Unit)? = null,
    onRemoveLocal: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onDownload != null || onRemoveLocal != null
    val canRemove = downloadStatus?.isComplete == true || downloadStatus?.inProgress == true

    Box(modifier) {
        Column {
            ListItem(
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = onClick,
                    onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                ),
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
                trailingContent = {
                    AlbumArtDownloadBadge(status = downloadStatus)
                },
            ) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            AlbumDownloadBar(
                status = downloadStatus,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            onDownload?.let { download ->
                DropdownMenuItem(
                    text = { Text("Download") },
                    leadingIcon = { Icon(Icons.Filled.Save, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        download()
                    },
                )
            }
            if (canRemove) {
                onRemoveLocal?.let { remove ->
                    DropdownMenuItem(
                        text = { Text("Remove local") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            remove()
                        },
                    )
                }
            }
        }
    }
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
