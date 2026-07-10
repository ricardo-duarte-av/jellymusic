package pt.aguiarvieira.jellymusic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.aguiarvieira.jellymusic.domain.model.Track

@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showArtwork: Boolean = false,
) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
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
        trailingContent = track.durationMs?.let { ms ->
            { Text(formatDuration(ms), style = MaterialTheme.typography.labelMedium) }
        },
    )
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
