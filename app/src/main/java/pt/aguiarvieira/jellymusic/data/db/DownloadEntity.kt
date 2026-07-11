package pt.aguiarvieira.jellymusic.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import pt.aguiarvieira.jellymusic.domain.model.Track

/**
 * One track's offline copy. Rows exist only for tracks the user asked to download; enough metadata
 * is stored to render and play the track fully offline.
 */
@Entity(tableName = "track_downloads")
data class TrackDownloadEntity(
    @PrimaryKey val trackId: String,
    val albumId: String?,
    val title: String,
    val artist: String?,
    val album: String?,
    val trackNumber: Int?,
    val durationMs: Long?,
    val artworkUrl: String?,
    /** Download-time transcode choice (independent of the streaming setting). */
    val transcoded: Boolean,
    val codec: String?,        // AudioCodec.name when transcoded
    val bitrateKbps: Int?,     // when transcoded
    val state: String,         // DownloadState.name
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L, // 0 when the server didn't advertise a length
    val filePath: String?,     // set once COMPLETED
    val updatedAt: Long,
)

/**
 * A requested album download. Stores the album's total track count (captured at request time) so the
 * UI can tell "whole album downloaded" without re-hitting the server.
 */
@Entity(tableName = "album_downloads")
data class AlbumDownloadEntity(
    @PrimaryKey val albumId: String,
    val name: String,
    val artist: String?,
    val artworkUrl: String?,
    val totalTracks: Int,
    val transcoded: Boolean,
    val requestedAt: Long,
)

/** Reconstructs a domain [Track] from a stored download, for offline browsing/playback. */
fun TrackDownloadEntity.toDomainTrack(): Track = Track(
    id = trackId,
    name = title,
    artist = artist,
    album = album,
    albumId = albumId,
    trackNumber = trackNumber,
    durationMs = durationMs,
    artworkUrl = artworkUrl,
)
