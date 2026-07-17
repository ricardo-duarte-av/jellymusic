package pt.aguiarvieira.jellymusic.data.db

import androidx.room.Entity
import pt.aguiarvieira.jellymusic.domain.model.Artist
import pt.aguiarvieira.jellymusic.domain.model.Playlist

/** Cached artist row, keyed by (library, id) since an artist can appear in multiple libraries. */
@Entity(tableName = "cached_artists", primaryKeys = ["libraryKey", "id"])
data class CachedArtistEntity(
    val libraryKey: String,
    val id: String,
    val name: String,
    val artworkUrl: String?,
    val isFavorite: Boolean = false,
)

/** Cached playlist row. Playlists are server-global, so no library key. */
@Entity(tableName = "cached_playlists", primaryKeys = ["id"])
data class CachedPlaylistEntity(
    val id: String,
    val name: String,
    val trackCount: Int?,
    val artworkUrl: String?,
    val isFavorite: Boolean = false,
)

fun Artist.toCached(libraryKey: String) = CachedArtistEntity(libraryKey, id, name, artworkUrl, isFavorite)
fun CachedArtistEntity.toArtist() = Artist(id = id, name = name, artworkUrl = artworkUrl, isFavorite = isFavorite)

fun Playlist.toCached() = CachedPlaylistEntity(id, name, trackCount, artworkUrl, isFavorite)
fun CachedPlaylistEntity.toPlaylist() =
    Playlist(id = id, name = name, trackCount = trackCount, artworkUrl = artworkUrl, isFavorite = isFavorite)
