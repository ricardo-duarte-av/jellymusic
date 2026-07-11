package pt.aguiarvieira.jellymusic.ui.feature.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import pt.aguiarvieira.jellymusic.domain.model.AlbumDownloadStatus
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
}

/**
 * The download options dialog (transcode switch + OK/Cancel), followed by a mobile-data warning when
 * the active connection is metered. Renders nothing when [target] is null.
 */
@Composable
fun DownloadDialogs(
    target: DownloadTarget?,
    transcodeDefault: Boolean,
    isMetered: () -> Boolean,
    onConfirm: (DownloadTarget, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (target == null) return
    var transcode by remember(target) { mutableStateOf(transcodeDefault) }
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
                            text = if (transcode) "Smaller, converted files" else "Original files",
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
}

/**
 * Overlay badge for album artwork: a save icon once the whole album is downloaded, or a determinate
 * ring while it's downloading. Place inside a Box and pass an aligned [modifier].
 */
@Composable
fun AlbumArtDownloadBadge(status: AlbumDownloadStatus?, modifier: Modifier = Modifier) {
    if (status == null || (!status.isComplete && !status.inProgress)) return
    Surface(
        modifier = modifier.padding(6.dp).size(26.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (status.isComplete) {
                Icon(
                    Icons.Filled.Save,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                CircularProgressIndicator(
                    progress = { status.progress },
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/** Compact per-track state: save icon when downloaded, ring while active, error mark on failure. */
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

        status.isActive -> if (status.isIndeterminate) {
            CircularProgressIndicator(modifier = modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            CircularProgressIndicator(
                progress = { status.progress },
                modifier = modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        }

        else -> Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = "Download failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = modifier.size(18.dp),
        )
    }
}
