package pt.aguiarvieira.jellymusic.ui.feature.player

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import pt.aguiarvieira.jellymusic.domain.model.Lyrics
import pt.aguiarvieira.jellymusic.playback.PlaybackProgress
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The lyrics box on the full player screen: plain text for unsynced lyrics, or a follow-along view
 * that highlights and centres the line currently being sung for synchronized ones.
 *
 * Tapping anywhere toggles [expanded], which the player screen uses to shrink the cover into a
 * corner thumbnail and hand the reclaimed height to this panel.
 *
 * All emphasis comes from M3 role colours ([androidx.compose.material3.ColorScheme.primary] and
 * friends), so the highlight automatically follows the album-derived scheme when dynamic album
 * colour is on and the device scheme when it isn't.
 */
@Composable
fun LyricsPanel(
    lyrics: Lyrics,
    progress: StateFlow<PlaybackProgress>,
    isPlaying: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeIndex by rememberActiveLineIndex(lyrics, progress, isPlaying)
    val listState = rememberLazyListState()

    // Following the song fights the user when they're reading ahead, so hold off auto-scrolling for
    // a few seconds after any drag of the list.
    var lastDragAtMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start || interaction is DragInteraction.Stop) {
                lastDragAtMs = SystemClock.elapsedRealtime()
            }
        }
    }

    // Re-centring is keyed on the line index, so it covers both ordinary playback and the user
    // dragging the seek bar — a seek changes the active line, which lands here like any other.
    LaunchedEffect(activeIndex, expanded) {
        if (activeIndex < 0) return@LaunchedEffect
        // Centring needs a measured viewport, and it needs the *settled* one: expanding grows this
        // box over the whole morph, so centring against a half-grown viewport would leave the line
        // sitting off-centre once it finished. Wait for two frames at the same height. In the
        // ordinary case (no morph running) that's already true, so this costs a frame.
        var previous = -1
        while (true) {
            val height = listState.layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset }
            if (height > 0 && height == previous) break
            previous = height
            withFrameNanos { }
        }
        if (SystemClock.elapsedRealtime() - lastDragAtMs < USER_SCROLL_GRACE_MS) return@LaunchedEffect
        listState.centreOn(activeIndex)
    }

    // Collapsed, a faint surface marks the lyrics out as a distinct, tappable box; expanded, it
    // dissolves so the words sit directly on the player background like a reading view.
    val containerAlpha by animateFloatAsState(
        targetValue = if (expanded) 0f else COLLAPSED_CONTAINER_ALPHA,
        label = "lyricsContainer",
    )
    val container = MaterialTheme.colorScheme.surfaceContainerHighest

    BoxWithConstraints(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(container.copy(alpha = containerAlpha))
            .clickable(onClick = onToggleExpanded),
    ) {
        // Synced lyrics park the active line in the middle of the box, so the list needs half a
        // box of slack at each end for the first and last lines to reach it.
        val edgePadding = if (lyrics.synced) maxHeight / 2 else 8.dp
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(FADE_HEIGHT),
            contentPadding = PaddingValues(vertical = edgePadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                if (line.text.isEmpty()) {
                    // Blank line: a stanza break in the source file. Keep the breathing room.
                    Box(Modifier.height(STANZA_BREAK_HEIGHT))
                } else {
                    LyricLineText(
                        text = line.text,
                        // Unsynced lyrics have no "now", so every line stays equally readable.
                        distance = if (lyrics.synced) index - activeIndex else UNSYNCED_DISTANCE,
                    )
                }
            }
        }
    }
}

/**
 * One line, emphasised by how far it is from the line being sung: the current line is drawn in the
 * theme's primary colour on a faint primary wash, its immediate neighbours stay legible, and
 * everything further out recedes.
 */
@Composable
private fun LyricLineText(text: String, distance: Int) {
    val isActive = distance == 0
    val targetAlpha = when {
        isActive -> 1f
        distance == UNSYNCED_DISTANCE -> UNSYNCED_ALPHA
        distance == -1 || distance == 1 -> NEAR_ALPHA
        else -> FAR_ALPHA
    }
    val alpha by animateFloatAsState(targetAlpha, label = "lyricAlpha")
    val color by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        label = "lyricColor",
    )
    val highlight by animateFloatAsState(
        targetValue = if (isActive) ACTIVE_WASH_ALPHA else 0f,
        label = "lyricHighlight",
    )
    val primary = MaterialTheme.colorScheme.primary

    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
        color = color.copy(alpha = alpha),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(primary.copy(alpha = highlight))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Index of the line being sung right now, or -1 before the first timed line (and always, for
 * unsynced lyrics).
 *
 * The player only reports its position twice a second, which would leave the highlight up to half a
 * beat late. So this anchors a monotonic clock to each position sample and advances between them,
 * then sleeps exactly until the next line is due rather than polling — the state only ever changes
 * when the line actually does.
 */
@Composable
private fun rememberActiveLineIndex(
    lyrics: Lyrics,
    progress: StateFlow<PlaybackProgress>,
    isPlaying: Boolean,
): State<Int> {
    val p by progress.collectAsStateWithLifecycle()
    return produceState(lyrics.activeLineAt(p.positionMs), lyrics, p, isPlaying) {
        val anchoredAt = SystemClock.elapsedRealtime()
        while (true) {
            val now = if (isPlaying) {
                p.positionMs + (SystemClock.elapsedRealtime() - anchoredAt)
            } else {
                p.positionMs
            }
            val index = lyrics.activeLineAt(now)
            value = index
            if (!isPlaying || !lyrics.synced) break
            val nextStart = lyrics.lines.getOrNull(index + 1)?.startMs ?: break
            delay((nextStart - now).coerceAtLeast(MIN_LYRIC_TICK_MS))
        }
    }
}

/**
 * Scrolls [index] to the vertical middle of the viewport. Long jumps (a seek across the song) snap
 * rather than animate — animating hundreds of lines would take longer than the listener's patience.
 */
private suspend fun LazyListState.centreOn(index: Int) {
    val info = layoutInfo
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    if (viewport <= 0) return
    val itemHeight = info.visibleItemsInfo.firstOrNull { it.index == index }?.size
        ?: info.visibleItemsInfo.firstOrNull()?.size
        ?: 0
    val offset = -((viewport - itemHeight) / 2).coerceAtLeast(0)
    val visible = info.visibleItemsInfo
    val isNearby = visible.isNotEmpty() &&
        index >= visible.first().index - SNAP_DISTANCE_LINES &&
        index <= visible.last().index + SNAP_DISTANCE_LINES
    if (isNearby) animateScrollToItem(index, offset) else scrollToItem(index, offset)
}

/**
 * Softens the top and bottom edges of a scrolling list so lines dissolve into the background
 * instead of being sliced off at the box border.
 */
private fun Modifier.fadingEdges(fadeHeight: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = (fadeHeight.toPx() / size.height).coerceIn(0f, 0.5f)
        if (fade <= 0f) return@drawWithContent
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                fade to Color.Black,
                1f - fade to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/** Sentinel "distance" for unsynced lyrics, where no line is the current one. */
private const val UNSYNCED_DISTANCE = Int.MIN_VALUE

private const val UNSYNCED_ALPHA = 0.85f
private const val NEAR_ALPHA = 0.55f
private const val FAR_ALPHA = 0.32f
private const val ACTIVE_WASH_ALPHA = 0.10f
private const val COLLAPSED_CONTAINER_ALPHA = 0.45f

private const val MIN_LYRIC_TICK_MS = 16L
private const val USER_SCROLL_GRACE_MS = 4_000L
private const val SNAP_DISTANCE_LINES = 4

private val FADE_HEIGHT = 28.dp
private val STANZA_BREAK_HEIGHT = 12.dp
