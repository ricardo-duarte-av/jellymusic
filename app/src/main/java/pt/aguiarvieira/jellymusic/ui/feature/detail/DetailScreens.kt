package pt.aguiarvieira.jellymusic.ui.feature.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.domain.model.TrackDownloadStatus
import pt.aguiarvieira.jellymusic.ui.common.ContentState
import pt.aguiarvieira.jellymusic.ui.components.AlbumCard
import pt.aguiarvieira.jellymusic.ui.components.ArtworkImage
import pt.aguiarvieira.jellymusic.ui.components.TrackRow
import pt.aguiarvieira.jellymusic.ui.feature.downloads.DownloadDialogs
import pt.aguiarvieira.jellymusic.ui.feature.downloads.DownloadTarget
import pt.aguiarvieira.jellymusic.ui.feature.downloads.DownloadsViewModel
import pt.aguiarvieira.jellymusic.ui.feature.downloads.TrackDownloadBar
import pt.aguiarvieira.jellymusic.ui.feature.downloads.TrackDownloadIndicator
import pt.aguiarvieira.jellymusic.ui.feature.player.MiniPlayer
import pt.aguiarvieira.jellymusic.ui.feature.player.PlaybackViewModel

private val MAX_ART_HEIGHT = 340.dp
private const val MIN_ART_FRACTION = 0.25f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit,
    onExpandPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
    playbackViewModel: PlaybackViewModel = hiltViewModel(),
    downloadsViewModel: DownloadsViewModel = hiltViewModel(),
) {
    val tracksState by viewModel.tracks.collectAsStateWithLifecycle()
    val trackStatuses by downloadsViewModel.trackStatuses.collectAsStateWithLifecycle()
    val transcodeDefault by downloadsViewModel.transcodeDefault.collectAsStateWithLifecycle()
    var pendingDownload by remember { mutableStateOf<DownloadTarget?>(null) }

    DownloadDialogs(
        target = pendingDownload,
        transcodeDefault = transcodeDefault,
        isMetered = downloadsViewModel::isMetered,
        onConfirm = { target, transcode ->
            if (target is DownloadTarget.TrackItem) {
                downloadsViewModel.downloadTrack(target.track, transcode)
            }
            pendingDownload = null
        },
        onDismiss = { pendingDownload = null },
    )
    Scaffold(
        // (1) No album name in the header — keeps space for the Settings action.
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = { MiniPlayer(onExpand = onExpandPlayer) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val state = tracksState) {
                ContentState.Loading -> Centered { CircularProgressIndicator() }
                is ContentState.Error -> Centered {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }

                is ContentState.Data -> AlbumTrackList(
                    title = viewModel.title,
                    tracks = state.value,
                    onPlay = playbackViewModel::play,
                    trackStatuses = trackStatuses,
                    onRequestDownload = { pendingDownload = DownloadTarget.TrackItem(it) },
                    onRemoveTrack = downloadsViewModel::removeTrack,
                )
            }
        }
    }
}

/** Album track list with a collapsing hero image (shrinks to 25% on scroll, then scrolls away). */
@Composable
private fun AlbumTrackList(
    title: String,
    tracks: List<Track>,
    onPlay: (List<Track>, Int) -> Unit,
    trackStatuses: Map<String, TrackDownloadStatus>,
    onRequestDownload: (Track) -> Unit,
    onRemoveTrack: (String) -> Unit,
) {
    val density = LocalDensity.current
    val maxArtPx = with(density) { MAX_ART_HEIGHT.toPx() }
    val minArtPx = maxArtPx * MIN_ART_FRACTION
    var artHeightPx by remember { mutableFloatStateOf(maxArtPx) }

    // (4) Scroll shrinks the art (consuming the scroll) until it hits the min; only then does the
    // list scroll and carry the shrunk art off the top.
    val collapseConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta < 0f) {
                    val newHeight = (artHeightPx + delta).coerceIn(minArtPx, maxArtPx)
                    val consumed = newHeight - artHeightPx
                    artHeightPx = newHeight
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val delta = available.y
                if (delta > 0f) {
                    val newHeight = (artHeightPx + delta).coerceIn(minArtPx, maxArtPx)
                    val used = newHeight - artHeightPx
                    artHeightPx = newHeight
                    return Offset(0f, used)
                }
                return Offset.Zero
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(collapseConnection),
    ) {
        item(key = "art") {
            ArtworkImage(
                url = tracks.firstOrNull()?.artworkUrl,
                contentDescription = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { artHeightPx.toDp() }),
                shape = RectangleShape,
                // Scale the whole cover to fit so it shrinks with the frame instead of clipping.
                contentScale = ContentScale.Fit,
            )
        }
        // Pins to the top once the art has scrolled away; the track list scrolls beneath it.
        stickyHeader(key = "header") {
            AlbumHeader(
                title = title,
                artist = tracks.firstOrNull()?.artist,
                trackCount = tracks.size,
                onPlay = { onPlay(tracks, 0) },
                onShuffle = { onPlay(tracks.shuffled(), 0) },
            )
        }
        val multiDisc = tracks.mapNotNull { it.discNumber }.distinct().size > 1
        if (multiDisc) {
            // Each disc is a low-elevation container card; its track cards sit raised on top.
            var start = 0
            val sections = tracks.groupBy { it.discNumber ?: 1 }.map { (disc, discTracks) ->
                DiscSection(disc, discTracks, start).also { start += discTracks.size }
            }
            items(sections, key = { "disc-${it.disc}" }) { section ->
                DiscCard(
                    section = section,
                    allTracks = tracks,
                    trackStatuses = trackStatuses,
                    onPlay = onPlay,
                    onRequestDownload = onRequestDownload,
                    onRemoveTrack = onRemoveTrack,
                )
            }
        } else {
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                TrackCard(
                    track = track,
                    onClick = { onPlay(tracks, index) },
                    downloadStatus = trackStatuses[track.id],
                    onDownload = { onRequestDownload(track) },
                    onRemoveLocal = { onRemoveTrack(track.id) },
                )
            }
        }
    }
}

/** A disc and its tracks, plus the tracks' starting position in the full album list. */
private data class DiscSection(val disc: Int, val tracks: List<Track>, val startIndex: Int)

@Composable
private fun DiscCard(
    section: DiscSection,
    allTracks: List<Track>,
    trackStatuses: Map<String, TrackDownloadStatus>,
    onPlay: (List<Track>, Int) -> Unit,
    onRequestDownload: (Track) -> Unit,
    onRemoveTrack: (String) -> Unit,
) {
    // Lower elevation / dimmer container so the disc reads as sitting *beneath* its tracks.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Text(
            text = "Disc ${section.disc}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        )
        section.tracks.forEachIndexed { i, track ->
            TrackCard(
                track = track,
                onClick = { onPlay(allTracks, section.startIndex + i) },
                downloadStatus = trackStatuses[track.id],
                onDownload = { onRequestDownload(track) },
                onRemoveLocal = { onRemoveTrack(track.id) },
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun AlbumHeader(
    title: String,
    artist: String?,
    trackCount: Int,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // (2) Album artist — smaller than the title, larger than track rows.
        if (!artist.isNullOrEmpty()) {
            Text(
                text = artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        // (5) Play (enqueue + play) and Shuffle (enqueue shuffled + play).
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Play")
            }
            FilledTonalButton(onClick = onShuffle) {
                Icon(Icons.Filled.Shuffle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Shuffle")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "$trackCount tracks",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** (3) Each track in an M3 filled card with a small (non-pill) corner radius. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackCard(
    track: Track,
    onClick: () -> Unit,
    downloadStatus: TrackDownloadStatus? = null,
    onDownload: (() -> Unit)? = null,
    onRemoveLocal: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onDownload != null || onRemoveLocal != null
    val canRemove = downloadStatus?.isComplete == true || downloadStatus?.isActive == true

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = track.trackNumber?.toString() ?: "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        track.artist?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Download state sits above the duration.
                    Column(horizontalAlignment = Alignment.End) {
                        TrackDownloadIndicator(downloadStatus)
                        track.durationMs?.let { ms ->
                            Text(
                                text = formatDuration(ms),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                TrackDownloadBar(
                    status = downloadStatus,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
                )
            }
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

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onExpandPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
    playbackViewModel: PlaybackViewModel = hiltViewModel(),
    downloadsViewModel: DownloadsViewModel = hiltViewModel(),
) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val trackStatuses by downloadsViewModel.trackStatuses.collectAsStateWithLifecycle()
    val transcodeDefault by downloadsViewModel.transcodeDefault.collectAsStateWithLifecycle()
    var pendingDownload by remember { mutableStateOf<DownloadTarget?>(null) }

    DownloadDialogs(
        target = pendingDownload,
        transcodeDefault = transcodeDefault,
        isMetered = downloadsViewModel::isMetered,
        onConfirm = { target, transcode ->
            if (target is DownloadTarget.TrackItem) {
                downloadsViewModel.downloadTrack(target.track, transcode)
            }
            pendingDownload = null
        },
        onDismiss = { pendingDownload = null },
    )
    TrackListDetail(
        title = viewModel.title,
        tracksState = tracks,
        onBack = onBack,
        onExpandPlayer = onExpandPlayer,
        onOpenSettings = onOpenSettings,
        onPlay = playbackViewModel::play,
        showTrackArtwork = true,
        trackStatuses = trackStatuses,
        onRequestDownload = { pendingDownload = DownloadTarget.TrackItem(it) },
        onRemoveTrack = downloadsViewModel::removeTrack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackListDetail(
    title: String,
    tracksState: ContentState<List<Track>>,
    onBack: () -> Unit,
    onExpandPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    showTrackArtwork: Boolean = false,
    trackStatuses: Map<String, TrackDownloadStatus> = emptyMap(),
    onRequestDownload: (Track) -> Unit = {},
    onRemoveTrack: (String) -> Unit = {},
) {
    Scaffold(
        topBar = { DetailTopBar(title = title, onBack = onBack, onOpenSettings = onOpenSettings) },
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
                                downloadStatus = trackStatuses[track.id],
                                onDownload = { onRequestDownload(track) },
                                onRemoveLocal = { onRemoveTrack(track.id) },
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
    onOpenSettings: () -> Unit,
    viewModel: ArtistDetailViewModel = hiltViewModel(),
) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    ArtistDetailContent(
        title = viewModel.title,
        albumsState = albums,
        onBack = onBack,
        onAlbumClick = onAlbumClick,
        onExpandPlayer = onExpandPlayer,
        onOpenSettings = onOpenSettings,
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
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = { DetailTopBar(title = title, onBack = onBack, onOpenSettings = onOpenSettings) },
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
private fun DetailTopBar(title: String, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        },
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
