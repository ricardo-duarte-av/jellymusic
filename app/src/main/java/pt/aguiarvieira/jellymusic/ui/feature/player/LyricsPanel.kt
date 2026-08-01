package pt.aguiarvieira.jellymusic.ui.feature.player

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    val activeLine = rememberActiveLineIndex(lyrics, progress, isPlaying)
    val activeIndex = activeLine.value
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

    // Following the song: keyed on the line index, so it covers ordinary playback and the user
    // dragging the seek bar alike — a seek changes the active line, which lands here like any other.
    LaunchedEffect(activeIndex) {
        if (activeIndex < 0) return@LaunchedEffect
        // First composition may not have measured the box yet; there is nothing to centre against.
        snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        if (SystemClock.elapsedRealtime() - lastDragAtMs < USER_SCROLL_GRACE_MS) return@LaunchedEffect
        listState.centreOn(activeIndex, animate = true)
    }

    // Expanding and collapsing resizes this box over the whole morph. Re-centre on every height it
    // passes through, so the sung line stays pinned to the middle as the box grows and shrinks —
    // and is therefore already centred the instant the mini box arrives, rather than inheriting
    // wherever the full-screen scroll position happened to leave it. Instant, not animated: these
    // are per-frame corrections during an animation, not a scroll of their own.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            .collect { height ->
                val line = activeLine.value
                if (height > 0 && line >= 0) listState.centreOn(line, animate = false)
            }
    }

    // Collapsed, a faint surface marks the lyrics out as a distinct, tappable box; expanded, it
    // dissolves so the words sit directly on the player background like a reading view.
    val containerAlpha by animateFloatAsState(
        targetValue = if (expanded) 0f else COLLAPSED_CONTAINER_ALPHA,
        label = "lyricsContainer",
    )
    val container = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(container.copy(alpha = containerAlpha))
            .clickable(onClick = onToggleExpanded),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .fadingEdges(FADE_HEIGHT),
            // Just enough slack to clear the fade at each end — no more. Padding the list by half a
            // box would let the first and last lines reach dead centre, but only by scrolling a
            // screenful of nothing in to do it; better they rest near the edge they belong to.
            contentPadding = PaddingValues(vertical = FADE_HEIGHT + 4.dp),
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
        // Runs until there is no next line to wait for: either playback is stopped, the lyrics
        // aren't timed, or the last line has been reached. All three settle on a final index.
        var following = true
        while (following) {
            val now = if (isPlaying) {
                p.positionMs + (SystemClock.elapsedRealtime() - anchoredAt)
            } else {
                p.positionMs
            }
            val index = lyrics.activeLineAt(now)
            value = index
            val nextStart = if (isPlaying && lyrics.synced) {
                lyrics.lines.getOrNull(index + 1)?.startMs
            } else {
                null
            }
            if (nextStart == null) {
                following = false
            } else {
                delay((nextStart - now).coerceAtLeast(MIN_LYRIC_TICK_MS))
            }
        }
    }
}

/**
 * Scrolls [index] to the vertical middle of the viewport. Long jumps (a seek across the song) snap
 * rather than animate — animating hundreds of lines would take longer than the listener's patience.
 */
private suspend fun LazyListState.centreOn(index: Int, animate: Boolean) {
    // viewportSize is the box itself; the viewportStart/End pair spans the content padding too, so
    // it is not interchangeable with it. Fall back only if the box hasn't reported a size yet.
    val viewport = layoutInfo.viewportSize.height.takeIf { it > 0 }
        ?: (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset)
    if (viewport <= 0) return

    val visible = layoutInfo.visibleItemsInfo
    val isNearby = visible.isNotEmpty() &&
        index >= visible.first().index - SNAP_DISTANCE_LINES &&
        index <= visible.last().index + SNAP_DISTANCE_LINES
    if (!isNearby) {
        // Too far to animate across (a seek to the other end of the song). Jump it into view first;
        // the measured correction below then closes whatever distance that left.
        scrollToItem(index)
        withFrameNanos { }
    }

    // Scroll by the distance actually measured between the line's middle and the box's middle.
    // Item offsets are in the viewport's own coordinate space, whose origin is viewportStartOffset —
    // and that goes *negative* by the content padding, because the padded area is still visible. So
    // the line's position within the box is its offset measured from there, not from zero. Skipping
    // that correction left every line exactly one padding's worth too low, which at this text size
    // is very nearly one line: the sung line kept landing last in the box instead of centred.
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val itemCentreInBox = item.offset - layoutInfo.viewportStartOffset + item.size / 2
    val delta = itemCentreInBox - viewport / 2
    // Both scroll primitives clamp at the list's own ends, which is what leaves the opening lines
    // resting high and the closing lines resting low instead of dragging empty space in to force
    // them centre.
    if (delta == 0) return
    if (animate) animateScrollBy(delta.toFloat()) else scrollBy(delta.toFloat())
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
