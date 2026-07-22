package pt.aguiarvieira.jellymusic.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
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
import pt.aguiarvieira.jellymusic.ui.feature.downloads.TrackDownloadBar
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
    onToggleFavorite: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onDownload != null || onRemoveLocal != null || onToggleFavorite != null
    val canRemove = downloadStatus?.isComplete == true || downloadStatus?.isActive == true

    Box {
      Column {
        ListItem(
            modifier = modifier.combinedClickable(
                onClick = onClick,
                onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
            ),
            supportingContent = trackSupporting(track, downloadStatus),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FavoriteMarker(favorite = track.isFavorite, size = 16.dp)
                        track.durationMs?.let { ms ->
                            Text(
                                formatDuration(ms),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            },
        ) { Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        TrackDownloadBar(
            status = downloadStatus,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
        )
      }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            onToggleFavorite?.let { toggle ->
                DropdownMenuItem(
                    text = { Text(if (track.isFavorite) "Remove from favourites" else "Add to favourites") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (track.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        toggle()
                    },
                )
            }
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

/**
 * Supporting slot for a track row: the artist, plus a compact metadata line combining the upstream
 * codec, the downloaded local format (when present), and the ReplayGain value. Null when there's
 * nothing to show, so the row stays single-line.
 */
private fun trackSupporting(
    track: Track,
    downloadStatus: TrackDownloadStatus?,
): (@Composable () -> Unit)? {
    val meta = trackMetaLine(track, downloadStatus)
    if (track.artist == null && meta == null) return null
    return {
        Column {
            track.artist?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            meta?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Compact metadata line shown under a track title, mirroring the now-playing quality label:
 * "FLAC · 44.1 kHz · 24-bit · 1206 kbps · ↓ AAC 256 · RG ±x.x dB". Each part shows only when known;
 * null when nothing is available.
 */
internal fun trackMetaLine(track: Track, downloadStatus: TrackDownloadStatus?): String? {
    val parts = buildList {
        track.audioInfo?.let { info ->
            info.codec?.let { add(it.uppercase()) }
            info.sampleRateHz?.let { add("%.1f kHz".format(it / 1000.0)) }
            info.bitDepth?.let { add("$it-bit") }
            info.bitrateKbps?.let { add("$it kbps") }
        }
        downloadStatus?.localFormat?.let { add("↓ $it") }
        track.normalizationGainDb?.let { add("RG %+.1f dB".format(it)) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
