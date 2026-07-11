package pt.aguiarvieira.jellymusic.ui.feature.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.aguiarvieira.jellymusic.data.db.AlbumDownloadEntity
import pt.aguiarvieira.jellymusic.data.db.TrackDownloadEntity
import pt.aguiarvieira.jellymusic.data.db.toDomainTrack
import pt.aguiarvieira.jellymusic.domain.model.AlbumDownloadStatus
import pt.aguiarvieira.jellymusic.domain.model.DownloadState
import pt.aguiarvieira.jellymusic.domain.model.TrackDownloadStatus
import pt.aguiarvieira.jellymusic.ui.components.ArtworkImage
import pt.aguiarvieira.jellymusic.ui.feature.player.MiniPlayer
import pt.aguiarvieira.jellymusic.ui.feature.player.PlaybackViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onAlbumClick: (String, String) -> Unit,
    onExpandPlayer: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
    playbackViewModel: PlaybackViewModel = hiltViewModel(),
) {
    val albums by viewModel.downloadedAlbums.collectAsStateWithLifecycle()
    val tracks by viewModel.downloadedTracks.collectAsStateWithLifecycle()
    val albumStatuses by viewModel.albumStatuses.collectAsStateWithLifecycle()
    val trackStatuses by viewModel.trackStatuses.collectAsStateWithLifecycle()

    val albumIds = remember(albums) { albums.map { it.albumId }.toSet() }
    // Tracks downloaded on their own (not part of a downloaded album).
    val individualTracks = remember(tracks, albumIds) {
        tracks.filter { it.albumId == null || it.albumId !in albumIds }
    }
    // Domain tracks for the standalone list, so tapping one plays it in context.
    val individualDomainTracks = remember(individualTracks) {
        individualTracks.map { it.toDomainTrack() }
    }
    val albumSizes = remember(tracks) {
        tracks.groupBy { it.albumId }.mapValues { (_, v) -> v.sumOf { it.downloadedBytes } }
    }
    val totalBytes = remember(tracks) {
        tracks.filter { it.state == DownloadState.COMPLETED.name }.sumOf { it.downloadedBytes }
    }
    val completedCount = remember(tracks) {
        tracks.count { it.state == DownloadState.COMPLETED.name }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = { MiniPlayer(onExpand = onExpandPlayer) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (albums.isEmpty() && individualTracks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No downloads yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            text = "${formatBytes(totalBytes)} · $completedCount ${if (completedCount == 1) "track" else "tracks"}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }

                    if (albums.isNotEmpty()) {
                        item { SectionHeader("Albums") }
                        items(albums, key = { it.albumId }) { album ->
                            AlbumDownloadRow(
                                album = album,
                                status = albumStatuses[album.albumId],
                                sizeBytes = albumSizes[album.albumId] ?: 0L,
                                onClick = { onAlbumClick(album.albumId, album.name) },
                                onRemove = { viewModel.removeAlbum(album.albumId) },
                            )
                        }
                    }

                    if (individualTracks.isNotEmpty()) {
                        item { SectionHeader("Tracks") }
                        itemsIndexed(individualTracks, key = { _, t -> t.trackId }) { index, track ->
                            TrackDownloadRow(
                                track = track,
                                status = trackStatuses[track.trackId],
                                onClick = { playbackViewModel.play(individualDomainTracks, index) },
                                onRemove = { viewModel.removeTrack(track.trackId) },
                            )
                        }
                    }

                    item { Spacer(Modifier.size(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun AlbumDownloadRow(
    album: AlbumDownloadEntity,
    status: AlbumDownloadStatus?,
    sizeBytes: Long,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(
                url = album.artworkUrl,
                contentDescription = album.name,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val counts = status?.let { "${it.completed}/${it.total} tracks" } ?: "${album.totalTracks} tracks"
                Text(
                    text = listOfNotNull(album.artist, counts, formatBytes(sizeBytes)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (status?.isComplete == true) {
                Icon(Icons.Filled.Save, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove local")
            }
        }
        AlbumDownloadBar(status = status, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
    }
}

@Composable
private fun TrackDownloadRow(
    track: TrackDownloadEntity,
    status: TrackDownloadStatus?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = listOfNotNull(track.artist, formatBytes(track.downloadedBytes)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TrackDownloadIndicator(status)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove local")
            }
        }
        TrackDownloadBar(status = status, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return if (i == 0) "${value.toLong()} ${units[i]}" else "%.1f %s".format(value, units[i])
}
