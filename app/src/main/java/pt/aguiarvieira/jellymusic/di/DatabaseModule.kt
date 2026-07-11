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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): JellyMusicDatabase =
        Room.databaseBuilder(context, JellyMusicDatabase::class.java, "jellymusic.db")
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideAlbumDao(database: JellyMusicDatabase): AlbumDao = database.albumDao()

    @Provides
    fun provideDownloadDao(database: JellyMusicDatabase): DownloadDao = database.downloadDao()
}
