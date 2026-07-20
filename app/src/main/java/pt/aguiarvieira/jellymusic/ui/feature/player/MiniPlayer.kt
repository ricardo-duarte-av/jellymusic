package pt.aguiarvieira.jellymusic.ui.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import pt.aguiarvieira.jellymusic.playback.PlaybackProgress
import pt.aguiarvieira.jellymusic.ui.components.ArtworkImage
import pt.aguiarvieira.jellymusic.ui.components.nowPlayingArtSharedKey
import pt.aguiarvieira.jellymusic.ui.components.sharedElementArt
import pt.aguiarvieira.jellymusic.ui.theme.AlbumTheme

/** Fraction of the bar's width a swipe must pass to dismiss (stop) instead of springing back. */
private const val DISMISS_THRESHOLD_FRACTION = 0.33f

/** How far along the swipe-to-dismiss the bar is, 0..1 — drives the fade-out. */
private fun dismissFraction(offset: Float, widthPx: Int): Float =
    if (widthPx <= 0) 0f else (abs(offset) / widthPx).coerceIn(0f, 1f)

/** Persistent mini player docked above the navigation bar. Hidden when nothing is loaded. */
@Composable
fun MiniPlayer(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaybackViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.hasMedia) return

    // Swipe the bar left or right to stop playback and clear the queue (there's no stop button).
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableIntStateOf(0) }

    // Retint to the now-playing album's cover.
    AlbumTheme(artworkUrl = state.artworkUri) {
        Column(
            modifier
                .fillMaxWidth()
                .onSizeChanged { widthPx = it.width }
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                // Fade out as it slides so the swipe reads as a dismissal.
                .alpha(1f - dismissFraction(offsetX.value, widthPx))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val past = widthPx > 0 && abs(offsetX.value) > widthPx * DISMISS_THRESHOLD_FRACTION
                            if (past) {
                                val target = if (offsetX.value > 0) widthPx.toFloat() else -widthPx.toFloat()
                                scope.launch {
                                    offsetX.animateTo(target)
                                    viewModel.stop()
                                }
                            } else {
                                scope.launch { offsetX.animateTo(0f) }
                            }
                        },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            scope.launch { offsetX.snapTo(offsetX.value + delta) }
                        },
                    )
                },
        ) {
        // Soft top shade (album-tinted) so the bar separates from the list above it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)),
                    ),
                ),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Stable handle for the screenshot test to expand into the full player.
                    .testTag("miniPlayer")
                    .clickable(onClick = onExpand)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    url = state.artworkUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .sharedElementArt(nowPlayingArtSharedKey()),
                    shape = MaterialTheme.shapes.small,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.artist.isNotEmpty()) {
                        Text(
                            text = state.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = viewModel::togglePlayPause) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                    )
                }
            }
            // Collects the position flow internally so only this bar recomposes as playback advances.
            MiniPlayerProgress(progress = viewModel.progress)
        }
        }
        }
    }
}

@Composable
private fun MiniPlayerProgress(progress: StateFlow<PlaybackProgress>) {
    val p by progress.collectAsStateWithLifecycle()
    val fraction = if (p.durationMs > 0) {
        (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth(),
    )
}
