package pt.aguiarvieira.jellymusic.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import pt.aguiarvieira.jellymusic.data.jellyfin.JellyfinClientProvider
import pt.aguiarvieira.jellymusic.data.jellyfin.StreamUrlBuilder
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.AlbumSort
import pt.aguiarvieira.jellymusic.domain.model.MusicLibrary
import java.util.UUID

/**
 * Loads albums one page at a time via Jellyfin's `startIndex`/`limit`, so the first page renders
 * quickly regardless of library size. The [key] is the next item's start index.
 */
class AlbumPagingSource(
    private val clientProvider: JellyfinClientProvider,
    private val urlBuilder: StreamUrlBuilder,
    private val libraryId: String?,
    private val sort: AlbumSort,
    private val descending: Boolean,
) : PagingSource<Int, Album>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Album> {
        val api = clientProvider.api
            ?: return LoadResult.Error(IllegalStateException("Not signed in"))
        val startIndex = params.key ?: 0
        return try {
            val result = ItemsApi(api).getItems(
                GetItemsRequest(
                    parentId = libraryId.toParentUuid(),
                    includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                    recursive = true,
                    sortBy = listOf(sort.toItemSortBy()),
                    sortOrder = listOf(if (descending) SortOrder.DESCENDING else SortOrder.ASCENDING),
                    startIndex = startIndex,
                    limit = params.loadSize,
                    enableUserData = false,
                    imageTypeLimit = 1,
                    enableImageTypes = listOf(ImageType.PRIMARY),
                ),
            ).content
            val albums = result.items.map { it.toAlbum(urlBuilder) }
            val nextStart = startIndex + albums.size
            LoadResult.Page(
                data = albums,
                prevKey = if (startIndex == 0) null else (startIndex - params.loadSize).coerceAtLeast(0),
                nextKey = if (albums.isEmpty() || nextStart >= result.totalRecordCount) null else nextStart,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Album>): Int? =
        state.anchorPosition?.let { anchor ->
            val closest = state.closestPageToPosition(anchor)
            closest?.prevKey?.plus(state.config.pageSize)
                ?: closest?.nextKey?.minus(state.config.pageSize)
        }

    private fun String?.toParentUuid(): UUID? =
        if (this == null || this == MusicLibrary.ALL_ID) null else UUID.fromString(this)
}
