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
    val discNumber: Int?,
    val trackNumber: Int?,
    val durationMs: Long?,
    val artworkUrl: String?,
    /** Absolute path to the cached cover on disk, once fetched (for offline display). */
    val artworkPath: String? = null,
    /** Download-time transcode choice (independent of the streaming setting). */
    val transcoded: Boolean,
    /** True when the user explicitly downloaded this (track/album/playlist). */
    val manualRequest: Boolean = true,
    /** True when the favourite-sync pulled this in because it's a favourite on the server. */
    val favoriteRequest: Boolean = false,
    val codec: String?,        // AudioCodec.name when transcoded
    val bitrateKbps: Int?,     // when transcoded
    /** Jellyfin LUFS normalization gain (dB), captured at download so ReplayGain works offline. */
    val normalizationGainDb: Float? = null,
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
    /** Absolute path to the cached cover on disk, once fetched (for offline display). */
    val artworkPath: String? = null,
    val totalTracks: Int,
    val transcoded: Boolean,
    val requestedAt: Long,
)

/**
 * A requested playlist download. Unlike an album, a playlist's membership is arbitrary and shared, so
 * the member [trackIds] are stored here (captured at request time) to compute the group's progress
 * and to know which tracks to clean up on removal.
 */
@Entity(tableName = "playlist_downloads")
data class PlaylistDownloadEntity(
    @PrimaryKey val playlistId: String,
    val name: String,
    val artworkUrl: String?,
    /** Absolute path to the cached cover on disk, once fetched (for offline display). */
    val artworkPath: String? = null,
    val totalTracks: Int,
    /** IDs of the tracks that made up the playlist when it was queued. */
    val trackIds: List<String>,
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
    discNumber = discNumber,
    trackNumber = trackNumber,
    durationMs = durationMs,
    // Prefer the cached local cover so downloaded tracks show art offline.
    artworkUrl = artworkPath?.let { "file://$it" } ?: artworkUrl,
    normalizationGainDb = normalizationGainDb,
)
