package pt.aguiarvieira.jellymusic.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    // --- Observation (UI) ---

    @Query("SELECT * FROM track_downloads")
    fun observeTracks(): Flow<List<TrackDownloadEntity>>

    @Query("SELECT * FROM album_downloads")
    fun observeAlbums(): Flow<List<AlbumDownloadEntity>>

    @Query("SELECT * FROM track_downloads WHERE state = 'COMPLETED' ORDER BY updatedAt DESC")
    fun observeCompletedTracks(): Flow<List<TrackDownloadEntity>>

    // --- Track rows ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(entity: TrackDownloadEntity)

    @Query("SELECT * FROM track_downloads WHERE trackId = :trackId")
    suspend fun getTrack(trackId: String): TrackDownloadEntity?

    @Query("SELECT * FROM track_downloads WHERE state IN ('QUEUED', 'DOWNLOADING') ORDER BY updatedAt ASC LIMIT 1")
    suspend fun nextPending(): TrackDownloadEntity?

    @Query("SELECT * FROM track_downloads WHERE state = 'COMPLETED'")
    suspend fun completedTracks(): List<TrackDownloadEntity>

    @Query("UPDATE track_downloads SET state = :state, downloadedBytes = :downloaded, totalBytes = :total, filePath = :filePath, updatedAt = :now WHERE trackId = :trackId")
    suspend fun updateProgress(
        trackId: String,
        state: String,
        downloaded: Long,
        total: Long,
        filePath: String?,
        now: Long,
    )

    @Query("DELETE FROM track_downloads WHERE trackId = :trackId")
    suspend fun deleteTrack(trackId: String)

    @Query("DELETE FROM track_downloads WHERE albumId = :albumId")
    suspend fun deleteTracksForAlbum(albumId: String)

    // --- Album rows ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbum(entity: AlbumDownloadEntity)

    @Query("DELETE FROM album_downloads WHERE albumId = :albumId")
    suspend fun deleteAlbum(albumId: String)
}
