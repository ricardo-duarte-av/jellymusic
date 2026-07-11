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
    ): Result<List<Album>>

    /** Paged album stream (first page renders fast); reflects [libraryId]/[sort]/[descending]. */
    fun albumsPager(
        libraryId: String?,
        sort: AlbumSort,
        descending: Boolean,
    ): Flow<PagingData<Album>>
    suspend fun getArtists(libraryId: String?): Result<List<Artist>>
    suspend fun getPlaylists(libraryId: String?): Result<List<Playlist>>

    /** Original audio-stream details (codec/sample rate/bit depth/bitrate) for a track. */
    suspend fun getTrackAudioInfo(trackId: String): Result<TrackAudioInfo?>

    suspend fun getAlbumTracks(albumId: String): Result<List<Track>>
    suspend fun getArtistAlbums(artistId: String): Result<List<Album>>
    suspend fun getPlaylistTracks(playlistId: String): Result<List<Track>>

    /** Searches tracks/albums/artists/playlists in [libraryId] (or all music) for [query]. */
    suspend fun search(query: String, libraryId: String?): Result<SearchResults>
}
