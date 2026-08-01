package pt.aguiarvieira.jellymusic.ui.feature.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import pt.aguiarvieira.jellymusic.domain.model.AlbumDownloadStatus
import pt.aguiarvieira.jellymusic.domain.model.DownloadState
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.domain.model.TrackDownloadStatus

/** What the user long-pressed to download. */
sealed interface DownloadTarget {
    data class Album(
        val id: String,
        val name: String,
        val artist: String?,
        val artworkUrl: String?,
    ) : DownloadTarget

    data class TrackItem(val track: Track) : DownloadTarget

    data class Playlist(
        val id: String,
        val name: String,
        val artworkUrl: String?,
    ) : DownloadTarget
}

/**
 * The download options dialog (transcode switch + OK/Cancel), followed by a mobile-data warning when
 * the active connection is metered. Renders nothing when [target] is null.
 */
@Composable
fun DownloadDialogs(
    target: DownloadTarget?,
    downloadSettings: StreamSettings,
    isMetered: () -> Boolean,
    onConfirm: (DownloadTarget, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (target == null) return
    var transcode by remember(target) { mutableStateOf(downloadSettings.transcode) }
    var showWarning by remember(target) { mutableStateOf(false) }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Use mobile data?") },
            text = { Text("You're on a metered connection. Downloading now may use mobile data.") },
            confirmButton = {
                TextButton(onClick = { onConfirm(target, transcode) }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download") },
        text = {
            Column {
                Text(
                    text = targetLabel(target),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Transcode", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (transcode) {
                                "Smaller files: ${downloadSettings.codec.label}, " +
                                    "max ${downloadSettings.maxBitrateKbps} kbps"
                            } else {
                                "Original files"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = transcode, onCheckedChange = { transcode = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (isMetered()) showWarning = true else onConfirm(target, transcode)
            }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun targetLabel(target: DownloadTarget): String = when (target) {
    is DownloadTarget.Album -> "Download the album \"${target.name}\" for offline playback."
    is DownloadTarget.TrackItem -> "Download \"${target.track.name}\" for offline playback."
    is DownloadTarget.Playlist -> "Download all tracks in \"${target.name}\" for offline playback."
}

/**
 * Overlay badge for album artwork: a save icon once the whole album is downloaded. In-progress
 * albums show a [AlbumDownloadBar] on the card instead. Place inside a Box with an aligned [modifier].
 */
@Composable
fun AlbumArtDownloadBadge(status: AlbumDownloadStatus?, modifier: Modifier = Modifier) {
    if (status?.isComplete != true) return
    Surface(
        modifier = modifier.padding(12.dp),
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.35f),
    ) {
        Icon(
            Icons.Filled.Save,
            contentDescription = "Downloaded",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(4.dp)
                .size(18.dp),
        )
    }
}

/** Thin album-wide progress bar shown while the album is downloading/queued; hidden otherwise. */
@Composable
fun AlbumDownloadBar(status: AlbumDownloadStatus?, modifier: Modifier = Modifier) {
    if (status == null || status.isComplete || !status.inProgress) return
    // Determinate once at least one track is done; indeterminate while everything is still queued.
    if (status.completed > 0) {
        LinearProgressIndicator(progress = { status.progress }, modifier = modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth())
    }
}

/** Compact per-track state marker: save icon when downloaded, error mark on failure. */
@Composable
fun TrackDownloadIndicator(status: TrackDownloadStatus?, modifier: Modifier = Modifier) {
    when {
        status == null -> Unit
        status.isComplete -> Icon(
            Icons.Filled.Save,
            contentDescription = "Downloaded",
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.size(18.dp),
        )

        status.state == DownloadState.FAILED -> Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = "Download failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = modifier.size(18.dp),
        )

        else -> Unit // active state is shown by TrackDownloadBar
    }
}

/** Thin per-track progress bar shown while the track is downloading/queued; hidden otherwise. */
@Composable
fun TrackDownloadBar(status: TrackDownloadStatus?, modifier: Modifier = Modifier) {
    if (status == null || !status.isActive) return
    if (status.isIndeterminate) {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { status.progress }, modifier = modifier.fillMaxWidth())
    }
}
