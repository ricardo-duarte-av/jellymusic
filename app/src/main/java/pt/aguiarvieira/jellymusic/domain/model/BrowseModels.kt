package pt.aguiarvieira.jellymusic.domain.model

data class Album(
    val id: String,
    val name: String,
    val artist: String?,
    val year: Int?,
    val artworkUrl: String?,
    val isFavorite: Boolean = false,
)

data class Artist(
    val id: String,
    val name: String,
    val artworkUrl: String?,
    val isFavorite: Boolean = false,
)

data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int?,
    val artworkUrl: String?,
    val isFavorite: Boolean = false,
)

data class Track(
    val id: String,
    val name: String,
    val artist: String?,
    val album: String?,
    val albumId: String?,
    val discNumber: Int?,
    val trackNumber: Int?,
    val durationMs: Long?,
    val artworkUrl: String?,
    /**
     * Jellyfin's loudness-normalization gain in dB (from its server-side LUFS scan), applied at
     * playback by the ReplayGain audio processor. Negative attenuates loud masters, positive boosts
     * quiet tracks. Null when the server hasn't scanned the item.
     */
    val normalizationGainDb: Float? = null,
    /** Original audio-stream details, populated only when the query requested MediaSources. */
    val audioInfo: TrackAudioInfo? = null,
    /** Whether this item is in the user's Jellyfin favourites (needs a query with UserData). */
    val isFavorite: Boolean = false,
)

/** Combined results of a library search, grouped by content type. */
data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
) {
    val isEmpty: Boolean
        get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}
