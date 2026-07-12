package pt.aguiarvieira.jellymusic.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface BrowseCacheDao {

    // --- Artists (per library) ---

    @Query("SELECT * FROM cached_artists WHERE libraryKey = :libraryKey ORDER BY name COLLATE NOCASE")
    suspend fun artistsForLibrary(libraryKey: String): List<CachedArtistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtists(artists: List<CachedArtistEntity>)

    @Query("DELETE FROM cached_artists WHERE libraryKey = :libraryKey")
    suspend fun clearArtists(libraryKey: String)

    @Transaction
    suspend fun replaceArtists(libraryKey: String, artists: List<CachedArtistEntity>) {
        clearArtists(libraryKey)
        insertArtists(artists)
    }

    // --- Playlists (global) ---

    @Query("SELECT * FROM cached_playlists ORDER BY name COLLATE NOCASE")
    suspend fun playlists(): List<CachedPlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<CachedPlaylistEntity>)

    @Query("DELETE FROM cached_playlists")
    suspend fun clearPlaylists()

    @Transaction
    suspend fun replacePlaylists(playlists: List<CachedPlaylistEntity>) {
        clearPlaylists()
        insertPlaylists(playlists)
    }
}
