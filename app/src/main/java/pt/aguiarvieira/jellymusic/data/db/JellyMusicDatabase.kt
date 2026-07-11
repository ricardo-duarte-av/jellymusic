package pt.aguiarvieira.jellymusic.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CachedAlbumEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class JellyMusicDatabase : RoomDatabase() {
    abstract fun albumDao(): AlbumDao
}
