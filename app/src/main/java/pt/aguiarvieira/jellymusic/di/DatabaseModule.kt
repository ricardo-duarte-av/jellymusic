package pt.aguiarvieira.jellymusic.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import pt.aguiarvieira.jellymusic.data.db.AlbumDao
import pt.aguiarvieira.jellymusic.data.db.BrowseCacheDao
import pt.aguiarvieira.jellymusic.data.db.DownloadDao
import pt.aguiarvieira.jellymusic.data.db.JellyMusicDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    // v3 adds discNumber to track_downloads; migrate in place so existing downloads survive.
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE track_downloads ADD COLUMN discNumber INTEGER")
        }
    }

    // v4 adds cached-artwork paths to both download tables.
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE track_downloads ADD COLUMN artworkPath TEXT")
            db.execSQL("ALTER TABLE album_downloads ADD COLUMN artworkPath TEXT")
        }
    }

    // v5 adds artist/playlist browse caches.
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `cached_artists` (`libraryKey` TEXT NOT NULL, " +
                    "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `artworkUrl` TEXT, " +
                    "PRIMARY KEY(`libraryKey`, `id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `cached_playlists` (`id` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, `trackCount` INTEGER, `artworkUrl` TEXT, PRIMARY KEY(`id`))",
            )
        }
    }

    // v6 adds the per-track LUFS normalization gain (ReplayGain) to track_downloads.
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE track_downloads ADD COLUMN normalizationGainDb REAL")
        }
    }

    // v7 adds the favourite flag to the browse caches (albums/artists/playlists).
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE cached_albums ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE cached_artists ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE cached_playlists ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
        }
    }

    // v8 adds the playlist_downloads grouping table (mirrors album_downloads).
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlist_downloads` (`playlistId` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, `artworkUrl` TEXT, `artworkPath` TEXT, " +
                    "`totalTracks` INTEGER NOT NULL, `trackIds` TEXT NOT NULL, " +
                    "`transcoded` INTEGER NOT NULL, `requestedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`playlistId`))",
            )
        }
    }

    // v9 adds manual/favourite request flags to track_downloads so favourite auto-downloads can be
    // told apart from manual ones (existing downloads are all manual).
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE track_downloads ADD COLUMN manualRequest INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE track_downloads ADD COLUMN favoriteRequest INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): JellyMusicDatabase =
        Room.databaseBuilder(context, JellyMusicDatabase::class.java, "jellymusic.db")
            .addMigrations(
                MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                MIGRATION_8_9,
            )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideAlbumDao(database: JellyMusicDatabase): AlbumDao = database.albumDao()

    @Provides
    fun provideDownloadDao(database: JellyMusicDatabase): DownloadDao = database.downloadDao()

    @Provides
    fun provideBrowseCacheDao(database: JellyMusicDatabase): BrowseCacheDao = database.browseCacheDao()
}
