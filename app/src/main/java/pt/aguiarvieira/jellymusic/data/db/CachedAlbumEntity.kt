package pt.aguiarvieira.jellymusic.data.db

import androidx.room.Entity
import pt.aguiarvieira.jellymusic.domain.model.Album

/**
 * A cached album at a specific [position] within a given [queryKey] (library + sort + order).
 * Storing the server-assigned position is what lets us reproduce the server's sort exactly — even
 * for Random / ratings / the server's default ordering — without re-implementing it in SQL.
 */
@Entity(tableName = "cached_albums", primaryKeys = ["queryKey", "position"])
data class CachedAlbumEntity(
    val queryKey: String,
    val position: Int,
    val albumId: String,
    val name: String,
    val artist: String?,
    val year: Int?,
    val artworkUrl: String?,
)

fun CachedAlbumEntity.toAlbum(): Album = Album(
    id = albumId,
    name = name,
    artist = artist,
    year = year,
    artworkUrl = artworkUrl,
)
