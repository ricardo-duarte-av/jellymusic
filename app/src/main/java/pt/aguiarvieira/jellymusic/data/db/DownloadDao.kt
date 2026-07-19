package pt.aguiarvieira.jellymusic.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// A Room DAO naturally accrues one function per query across tracks/albums/playlists.
@Suppress("TooManyFunctions")
@Dao
interface DownloadDao {

    // --- Observation (UI) ---

    @Query("SELECT * FROM track_downloads")
    fun observeTracks(): Flow<List<TrackDownloadEntity>>

    @Query("SELECT * FROM album_downloads")
    fun observeAlbums(): Flow<List<AlbumDownloadEntity>>

    @Query("SELECT * FROM playlist_downloads")
    fun observePlaylists(): Flow<List<PlaylistDownloadEntity>>

    @Query("SELECT * FROM track_downloads WHERE state = 'COMPLETED' ORDER BY updatedAt DESC")
    fun observeCompletedTracks(): Flow<List<TrackDownloadEntity>>

    // --- Track rows ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(entity: TrackDownloadEntity)

    @Query("SELECT * FROM track_downloads WHERE trackId = :trackId")
    suspend fun getTrack(trackId: String): TrackDownloadEntity?

    @Query(
        "SELECT * FROM track_downloads WHERE state IN ('QUEUED', 'DOWNLOADING') AND manualRequest = 1 " +
            "ORDER BY updatedAt ASC LIMIT 1",
    )
    suspend fun nextPending(): TrackDownloadEntity?

    /**
     * Next favourite-only pending track (a manual+favourite track is drained by [nextPending] on any
     * network, which is correct — it's a manual download). Used by the Wi-Fi-gated favourite worker.
     */
    @Query(
        "SELECT * FROM track_downloads WHERE state IN ('QUEUED', 'DOWNLOADING') " +
            "AND favoriteRequest = 1 AND manualRequest = 0 ORDER BY updatedAt ASC LIMIT 1",
    )
    suspend fun nextPendingFavorite(): TrackDownloadEntity?

    /** Every track claimed by favourite-sync, for reconciling against the server's favourites. */
    @Query("SELECT * FROM track_downloads WHERE favoriteRequest = 1")
    suspend fun favoriteDownloads(): List<TrackDownloadEntity>

    @Query("UPDATE track_downloads SET favoriteRequest = :favorite WHERE trackId = :trackId")
    suspend fun setFavoriteRequest(trackId: String, favorite: Boolean)

    /**
     * Re-queue favourite-only downloads that previously failed, so a later sync retries them. Scoped
     * to favourite-only rows (a manual+favourite track is retried through the manual flow).
     */
    @Query(
        "UPDATE track_downloads SET state = 'QUEUED', downloadedBytes = 0, updatedAt = :now " +
            "WHERE state = 'FAILED' AND favoriteRequest = 1 AND manualRequest = 0",
    )
    suspend fun requeueFailedFavorites(now: Long)

    @Query("SELECT * FROM track_downloads WHERE state = 'COMPLETED'")
    suspend fun completedTracks(): List<TrackDownloadEntity>

    @Query(
        "SELECT * FROM track_downloads WHERE albumId = :albumId AND state = 'COMPLETED' " +
            "ORDER BY discNumber, trackNumber",
    )
    suspend fun completedTracksForAlbum(albumId: String): List<TrackDownloadEntity>

    @Query(
        """
        UPDATE track_downloads
        SET state = :state, downloadedBytes = :downloaded, totalBytes = :total,
            filePath = :filePath, updatedAt = :now
        WHERE trackId = :trackId
        """,
    )
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

    @Query("SELECT albumId FROM album_downloads")
    suspend fun downloadedAlbumIds(): List<String>

    // --- Playlist rows ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(entity: PlaylistDownloadEntity)

    @Query("SELECT * FROM playlist_downloads WHERE playlistId = :playlistId")
    suspend fun getPlaylist(playlistId: String): PlaylistDownloadEntity?

    @Query("SELECT * FROM playlist_downloads")
    suspend fun playlists(): List<PlaylistDownloadEntity>

    @Query("DELETE FROM playlist_downloads WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: String)
}
