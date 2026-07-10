package pt.aguiarvieira.jellymusic.domain.repository

import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.Artist
import pt.aguiarvieira.jellymusic.domain.model.Playlist
import pt.aguiarvieira.jellymusic.domain.model.Track

/**
 * Reads music content from the active Jellyfin session. A `null` (or "all music") library id means
 * "search across every music library".
 */
interface MusicRepository {
    suspend fun getAlbums(libraryId: String?): Result<List<Album>>
    suspend fun getArtists(libraryId: String?): Result<List<Artist>>
    suspend fun getPlaylists(libraryId: String?): Result<List<Playlist>>

    suspend fun getAlbumTracks(albumId: String): Result<List<Track>>
    suspend fun getArtistAlbums(artistId: String): Result<List<Album>>
    suspend fun getPlaylistTracks(playlistId: String): Result<List<Track>>
}
