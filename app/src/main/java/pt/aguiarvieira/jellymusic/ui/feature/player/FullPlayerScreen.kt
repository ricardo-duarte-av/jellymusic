package pt.aguiarvieira.jellymusic.ui.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import pt.aguiarvieira.jellymusic.playback.PlaybackProgress
import pt.aguiarvieira.jellymusic.playback.QueueItem
import pt.aguiarvieira.jellymusic.playback.RepeatMode
import pt.aguiarvieira.jellymusic.ui.components.ArtworkImage
import pt.aguiarvieira.jellymusic.ui.components.FavoriteToggleButton
import pt.aguiarvieira.jellymusic.ui.components.NOW_PLAYING_ART_KEY
import pt.aguiarvieira.jellymusic.ui.components.nowPlayingContainerTransform
import pt.aguiarvieira.jellymusic.ui.components.sharedElementArt
import pt.aguiarvieira.jellymusic.ui.theme.AlbumTheme
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FullPlayerScreen(
    onCollapse: () -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateToAlbum: (albumId: String, albumName: String, artworkUrl: String?) -> Unit,
    onNavigateToArtist: (artistId: String, artistName: String) -> Unit,
    viewModel: PlaybackViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val qualityLabel by viewModel.qualityLabel.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyrics.collectAsStateWithLifecycle()

    var showQueue by remember { mutableStateOf(false) }

    // Tapping the lyrics box shrinks the cover into the corner and gives the lyrics the rest of the
    // screen. Reset whenever the incoming track has no lyrics — there'd be nothing to read.
    var lyricsExpanded by remember { mutableStateOf(false) }
    val lyrics = (lyricsState as? LyricsState.Available)?.lyrics
    LaunchedEffect(lyrics == null) { if (lyrics == null) lyricsExpanded = false }

    // Drives the whole header morph (cover size and position, metadata placement, top spacing).
    val headerFraction by animateFloatAsState(
        targetValue = if (lyricsExpanded) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "lyricsHeaderMorph",
    )
    val compactHeader = headerFraction > 0.5f

    AlbumTheme(artworkUrl = state.artworkUri) {
    // Container transform: this whole screen grows out of (and collapses back into) the mini player
    // bar, with the cover riding on top as a shared element.
    Box(modifier = Modifier.nowPlayingContainerTransform()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse")
                    }
                },
                actions = {
                    if (state.hasMedia) {
                        FavoriteToggleButton(favorite = isFavorite, onToggle = { viewModel.toggleFavorite() })
                    }
                    IconButton(onClick = { showQueue = true }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = "Queue")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // With no lyrics on screen the cover sits centred between the bars, as before. With
                // lyrics it rides near the top, and collapses right up to it once they're expanded.
                Spacer(Modifier.weight(lerp(if (lyrics != null) 0.18f else 1f, 0.02f, headerFraction)))

                NowPlayingHeader(
                    fraction = headerFraction,
                    modifier = Modifier.fillMaxWidth(),
                    art = {
                        ArtworkImage(
                            url = state.artworkUri,
                            contentDescription = state.title,
                            modifier = Modifier
                                .fillMaxSize()
                                .sharedElementArt(NOW_PLAYING_ART_KEY),
                            // A 28dp radius reads as a lozenge once the cover is thumbnail-sized, so
                            // tighten the corners as it shrinks.
                            shape = RoundedCornerShape(lerp(28.dp, 12.dp, headerFraction)),
                        )
                    },
                    metadata = {
                        Column(
                            horizontalAlignment = if (compactHeader) Alignment.Start else Alignment.CenterHorizontally,
                        ) {
                            val textAlign = if (compactHeader) TextAlign.Start else TextAlign.Center
                            // Track title and album both jump to the album detail screen; artist jumps to the
                            // artist detail screen. Each is tappable only when the id is known.
                            val albumTarget = state.albumId
                            Text(
                                text = state.title,
                                style = if (compactHeader) {
                                    MaterialTheme.typography.titleLargeEmphasized
                                } else {
                                    MaterialTheme.typography.headlineSmallEmphasized
                                },
                                textAlign = textAlign,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickableTo(
                                    enabled = albumTarget != null,
                                    onClick = {
                                        albumTarget?.let {
                                            onNavigateToAlbum(it, state.album.orEmpty(), state.artworkUri)
                                        }
                                    },
                                ),
                            )
                            state.album?.takeIf { it.isNotBlank() }?.let { album ->
                                Text(
                                    text = album,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = textAlign,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .clickableTo(
                                            enabled = albumTarget != null,
                                            onClick = {
                                                albumTarget?.let { onNavigateToAlbum(it, album, state.artworkUri) }
                                            },
                                        ),
                                )
                            }
                            val artistTarget = state.artistId
                            Text(
                                text = state.artist,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = textAlign,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickableTo(
                                        enabled = artistTarget != null,
                                        onClick = { artistTarget?.let { onNavigateToArtist(it, state.artist) } },
                                    ),
                            )
                            // Quality and source are detail for browsing, not for reading along —
                            // they fold away to leave the lyrics as much room as possible.
                            AnimatedVisibility(
                                visible = !compactHeader,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically(),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    // File quality: original details, "original → transcoded" when streaming
                                    // transcode is on, or just the transcoded format when playing a
                                    // downloaded transcoded file.
                                    qualityLabel?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                    }
                                    // Source: playing from a local download vs streaming from the server.
                                    if (state.hasMedia) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(top = 4.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (state.isLocal) {
                                                    Icons.Filled.Save
                                                } else {
                                                    Icons.Filled.CloudQueue
                                                },
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = buildString {
                                                    append(if (state.isLocal) "Downloaded" else "Streaming")
                                                    // Append this track's ReplayGain value when the server
                                                    // has scanned it.
                                                    state.normalizationGainDb?.let {
                                                        append("  ·  ReplayGain %+.1f dB".format(it))
                                                    }
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                )

                // The gap the removed colour banner and the tightened cover spacing gave back: with
                // lyrics it's the reading area, without them it's the same empty breathing room the
                // screen has always had above the seek bar.
                if (lyrics != null) {
                    Spacer(Modifier.height(12.dp))
                    LyricsPanel(
                        lyrics = lyrics,
                        progress = viewModel.progress,
                        isPlaying = state.isPlaying,
                        expanded = lyricsExpanded,
                        onToggleExpanded = { lyricsExpanded = !lyricsExpanded },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    Spacer(Modifier.height(12.dp))
                } else {
                    Spacer(Modifier.weight(1f))
                }

                // Collects the position flow internally so only the seek bar recomposes as it advances.
                PlayerSeekBar(progress = viewModel.progress, onSeek = viewModel::seekTo)

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    // Shuffle toggle — tinted when enabled.
                    IconButton(onClick = viewModel::toggleShuffle, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Shuffle,
                            contentDescription = if (state.shuffleEnabled) "Shuffle on" else "Shuffle off",
                            tint = if (state.shuffleEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = viewModel::previous, modifier = Modifier.size(56.dp)) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(72.dp)) {
                        PlayPauseButtonContent(
                            isPlaying = state.isPlaying,
                            isBuffering = state.isBuffering,
                            size = 40.dp,
                        )
                    }
                    IconButton(onClick = viewModel::next, modifier = Modifier.size(56.dp)) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    // Repeat toggle — off / all / one, tinted when active.
                    IconButton(onClick = viewModel::cycleRepeat, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = if (state.repeatMode == RepeatMode.ONE) {
                                Icons.Filled.RepeatOne
                            } else {
                                Icons.Filled.Repeat
                            },
                            contentDescription = "Repeat ${state.repeatMode.name.lowercase()}",
                            tint = if (state.repeatMode == RepeatMode.OFF) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }

        if (showQueue) {
            // Start hidden and allow only Hidden/Expanded — the replacement for the old
            // skipPartiallyExpanded = true (no half-open detent for the queue sheet).
            val sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            )
            ModalBottomSheet(
                onDismissRequest = { showQueue = false },
                sheetState = sheetState,
            ) {
                QueueSheet(
                    queue = queue,
                    onPlay = {
                        viewModel.playIndex(it)
                        showQueue = false
                    },
                    onRemove = viewModel::removeFromQueue,
                )
            }
        }
    }
    }
}

@Composable
private fun QueueSheet(
    queue: List<QueueItem>,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Text(
        text = "Up next",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
    )
    LazyColumn {
        items(queue, key = { "${it.index}-${it.id}" }) { item ->
            ListItem(
                modifier = Modifier.clickable { onPlay(item.index) },
                colors = if (item.isCurrent) {
                    ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    ListItemDefaults.colors()
                },
                leadingContent = {
                    ArtworkImage(
                        url = item.artworkUri,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        shape = MaterialTheme.shapes.small,
                    )
                },
                supportingContent = if (item.artist.isNotEmpty()) {
                    { Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                } else {
                    null
                },
                trailingContent = {
                    IconButton(onClick = { onRemove(item.index) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove from queue")
                    }
                },
            ) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Seek bar + elapsed/total labels. Collects [progress] locally so it is the only thing that
 * recomposes as playback advances (~2 Hz), keeping the rest of the player screen stable.
 */
@Composable
private fun PlayerSeekBar(
    progress: StateFlow<PlaybackProgress>,
    onSeek: (Long) -> Unit,
) {
    val p by progress.collectAsStateWithLifecycle()
    // While dragging, the thumb follows the finger. After release we keep showing the seeked
    // position (holding scrubFraction) until playback actually reaches it, so the bar never snaps
    // back to the old position in the gap before the player reports the new one.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var isScrubbing by remember { mutableStateOf(false) }

    val liveFraction = if (p.durationMs > 0) {
        (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val displayFraction = scrubFraction ?: liveFraction

    // Once the seek is committed, hand control back to the live position as soon as it catches up.
    LaunchedEffect(isScrubbing, p.positionMs) {
        val target = scrubFraction ?: return@LaunchedEffect
        if (!isScrubbing && abs(liveFraction - target) < 0.01f) {
            scrubFraction = null
        }
    }
    // Safety net: if playback never quite reaches the target (e.g. a non-seekable stream), release
    // the held value after a short grace period instead of freezing the thumb.
    LaunchedEffect(isScrubbing) {
        if (!isScrubbing && scrubFraction != null) {
            delay(SEEK_SETTLE_GRACE_MS)
            scrubFraction = null
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bubbleWidth = 56.dp
        val maxOffset = maxWidth - bubbleWidth
        val bubbleOffset = (maxOffset * displayFraction).coerceIn(0.dp, maxOffset)
        // Floating time tag that tracks the thumb while the user is holding the bar.
        if (isScrubbing) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = bubbleOffset)
                    .width(bubbleWidth),
            ) {
                Text(
                    text = formatTime((displayFraction * p.durationMs).toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                )
            }
        }
        Slider(
            value = displayFraction,
            onValueChange = {
                scrubFraction = it
                isScrubbing = true
            },
            onValueChangeFinished = {
                scrubFraction?.let { fraction -> onSeek((fraction * p.durationMs).toLong()) }
                isScrubbing = false
            },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(top = 28.dp),
        )
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = formatTime((displayFraction * p.durationMs).toLong()),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatTime(p.durationMs),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * Makes a now-playing metadata line tappable (rounded ripple) only when a navigation target is
 * known; otherwise it stays plain text with no click affordance.
 */
@Composable
private fun Modifier.clickableTo(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (enabled) {
        then(
            Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    } else {
        this
    }

private const val SEEK_SETTLE_GRACE_MS = 1_000L

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
