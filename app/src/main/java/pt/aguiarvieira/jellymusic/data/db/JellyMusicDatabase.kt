package pt.aguiarvieira.jellymusic.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CachedAlbumEntity::class, TrackDownloadEntity::class, AlbumDownloadEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class JellyMusicDatabase : RoomDatabase() {
    abstract fun albumDao(): AlbumDao
    abstract fun downloadDao(): DownloadDao
}
