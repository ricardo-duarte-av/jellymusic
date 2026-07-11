package pt.aguiarvieira.jellymusic.data.repository

import pt.aguiarvieira.jellymusic.data.jellyfin.JellyfinClientProvider
import pt.aguiarvieira.jellymusic.data.jellyfin.StreamUrlBuilder
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.AlbumSort
import pt.aguiarvieira.jellymusic.domain.model.Artist
import pt.aguiarvieira.jellymusic.domain.model.MusicLibrary
import pt.aguiarvieira.jellymusic.domain.model.Playlist
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.data.db.JellyMusicDatabase
import pt.aguiarvieira.jellymusic.data.db.toAlbum
import pt.aguiarvieira.jellymusic.domain.repository.MusicRepository
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.operations.ArtistsApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.api.operations.PlaylistsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetAlbumArtistsRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetPlaylistItemsRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val ALBUM_PAGE_SIZE = 100

@Singleton
class MusicRepositoryImpl @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
    private val urlBuilder: StreamUrlBuilder,
    private val database: JellyMusicDatabase,
) : MusicRepository {

    override suspend fun getAlbums(
        libraryId: String?,
        sort: AlbumSort,
        descending: Boolean,
    ): Result<List<Album>> = query { api ->
        ItemsApi(api).getItems(
            GetItemsRequest(
                parentId = libraryId.toParentUuid(),
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                recursive = true,
                sortBy = listOf(sort.toItemSortBy()),
                sortOrder = listOf(if (descending) SortOrder.DESCENDING else SortOrder.ASCENDING),
                // Trim the payload to what the grid shows (drops user-data + extra image tags).
                enableUserData = false,
                imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY),
            ),
        ).content.items.map { it.toAlbum(urlBuilder) }
    }

    @OptIn(ExperimentalPagingApi::class)
    override fun albumsPager(
        libraryId: String?,
        sort: AlbumSort,
        descending: Boolean,
    ): Flow<PagingData<Album>> {
        val queryKey = "${libraryId ?: MusicLibrary.ALL_ID}|${sort.name}|$descending"
        return Pager(
            config = PagingConfig(pageSize = ALBUM_PAGE_SIZE, enablePlaceholders = false),
            remoteMediator = AlbumRemoteMediator(
                clientProvider, urlBuilder, database, libraryId, sort, descending, queryKey,
            ),
            pagingSourceFactory = { database.albumDao().pagingSource(queryKey) },
        ).flow.map { pagingData -> pagingData.map { it.toAlbum() } }
    }

    override suspend fun getArtists(libraryId: String?): Result<List<Artist>> = query { api ->
        ArtistsApi(api).getAlbumArtists(
            GetAlbumArtistsRequest(
                parentId = libraryId.toParentUuid(),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                enableUserData = false,
                imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY),
            ),
        ).content.items.map { it.toArtist(urlBuilder) }
    }

    override suspend fun getPlaylists(libraryId: String?): Result<List<Playlist>> = query { api ->
        // Playlists live in their own view, so we query the whole server rather than a library.
        ItemsApi(api).getItems(
            GetItemsRequest(
                includeItemTypes = listOf(BaseItemKind.PLAYLIST),
                recursive = true,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                enableUserData = false,
                imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY),
            ),
        ).content.items.map { it.toPlaylist(urlBuilder) }
    }

    override suspend fun getAlbumTracks(albumId: String): Result<List<Track>> = query { api ->
        ItemsApi(api).getItems(
            GetItemsRequest(
                parentId = UUID.fromString(albumId),
                includeItemTypes = listOf(BaseItemKind.AUDIO),
            ),
        ).content.items
            .map { it.toTrack(urlBuilder) }
            .sortedWith(compareBy({ it.trackNumber ?: Int.MAX_VALUE }, { it.name }))
    }

    override suspend fun getArtistAlbums(artistId: String): Result<List<Album>> = query { api ->
        ItemsApi(api).getItems(
            GetItemsRequest(
                albumArtistIds = listOf(UUID.fromString(artistId)),
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                recursive = true,
                sortBy = listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.DESCENDING),
            ),
        ).content.items.map { it.toAlbum(urlBuilder) }
    }

    override suspend fun getPlaylistTracks(playlistId: String): Result<List<Track>> = query { api ->
        PlaylistsApi(api).getPlaylistItems(
            GetPlaylistItemsRequest(playlistId = UUID.fromString(playlistId)),
        ).content.items.map { it.toTrack(urlBuilder) }
    }

    private suspend fun <T> query(block: suspend (ApiClient) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            val api = clientProvider.api
                ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
            runCatching { block(api) }
        }

    private fun String?.toParentUuid(): UUID? =
        if (this == null || this == MusicLibrary.ALL_ID) null else UUID.fromString(this)
}
