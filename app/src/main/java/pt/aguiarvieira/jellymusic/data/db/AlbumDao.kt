package pt.aguiarvieira.jellymusic.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface AlbumDao {

    /** Paged, ordered by the server-assigned position — the source of truth for the grid. */
    @Query("SELECT * FROM cached_albums WHERE queryKey = :queryKey ORDER BY position ASC")
    fun pagingSource(queryKey: String): PagingSource<Int, CachedAlbumEntity>

    @Upsert
    suspend fun upsert(albums: List<CachedAlbumEntity>)

    @Query("DELETE FROM cached_albums WHERE queryKey = :queryKey")
    suspend fun clear(queryKey: String)

    @Query("SELECT MAX(position) FROM cached_albums WHERE queryKey = :queryKey")
    suspend fun maxPosition(queryKey: String): Int?
}
