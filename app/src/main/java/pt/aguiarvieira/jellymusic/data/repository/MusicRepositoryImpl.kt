package pt.aguiarvieira.jellymusic.data.repository

import android.util.Log
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
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
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

// Cap for the Android Auto "smart" home rows (Recently Played / Most Played / Recently Added). A car
// browse should surface a handful of quick picks, not the whole catalogue — and it keeps the node
// well under the Binder transaction limit.
private const val SMART_NODE_LIMIT = 100

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
        favoritesOnly: Boolean,
    ): Result<List<Album>> = query { api ->
        ItemsApi(api).getItems(
            GetItemsRequest(
                parentId = libraryId.toParentUuid(),
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                recursive = true,
                sortBy = listOf(sort.toItemSortBy()),
                sortOrder = listOf(if (descending) SortOrder.DESCENDING else SortOrder.ASCENDING),
                // UserData carries the favourite flag shown as a heart on the grid.
                isFavorite = favoritesOnly.orNull(),
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
        favoritesOnly: Boolean,
    ): Flow<PagingData<Album>> {
        val queryKey = "${libraryId ?: MusicLibrary.ALL_ID}|${sort.name}|$descending|fav=$favoritesOnly"
        return Pager(
            config = PagingConfig(pageSize = ALBUM_PAGE_SIZE, enablePlaceholders = false),
            remoteMediator = AlbumRemoteMediator(
                clientProvider, urlBuilder, database, libraryId, sort, descending, favoritesOnly, queryKey,
            ),
            pagingSourceFactory = { database.albumDao().pagingSource(queryKey) },
        ).flow.map { pagingData -> pagingData.map { it.toAlbum() } }
    }

    override suspend fun getRecentlyPlayedAlbums(libraryId: String?): Result<List<Album>> =
        playedAlbums(libraryId, ItemSortBy.DATE_PLAYED)

    override suspend fun getMostPlayedAlbums(libraryId: String?): Result<List<Album>> =
        playedAlbums(libraryId, ItemSortBy.PLAY_COUNT)

    override suspend fun getRecentlyAddedAlbums(libraryId: String?): Result<List<Album>> = query { api ->
        ItemsApi(api).getItems(
            GetItemsRequest(
                parentId = libraryId.toParentUuid(),
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                recursive = true,
                sortBy = listOf(ItemSortBy.DATE_CREATED),
                sortOrder = listOf(SortOrder.DESCENDING),
                limit = SMART_NODE_LIMIT,
                imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY),
            ),
        ).content.items.map { it.toAlbum(urlBuilder) }
    }

    /**
     * Recently-/most-played *albums*, derived from track play history. Jellyfin only marks an *album*
     * as played (and aggregates its PlayCount) once every track is played, so an album-level
     * `IS_PLAYED` filter returns almost nothing. Instead we query the played tracks — where DatePlayed
     * and PlayCount are accurate — and fold them down to their distinct albums, keeping the order the
     * server returned (so the album whose track was played most recently/most often comes first).
     */
    private suspend fun playedAlbums(libraryId: String?, sortBy: ItemSortBy): Result<List<Album>> = query { api ->
        val tracks = ItemsApi(api).getItems(
            GetItemsRequest(
                parentId = libraryId.toParentUuid(),
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                recursive = true,
                sortBy = listOf(sortBy),
                sortOrder = listOf(SortOrder.DESCENDING),
                filters = listOf(ItemFilter.IS_PLAYED),
                // Over-fetch: many tracks collapse into the same album, so ask for well more than the
                // number of distinct albums we want to show.
                limit = SMART_NODE_LIMIT * 4,
                enableUserData = true,
                imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY),
            ),
        ).content.items
        // Fold the played tracks down to their distinct albums (first-seen order), capped for the car.
        tracks.asSequence()
            .filter { it.albumId != null }
            .distinctBy { it.albumId }
            .take(SMART_NODE_LIMIT)
            .map { item ->
                val albumId = item.albumId.toString()
                Album(
                    id = albumId,
                    name = item.album.orEmpty(),
                    artist = item.albumArtist ?: item.artists?.firstOrNull(),
                    year = null,
                    artworkUrl = urlBuilder.imageUrl(albumId),
                    isFavorite = false,
                )
            }
            .toList()
    }

    override suspend fun getArtists(libraryId: String?, favoritesOnly: Boolean): Result<List<Artist>> {
        val key = libraryId ?: MusicLibrary.ALL_ID
        val remote = query { api ->
            ArtistsApi(api).getAlbumArtists(
                GetAlbumArtistsRequest(
                    parentId = libraryId.toParentUuid(),
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.ASCENDING),
                    isFavorite = favoritesOnly.orNull(),
                    imageTypeLimit = 1,
                    enableImageTypes = listOf(ImageType.PRIMARY),
                ),
            ).content.items.map { it.toArtist(urlBuilder) }
        }
        // The favourites view is a transient filter, not the canonical list — don't overwrite the cache.
        if (favoritesOnly) return remote
        remote.getOrNull()?.let { artists ->
            database.browseCacheDao().replaceArtists(key, artists.map { it.toCached(key) })
            return remote
        }
        // Offline: serve the cached artists for this library.
        val cached = database.browseCacheDao().artistsForLibrary(key).map { it.toArtist() }
        return if (cached.isNotEmpty()) Result.success(cached) else remote
    }

    override suspend fun getPlaylists(libraryId: String?, favoritesOnly: Boolean): Result<List<Playlist>> {
        val remote = query { api ->
            // Playlists live in their own view, so we query the whole server rather than a library.
            ItemsApi(api).getItems(
                GetItemsRequest(
                    includeItemTypes = listOf(BaseItemKind.PLAYLIST),
                    recursive = true,
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.ASCENDING),
                    isFavorite = favoritesOnly.orNull(),
                    imageTypeLimit = 1,
                    enableImageTypes = listOf(ImageType.PRIMARY),
                ),
            ).content.items.map { it.toPlaylist(urlBuilder) }
        }
        if (favoritesOnly) return remote
        remote.getOrNull()?.let { playlists ->
            database.browseCacheDao().replacePlaylists(playlists.map { it.toCached() })
            return remote
        }
        val cached = database.browseCacheDao().playlists().map { it.toPlaylist() }
        return if (cached.isNotEmpty()) Result.success(cached) else remote
    }

    override suspend fun getFavoriteTracks(libraryId: String?): Result<List<Track>> = query { api ->
        ItemsApi(api).getItems(
            GetItemsRequest(
                parentId = libraryId.toParentUuid(),
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                recursive = true,
                isFavorite = true,
                // MediaSources so downloaded favourites carry codec/rate/depth like other tracks.
                fields = listOf(ItemFields.MEDIA_SOURCES),
                enableUserData = true,
            ),
        ).content.items.map { it.toTrack(urlBuilder) }
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
                    enableUserData = true,
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
                enableUserData = true,
            ),
        ).content.items.map { it.toAlbum(urlBuilder) }
    }

    override suspend fun getPlaylistTracks(playlistId: String): Result<List<Track>> = query { api ->
        PlaylistsApi(api).getPlaylistItems(
            GetPlaylistItemsRequest(
                playlistId = UUID.fromString(playlistId),
                fields = listOf(ItemFields.MEDIA_SOURCES),
                enableUserData = true,
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
                        enableUserData = true,
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
                        enableUserData = true,
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
                        enableUserData = true,
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
                        enableUserData = true,
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

    override suspend fun getFavorite(itemId: String): Result<Boolean> = query { api ->
        ItemsApi(api).getItems(
            GetItemsRequest(
                ids = listOf(UUID.fromString(itemId)),
                enableUserData = true,
            ),
        ).content.items.firstOrNull()?.userData?.isFavorite == true
    }

    override suspend fun setFavorite(itemId: String, favorite: Boolean): Result<Unit> = query { api ->
        val userLibrary = UserLibraryApi(api)
        val uuid = UUID.fromString(itemId)
        if (favorite) userLibrary.markFavoriteItem(uuid) else userLibrary.unmarkFavoriteItem(uuid)
        Unit
    }

    private suspend fun <T> query(block: suspend (ApiClient) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            val api = clientProvider.api ?: run {
                // No active session — the usual cause of empty browse results when Android Auto starts
                // the service before the app has restored the session.
                Log.d(TAG, "query skipped: no active session (api == null)")
                return@withContext Result.failure(IllegalStateException("Not signed in"))
            }
            runCatching { block(api) }.onFailure {
                Log.d(TAG, "query failed", it)
                // A rejected token (401) means the session is dead — drop it so the app re-auths.
                if (it is InvalidStatusException && it.status == HTTP_UNAUTHORIZED) {
                    clientProvider.invalidateSession()
                }
            }
        }

    private companion object {
        const val TAG = "MusicRepository"
    }

    private fun String?.toParentUuid(): UUID? =
        if (this == null || this == MusicLibrary.ALL_ID) null else UUID.fromString(this)

    // Jellyfin's isFavorite filter is tri-state: true = favourites only, null = no filter.
    private fun Boolean.orNull(): Boolean? = if (this) true else null
}
