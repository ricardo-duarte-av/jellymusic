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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
    onOpenChangelog: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.streamSettings.collectAsStateWithLifecycle()
    val dynamicTheme by viewModel.dynamicAlbumTheme.collectAsStateWithLifecycle()
    val lyricsEnabled by viewModel.lyricsEnabled.collectAsStateWithLifecycle()
    val downloadSettings by viewModel.downloadSettings.collectAsStateWithLifecycle()
    val replayGain by viewModel.replayGainSettings.collectAsStateWithLifecycle()
    val downloadFavorites by viewModel.downloadFavorites.collectAsStateWithLifecycle()
    val downloadFavoritesOnMetered by viewModel.downloadFavoritesOnMetered.collectAsStateWithLifecycle()
    val context = LocalContext.current

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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Lyrics", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Show lyrics on the now-playing screen for songs that have them. " +
                            "Off leaves the cover its full size.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = lyricsEnabled, onCheckedChange = viewModel::setLyricsEnabled)
            }

            // Recovery for a corrupt cached cover: wipes Coil's image cache so artwork re-fetches.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Clear image cache", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Fixes covers stuck on a placeholder. Artwork re-downloads as needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        viewModel.clearImageCache {
                            Toast.makeText(context, "Image cache cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text("Clear", modifier = Modifier.padding(start = 8.dp))
                }
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

            Text(
                text = "Offline",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Transcode downloads", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (downloadSettings.transcode) {
                            "Downloads are converted to save space on the device."
                        } else {
                            "Download the original files. Set separately from streaming, so you can " +
                                "keep good quality offline while streaming smaller."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = downloadSettings.transcode, onCheckedChange = viewModel::setDownloadTranscode)
            }

            if (downloadSettings.transcode) {
                Text("Codec", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioCodec.entries.forEach { codec ->
                        FilterChip(
                            selected = downloadSettings.codec == codec,
                            onClick = { viewModel.setDownloadCodec(codec) },
                            label = { Text(codec.label) },
                        )
                    }
                }

                Text("Max bitrate", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    STREAM_BITRATE_OPTIONS.forEach { kbps ->
                        FilterChip(
                            selected = downloadSettings.maxBitrateKbps == kbps,
                            onClick = { viewModel.setDownloadBitrate(kbps) },
                            label = { Text("$kbps kbps") },
                        )
                    }
                }
            }

            Text(
                text = "Applies to new downloads — files already on the device keep the format they " +
                    "were downloaded in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Download favourites to device", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Keep your hearted tracks, albums and playlists available offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = downloadFavorites, onCheckedChange = viewModel::setDownloadFavorites)
            }

            if (downloadFavorites) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Allow on mobile data", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "By default, favourites only download on Wi-Fi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = downloadFavoritesOnMetered,
                        onCheckedChange = viewModel::setDownloadFavoritesOnMetered,
                    )
                }
            }

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

            HorizontalDivider()

            // Changelog and About are always the last two entries (changelog first). See CLAUDE.md.
            SettingsNavRow(
                icon = Icons.Filled.History,
                title = "Changelog",
                subtitle = "What's new in each version",
                onClick = onOpenChangelog,
            )
            SettingsNavRow(
                icon = Icons.Filled.Info,
                title = "About",
                subtitle = "Version and source code",
                onClick = onOpenAbout,
            )
        }
    }
}

/** A tappable settings entry that navigates to a sub-screen (icon · title/subtitle · chevron). */
@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
