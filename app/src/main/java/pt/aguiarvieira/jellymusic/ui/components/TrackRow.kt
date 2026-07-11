package pt.aguiarvieira.jellymusic.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.domain.model.TrackDownloadStatus
import pt.aguiarvieira.jellymusic.ui.feature.downloads.TrackDownloadIndicator

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showArtwork: Boolean = false,
    downloadStatus: TrackDownloadStatus? = null,
    onDownload: (() -> Unit)? = null,
    onRemoveLocal: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onDownload != null || onRemoveLocal != null
    val canRemove = downloadStatus?.isComplete == true || downloadStatus?.isActive == true

    Box {
        ListItem(
            modifier = modifier.combinedClickable(
                onClick = onClick,
                onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
            ),
            headlineContent = {
                Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = track.artist?.let { artist ->
                { Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
            leadingContent = {
                if (showArtwork) {
                    ArtworkImage(
                        url = track.artworkUrl,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                    )
                } else {
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = track.trackNumber?.toString() ?: "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    TrackDownloadIndicator(downloadStatus)
                    track.durationMs?.let { ms ->
                        Text(formatDuration(ms), style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
        )

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            onDownload?.let { download ->
                DropdownMenuItem(
                    text = { Text("Download") },
                    leadingIcon = { Icon(Icons.Filled.Save, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        download()
                    },
                )
            }
            if (canRemove) {
                onRemoveLocal?.let { remove ->
                    DropdownMenuItem(
                        text = { Text("Remove local") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            remove()
                        },
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
