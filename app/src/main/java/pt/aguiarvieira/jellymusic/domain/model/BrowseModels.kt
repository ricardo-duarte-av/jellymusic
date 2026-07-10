package pt.aguiarvieira.jellymusic.domain.model

data class Album(
    val id: String,
    val name: String,
    val artist: String?,
    val year: Int?,
    val artworkUrl: String?,
)

data class Artist(
    val id: String,
    val name: String,
    val artworkUrl: String?,
)

data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int?,
    val artworkUrl: String?,
)

data class Track(
    val id: String,
    val name: String,
    val artist: String?,
    val album: String?,
    val albumId: String?,
    val trackNumber: Int?,
    val durationMs: Long?,
    val artworkUrl: String?,
)
