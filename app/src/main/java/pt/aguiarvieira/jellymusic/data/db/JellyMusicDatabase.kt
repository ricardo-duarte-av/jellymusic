package pt.aguiarvieira.jellymusic.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedAlbumEntity::class,
        TrackDownloadEntity::class,
        AlbumDownloadEntity::class,
        CachedArtistEntity::class,
        CachedPlaylistEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class JellyMusicDatabase : RoomDatabase() {
    abstract fun albumDao(): AlbumDao
    abstract fun downloadDao(): DownloadDao
    abstract fun browseCacheDao(): BrowseCacheDao
}
