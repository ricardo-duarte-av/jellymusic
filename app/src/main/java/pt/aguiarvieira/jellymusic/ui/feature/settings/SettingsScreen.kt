package pt.aguiarvieira.jellymusic.ui.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.aguiarvieira.jellymusic.domain.model.AudioCodec
import pt.aguiarvieira.jellymusic.domain.model.ReplayGainSettings
import pt.aguiarvieira.jellymusic.domain.model.STREAM_BITRATE_OPTIONS

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.streamSettings.collectAsStateWithLifecycle()
    val dynamicTheme by viewModel.dynamicAlbumTheme.collectAsStateWithLifecycle()
    val replayGain by viewModel.replayGainSettings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Color from album art", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Tint the album and player screens using the cover.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = dynamicTheme, onCheckedChange = viewModel::setDynamicAlbumTheme)
            }

            HorizontalDivider()

            Text(
                text = "Playback",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Loudness normalization", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Even out volume between tracks using the server's ReplayGain scan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = replayGain.enabled, onCheckedChange = viewModel::setReplayGainEnabled)
            }

            if (replayGain.enabled) {
                // Local draft for smooth dragging; persisted only when the gesture finishes.
                var preampDraft by remember { mutableFloatStateOf(replayGain.preampDb) }
                LaunchedEffect(replayGain.preampDb) { preampDraft = replayGain.preampDb }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Pre-amp", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text(
                        text = "%+.1f dB".format(preampDraft),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = preampDraft,
                    onValueChange = { preampDraft = it },
                    onValueChangeFinished = { viewModel.setReplayGainPreampDb(preampDraft) },
                    valueRange = ReplayGainSettings.PREAMP_MIN_DB..ReplayGainSettings.PREAMP_MAX_DB,
                )
                Text(
                    text = "Applies on top of each track's gain. Changes take effect immediately, " +
                        "including on the current track.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Text(
                text = "Streaming",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Transcode streaming", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (settings.transcode) {
                            "Server transcodes to a smaller stream."
                        } else {
                            "Play the original file (direct play)."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.transcode, onCheckedChange = viewModel::setTranscode)
            }

            if (settings.transcode) {
                Text("Codec", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioCodec.entries.forEach { codec ->
                        FilterChip(
                            selected = settings.codec == codec,
                            onClick = { viewModel.setCodec(codec) },
                            label = { Text(codec.label) },
                        )
                    }
                }

                Text("Max bitrate", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    STREAM_BITRATE_OPTIONS.forEach { kbps ->
                        FilterChip(
                            selected = settings.maxBitrateKbps == kbps,
                            onClick = { viewModel.setBitrate(kbps) },
                            label = { Text("$kbps kbps") },
                        )
                    }
                }
            }

            Text(
                text = "Changes apply to the next track — the currently playing track keeps its quality.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // Entry point to the offline downloads manager.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDownloads)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                    Text("Downloads", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Manage offline albums and tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}
