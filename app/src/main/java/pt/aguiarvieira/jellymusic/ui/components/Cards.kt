package pt.aguiarvieira.jellymusic.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.AlbumDownloadStatus
import pt.aguiarvieira.jellymusic.domain.model.Artist
import pt.aguiarvieira.jellymusic.domain.model.Playlist
import pt.aguiarvieira.jellymusic.ui.feature.downloads.AlbumArtDownloadBadge
import pt.aguiarvieira.jellymusic.ui.feature.downloads.AlbumDownloadBar

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    downloadStatus: AlbumDownloadStatus? = null,
    onDownload: (() -> Unit)? = null,
    onRemoveLocal: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onDownload != null || onRemoveLocal != null
    val canRemove = downloadStatus?.isComplete == true || downloadStatus?.inProgress == true

    Box {
        Card(
            modifier = modifier
                .padding(6.dp)
                // Whole-card container transform into the album screen (cover shares separately).
                .albumContainerTransform(album.id)
                // Stable handle for the screenshot instrumentation test to open an album.
                .testTag("albumCard")
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                ),
        ) {
            // Artwork lives in its own inner frame (card) inset from the outer card's edges.
            Box {
                Card(modifier = Modifier.padding(8.dp)) {
                    ArtworkImage(
                        url = album.artworkUrl,
                        contentDescription = album.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .sharedElementArt(albumArtSharedKey(album.id)),
                        shape = RectangleShape,
                    )
                }
                // Downloaded badge / progress ring at the album art's top-right corner.
                AlbumArtDownloadBadge(
                    status = downloadStatus,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
                FavoriteArtworkMarker(
                    favorite = album.isFavorite,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedText(albumTitleSharedKey(album.id)),
                )
                album.artist?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.sharedText(albumArtistSharedKey(album.id)),
                    )
                }
                // Album-wide download progress while queued/downloading.
                AlbumDownloadBar(
                    status = downloadStatus,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

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

@Composable
fun ArtistCard(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .testTag("artistCard")
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            ArtworkImage(
                url = artist.artworkUrl,
                contentDescription = artist.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = CircleShape,
                fallbackIcon = Icons.Filled.Person,
            )
            FavoriteArtworkMarker(
                favorite = artist.isFavorite,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    downloadStatus: AlbumDownloadStatus? = null,
    onDownload: (() -> Unit)? = null,
    onRemoveLocal: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onDownload != null || onRemoveLocal != null
    val canRemove = downloadStatus?.isComplete == true || downloadStatus?.inProgress == true

    Box {
        Card(
            modifier = modifier
                .testTag("playlistCard")
                .padding(6.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                ),
        ) {
            Box {
                Card(modifier = Modifier.padding(8.dp)) {
                    ArtworkImage(
                        url = playlist.artworkUrl,
                        contentDescription = playlist.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        shape = RectangleShape,
                        fallbackIcon = Icons.AutoMirrored.Filled.QueueMusic,
                    )
                }
                AlbumArtDownloadBadge(
                    status = downloadStatus,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
                FavoriteArtworkMarker(
                    favorite = playlist.isFavorite,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                playlist.trackCount?.let {
                    Text(
                        text = "$it tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AlbumDownloadBar(
                    status = downloadStatus,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

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
