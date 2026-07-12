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
)

/** Cached playlist row. Playlists are server-global, so no library key. */
@Entity(tableName = "cached_playlists", primaryKeys = ["id"])
data class CachedPlaylistEntity(
    val id: String,
    val name: String,
    val trackCount: Int?,
    val artworkUrl: String?,
)

fun Artist.toCached(libraryKey: String) = CachedArtistEntity(libraryKey, id, name, artworkUrl)
fun CachedArtistEntity.toArtist() = Artist(id = id, name = name, artworkUrl = artworkUrl)

fun Playlist.toCached() = CachedPlaylistEntity(id, name, trackCount, artworkUrl)
fun CachedPlaylistEntity.toPlaylist() = Playlist(id = id, name = name, trackCount = trackCount, artworkUrl = artworkUrl)
