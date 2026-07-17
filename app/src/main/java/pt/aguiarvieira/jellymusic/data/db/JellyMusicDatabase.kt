package pt.aguiarvieira.jellymusic.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        CachedAlbumEntity::class,
        TrackDownloadEntity::class,
        AlbumDownloadEntity::class,
        PlaylistDownloadEntity::class,
        CachedArtistEntity::class,
        CachedPlaylistEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class JellyMusicDatabase : RoomDatabase() {
    abstract fun albumDao(): AlbumDao
    abstract fun downloadDao(): DownloadDao
    abstract fun browseCacheDao(): BrowseCacheDao
}
