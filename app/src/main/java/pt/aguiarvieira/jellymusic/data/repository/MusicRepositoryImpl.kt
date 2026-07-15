package pt.aguiarvieira.jellymusic.data.repository

import pt.aguiarvieira.jellymusic.data.jellyfin.JellyfinClientProvider
import pt.aguiarvieira.jellymusic.data.jellyfin.StreamUrlBuilder
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.AlbumSort
import pt.aguiarvieira.jellymusic.domain.model.Artist
import pt.aguiarvieira.jellymusic.domain.model.MusicLibrary
import pt.aguiarvieira.jellymusic.domain.model.Playlist
import pt.aguiarvieira.jellymusic.domain.model.SearchResults
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.domain.model.TrackAudioInfo
import pt.aguiarvieira.jellymusic.data.db.JellyMusicDatabase
import pt.aguiarvieira.jellymusic.data.db.toAlbum
import pt.aguiarvieira.jellymusic.data.db.toArtist
import pt.aguiarvieira.jellymusic.data.db.toCached
import pt.aguiarvieira.jellymusic.data.db.toDomainTrack
import pt.aguiarvieira.jellymusic.data.db.toPlaylist
import pt.aguiarvieira.jellymusic.domain.repository.MusicRepository
import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.operations.ArtistsApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.api.operations.PlaylistsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetAlbumArtistsRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetPlaylistItemsRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val ALBUM_PAGE_SIZE = 100
private const val SEARCH_LIMIT = 40
private const val HTTP_UNAUTHORIZED = 401

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

    override suspend fun getArtists(libraryId: String?): Result<List<Artist>> {
        val key = libraryId ?: MusicLibrary.ALL_ID
        val remote = query { api ->
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
        remote.getOrNull()?.let { artists ->
            database.browseCacheDao().replaceArtists(key, artists.map { it.toCached(key) })
            return remote
        }
        // Offline: serve the cached artists for this library.
        val cached = database.browseCacheDao().artistsForLibrary(key).map { it.toArtist() }
        return if (cached.isNotEmpty()) Result.success(cached) else remote
    }

    override suspend fun getPlaylists(libraryId: String?): Result<List<Playlist>> {
        val remote = query { api ->
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
        remote.getOrNull()?.let { playlists ->
            database.browseCacheDao().replacePlaylists(playlists.map { it.toCached() })
            return remote
        }
        val cached = database.browseCacheDao().playlists().map { it.toPlaylist() }
        return if (cached.isNotEmpty()) Result.success(cached) else remote
    }

    override suspend fun getTrackAudioInfo(trackId: String): Result<TrackAudioInfo?> = query { api ->
        val item = ItemsApi(api).getItems(
            GetItemsRequest(
                ids = listOf(UUID.fromString(trackId)),
                fields = listOf(ItemFields.MEDIA_SOURCES),
            ),
        ).content.items.firstOrNull()
        val audio = item?.mediaSources?.firstOrNull()?.mediaStreams
            ?.firstOrNull { it.type == MediaStreamType.AUDIO }
        audio?.let {
            TrackAudioInfo(
                codec = it.codec,
                sampleRateHz = it.sampleRate,
                bitDepth = it.bitDepth,
                bitrateKbps = it.bitRate?.let { bps -> bps / 1000 },
                channels = it.channels,
            )
        }
    }

    override suspend fun getAlbumTracks(albumId: String): Result<List<Track>> {
        val remote = query { api ->
            ItemsApi(api).getItems(
                GetItemsRequest(
                    parentId = UUID.fromString(albumId),
                    includeItemTypes = listOf(BaseItemKind.AUDIO),
                    // MediaSources carries the audio stream's codec/rate/depth, shown on track rows.
                    fields = listOf(ItemFields.MEDIA_SOURCES),
                ),
            ).content.items
                .map { it.toTrack(urlBuilder) }
                .sortedWith(
                    // Disc number first so multi-disc albums order 1-1, 1-2, … 2-1, 2-2 …
                    compareBy(
                        { it.discNumber ?: 0 },
                        { it.trackNumber ?: Int.MAX_VALUE },
                        { it.name },
                    ),
                )
        }
        remote.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return remote }
        // Offline (or server returned nothing): fall back to this album's downloaded tracks.
        val local = database.downloadDao().completedTracksForAlbum(albumId).map { it.toDomainTrack() }
        return if (local.isNotEmpty()) Result.success(local) else remote
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
            GetPlaylistItemsRequest(
                playlistId = UUID.fromString(playlistId),
                fields = listOf(ItemFields.MEDIA_SOURCES),
            ),
        ).content.items.map { it.toTrack(urlBuilder) }
    }

    override suspend fun search(query: String, libraryId: String?): Result<SearchResults> = query { api ->
        val parent = libraryId.toParentUuid()
        // Fire the four type-specific searches concurrently.
        coroutineScope {
            val tracks = async {
                ItemsApi(api).getItems(
                    GetItemsRequest(
                        parentId = parent,
                        includeItemTypes = listOf(BaseItemKind.AUDIO),
                        recursive = true,
                        searchTerm = query,
                        limit = SEARCH_LIMIT,
                        enableUserData = false,
                        imageTypeLimit = 1,
                        enableImageTypes = listOf(ImageType.PRIMARY),
                        fields = listOf(ItemFields.MEDIA_SOURCES),
                    ),
                ).content.items.map { it.toTrack(urlBuilder) }
            }
            val albums = async {
                ItemsApi(api).getItems(
                    GetItemsRequest(
                        parentId = parent,
                        includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                        recursive = true,
                        searchTerm = query,
                        limit = SEARCH_LIMIT,
                        enableUserData = false,
                        imageTypeLimit = 1,
                        enableImageTypes = listOf(ImageType.PRIMARY),
                    ),
                ).content.items.map { it.toAlbum(urlBuilder) }
            }
            val playlists = async {
                ItemsApi(api).getItems(
                    GetItemsRequest(
                        includeItemTypes = listOf(BaseItemKind.PLAYLIST),
                        recursive = true,
                        searchTerm = query,
                        limit = SEARCH_LIMIT,
                        enableUserData = false,
                        imageTypeLimit = 1,
                        enableImageTypes = listOf(ImageType.PRIMARY),
                    ),
                ).content.items.map { it.toPlaylist(urlBuilder) }
            }
            val artists = async {
                ArtistsApi(api).getAlbumArtists(
                    GetAlbumArtistsRequest(
                        parentId = parent,
                        searchTerm = query,
                        limit = SEARCH_LIMIT,
                        enableUserData = false,
                        imageTypeLimit = 1,
                        enableImageTypes = listOf(ImageType.PRIMARY),
                    ),
                ).content.items.map { it.toArtist(urlBuilder) }
            }
            SearchResults(
                tracks = tracks.await(),
                albums = albums.await(),
                artists = artists.await(),
                playlists = playlists.await(),
            )
        }
    }

    private suspend fun <T> query(block: suspend (ApiClient) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            val api = clientProvider.api
                ?: return@withContext Result.failure(IllegalStateException("Not signed in"))
            runCatching { block(api) }.onFailure {
                // A rejected token (401) means the session is dead — drop it so the app re-auths.
                if (it is InvalidStatusException && it.status == HTTP_UNAUTHORIZED) {
                    clientProvider.invalidateSession()
                }
            }
        }

    private fun String?.toParentUuid(): UUID? =
        if (this == null || this == MusicLibrary.ALL_ID) null else UUID.fromString(this)
}
