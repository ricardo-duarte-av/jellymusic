package pt.aguiarvieira.jellymusic.data.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import pt.aguiarvieira.jellymusic.data.db.CachedAlbumEntity
import pt.aguiarvieira.jellymusic.data.db.JellyMusicDatabase
import pt.aguiarvieira.jellymusic.data.jellyfin.JellyfinClientProvider
import pt.aguiarvieira.jellymusic.data.jellyfin.StreamUrlBuilder
import pt.aguiarvieira.jellymusic.domain.model.AlbumSort
import pt.aguiarvieira.jellymusic.domain.model.MusicLibrary
import java.util.UUID

private const val HTTP_UNAUTHORIZED = 401

/**
 * Keeps the Room album cache for one `queryKey` (library + sort + order) in sync with the server.
 *
 * REFRESH re-fetches from position 0 and, within a transaction, replaces the cached order for this
 * key — so albums added/removed/updated on the server are reconciled on every launch and on every
 * sort/library change. APPEND fetches the next page and extends the order. Because we persist the
 * server-assigned position, Room reproduces the server sort exactly (incl. Random / ratings).
 */
@OptIn(ExperimentalPagingApi::class)
class AlbumRemoteMediator(
    private val clientProvider: JellyfinClientProvider,
    private val urlBuilder: StreamUrlBuilder,
    private val database: JellyMusicDatabase,
    private val libraryId: String?,
    private val sort: AlbumSort,
    private val descending: Boolean,
    private val favoritesOnly: Boolean,
    private val queryKey: String,
) : RemoteMediator<Int, CachedAlbumEntity>() {

    private val dao = database.albumDao()

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, CachedAlbumEntity>,
    ): MediatorResult {
        val startIndex = when (loadType) {
            LoadType.REFRESH -> 0
            LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
            LoadType.APPEND -> (dao.maxPosition(queryKey) ?: -1) + 1
        }

        val api = clientProvider.api
            ?: return MediatorResult.Error(IllegalStateException("Not signed in"))

        return try {
            val result = ItemsApi(api).getItems(
                GetItemsRequest(
                    parentId = libraryId.toParentUuid(),
                    includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                    recursive = true,
                    sortBy = listOf(sort.toItemSortBy()),
                    sortOrder = listOf(if (descending) SortOrder.DESCENDING else SortOrder.ASCENDING),
                    startIndex = startIndex,
                    limit = state.config.pageSize,
                    // UserData carries the favourite flag; also filters to favourites when requested.
                    isFavorite = if (favoritesOnly) true else null,
                    imageTypeLimit = 1,
                    enableImageTypes = listOf(ImageType.PRIMARY),
                ),
            ).content

            val entities = result.items.mapIndexed { offset, dto ->
                CachedAlbumEntity(
                    queryKey = queryKey,
                    position = startIndex + offset,
                    albumId = dto.id.toString(),
                    name = dto.name.orEmpty(),
                    artist = dto.albumArtist ?: dto.artists?.firstOrNull(),
                    year = dto.productionYear,
                    artworkUrl = urlBuilder.imageUrl(dto.id.toString()),
                    isFavorite = dto.userData?.isFavorite == true,
                )
            }

            database.withTransaction {
                // Clear only after the network call succeeds, so cached data stays visible until the
                // fresh page is ready to replace it.
                if (loadType == LoadType.REFRESH) dao.clear(queryKey)
                dao.upsert(entities)
            }

            val endReached =
                result.items.isEmpty() || (startIndex + result.items.size) >= result.totalRecordCount
            MediatorResult.Success(endOfPaginationReached = endReached)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidStatusException) {
            // A rejected token (401) means the session is dead — drop it so the app re-auths.
            if (e.status == HTTP_UNAUTHORIZED) clientProvider.invalidateSession()
            MediatorResult.Error(e)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Standard RemoteMediator contract: surface any load failure as an Error result.
            MediatorResult.Error(e)
        }
    }

    private fun String?.toParentUuid(): UUID? =
        if (this == null || this == MusicLibrary.ALL_ID) null else UUID.fromString(this)
}
