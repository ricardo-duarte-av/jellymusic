package pt.aguiarvieira.jellymusic.ui.feature.player

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/**
 * The transport button's glyph, covering all three player states: playing (pause icon), paused (play
 * icon) and buffering (an animated indicator). Shared by the mini player and the full player so both
 * show the same third state; [size] is the glyph size inside the caller's button.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayPauseButtonContent(isPlaying: Boolean, isBuffering: Boolean, size: Dp) {
    if (isBuffering) {
        // Expressive morphing indicator, tinted to the enclosing button's content colour (it defaults
        // to an activeIndicator colour that ignores a filled button's onPrimary contrast).
        LoadingIndicator(
            modifier = Modifier
                .size(size)
                .semantics { contentDescription = "Buffering" },
            color = LocalContentColor.current,
        )
    } else {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            modifier = Modifier.size(size),
        )
    }
}
