package pt.aguiarvieira.jellymusic.ui.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.aguiarvieira.jellymusic.ui.components.ArtworkImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerScreen(
    onCollapse: () -> Unit,
    viewModel: PlaybackViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Local scrubbing state so the thumb follows the finger, committed on release.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ArtworkImage(
                url = state.artworkUri,
                contentDescription = state.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = MaterialTheme.shapes.extraLarge,
            )

            Spacer(Modifier.height(32.dp))

            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(24.dp))

            val liveFraction = if (state.durationMs > 0) {
                (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            val sliderValue = scrubFraction ?: liveFraction
            Slider(
                value = sliderValue,
                onValueChange = { scrubFraction = it },
                onValueChangeFinished = {
                    scrubFraction?.let { fraction ->
                        viewModel.seekTo((fraction * state.durationMs).toLong())
                    }
                    scrubFraction = null
                },
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatTime(state.positionMs),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatTime(state.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Spacer(Modifier.height(24.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IconButton(onClick = viewModel::previous, modifier = Modifier.size(56.dp)) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(40.dp),
                    )
                }
                FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(80.dp)) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(44.dp),
                    )
                }
                IconButton(onClick = viewModel::next, modifier = Modifier.size(56.dp)) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
