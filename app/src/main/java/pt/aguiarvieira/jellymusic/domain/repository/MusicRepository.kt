package pt.aguiarvieira.jellymusic.domain.repository

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.AlbumSort
import pt.aguiarvieira.jellymusic.domain.model.Artist
import pt.aguiarvieira.jellymusic.domain.model.Playlist
import pt.aguiarvieira.jellymusic.domain.model.SearchResults
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.domain.model.TrackAudioInfo

/**
 * Reads music content from the active Jellyfin session. A `null` (or "all music") library id means
 * "search across every music library".
 */
interface MusicRepository {
    suspend fun getAlbums(
        libraryId: String?,
        sort: AlbumSort = AlbumSort.DEFAULT,
        descending: Boolean = false,
        favoritesOnly: Boolean = false,
    ): Result<List<Album>>

    /** Paged album stream (first page renders fast); reflects [libraryId]/[sort]/[descending]. */
    fun albumsPager(
        libraryId: String?,
        sort: AlbumSort,
        descending: Boolean,
        favoritesOnly: Boolean = false,
    ): Flow<PagingData<Album>>
    suspend fun getArtists(libraryId: String?, favoritesOnly: Boolean = false): Result<List<Artist>>
    suspend fun getPlaylists(libraryId: String?, favoritesOnly: Boolean = false): Result<List<Playlist>>

    /** Reads the current favourite state of a single item (album/artist/playlist/track). */
    suspend fun getFavorite(itemId: String): Result<Boolean>

    /** Adds or removes [itemId] from the user's Jellyfin favourites. */
    suspend fun setFavorite(itemId: String, favorite: Boolean): Result<Unit>

    /** Original audio-stream details (codec/sample rate/bit depth/bitrate) for a track. */
    suspend fun getTrackAudioInfo(trackId: String): Result<TrackAudioInfo?>

    suspend fun getAlbumTracks(albumId: String): Result<List<Track>>
    suspend fun getArtistAlbums(artistId: String): Result<List<Album>>
    suspend fun getPlaylistTracks(playlistId: String): Result<List<Track>>

    /** Searches tracks/albums/artists/playlists in [libraryId] (or all music) for [query]. */
    suspend fun search(query: String, libraryId: String?): Result<SearchResults>
}
