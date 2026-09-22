package pt.aguiarvieira.jellymusic.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * An album's Jellyfin LUFS normalization gain (album ReplayGain), cached so album-gain playback works
 * offline and doesn't wait on the network once an album has been seen. A row with a null [gainDb]
 * records that the server has no album gain for it, so it isn't re-fetched on every queue change.
 */
@Entity(tableName = "album_gains")
data class AlbumGainEntity(
    @PrimaryKey val albumId: String,
    val gainDb: Float?,
)

@Dao
interface AlbumGainDao {
    @Query("SELECT * FROM album_gains")
    suspend fun all(): List<AlbumGainEntity>

    @Upsert
    suspend fun upsert(gains: List<AlbumGainEntity>)
}
