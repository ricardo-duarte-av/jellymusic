package pt.aguiarvieira.jellymusic.data.repository

import pt.aguiarvieira.jellymusic.data.jellyfin.JellyfinClientProvider
import pt.aguiarvieira.jellymusic.data.jellyfin.StreamUrlBuilder
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.AlbumSort
import pt.aguiarvieira.jellymusic.domain.model.Artist
import pt.aguiarvieira.jellymusic.domain.model.MusicLibrary
import pt.aguiarvieira.jellymusic.domain.model.Playlist
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.domain.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.operations.ArtistsApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.api.operations.PlaylistsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetAlbumArtistsRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetPlaylistItemsRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TICKS_PER_MS = 10_000L

@Singleton
class MusicRepositoryImpl @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
    private val urlBuilder: StreamUrlBuilder,
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
            ),
        ).content.items.map { it.toAlbum() }
    }

    private fun AlbumSort.toItemSortBy(): ItemSortBy = when (this) {
        AlbumSort.ALBUM_ARTIST -> ItemSortBy.ALBUM_ARTIST
        AlbumSort.ID -> ItemSortBy.DEFAULT
        AlbumSort.COMMUNITY_RATING -> ItemSortBy.COMMUNITY_RATING
        AlbumSort.CRITIC_RATING -> ItemSortBy.CRITIC_RATING
        AlbumSort.NAME -> ItemSortBy.SORT_NAME
        AlbumSort.PLAY_COUNT -> ItemSortBy.PLAY_COUNT
        AlbumSort.RANDOM -> ItemSortBy.RANDOM
        AlbumSort.DATE_ADDED -> ItemSortBy.DATE_CREATED
        AlbumSort.DATE_RELEASED -> ItemSortBy.PREMIERE_DATE
    }

    override suspend fun getArtists(libraryId: String?): Result<List<Artist>> = query { api ->
        ArtistsApi(api).getAlbumArtists(
            GetAlbumArtistsRequest(
                parentId = libraryId.toParentUuid(),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
            ),
        ).content.items.map { it.toArtist() }
    }

    override suspend fun getPlaylists(libraryId: String?): Result<List<Playlist>> = query { api ->
        // Playlists live in their own view, so we query the whole server rather than a library.
        ItemsApi(api).getItems(
            GetItemsRequest(
                includeItemTypes = listOf(BaseItemKind.PLAYLIST),
                recursive = true,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
            ),
        ).content.items.map { it.toPlaylist() }
    }

    override suspend fun getAlbumTracks(albumId: String): Result<List<Track>> = query { api ->
        ItemsApi(api).getItems(
            GetItemsRequest(
                parentId = UUID.fromString(albumId),
                includeItemTypes = listOf(BaseItemKind.AUDIO),
            ),
        ).content.items
            .map { it.toTrack() }
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
        ).content.items.map { it.toAlbum() }
    }

    override suspend fun getPlaylistTracks(playlistId: String): Result<List<Track>> = query { api ->
        PlaylistsApi(api).getPlaylistItems(
            GetPlaylistItemsRequest(playlistId = UUID.fromString(playlistId)),
        ).content.items.map { it.toTrack() }
    }

    private suspend fun <T> query(block: suspend (ApiClient) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            val api = clientProvider.api
                ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
            runCatching { block(api) }
        }

    private fun String?.toParentUuid(): UUID? =
        if (this == null || this == MusicLibrary.ALL_ID) null else UUID.fromString(this)

    private fun BaseItemDto.toAlbum() = Album(
        id = id.toString(),
        name = name.orEmpty(),
        artist = albumArtist ?: artists?.firstOrNull(),
        year = productionYear,
        artworkUrl = urlBuilder.imageUrl(id.toString()),
    )

    private fun BaseItemDto.toArtist() = Artist(
        id = id.toString(),
        name = name.orEmpty(),
        artworkUrl = urlBuilder.imageUrl(id.toString()),
    )

    private fun BaseItemDto.toPlaylist() = Playlist(
        id = id.toString(),
        name = name.orEmpty(),
        trackCount = childCount,
        artworkUrl = urlBuilder.imageUrl(id.toString()),
    )

    private fun BaseItemDto.toTrack() = Track(
        id = id.toString(),
        name = name.orEmpty(),
        artist = artists?.firstOrNull() ?: albumArtist,
        album = album,
        albumId = albumId?.toString(),
        trackNumber = indexNumber,
        durationMs = runTimeTicks?.let { it / TICKS_PER_MS },
        artworkUrl = urlBuilder.imageUrl(albumId?.toString() ?: id.toString()),
    )
}
