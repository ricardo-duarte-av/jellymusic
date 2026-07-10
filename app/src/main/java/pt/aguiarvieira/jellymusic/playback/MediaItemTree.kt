package pt.aguiarvieira.jellymusic.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.first
import pt.aguiarvieira.jellymusic.data.jellyfin.StreamUrlBuilder
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.Artist
import pt.aguiarvieira.jellymusic.domain.model.AudioQuality
import pt.aguiarvieira.jellymusic.domain.model.Playlist
import pt.aguiarvieira.jellymusic.domain.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the browsable media tree consumed by Android Auto (and any MediaBrowser) and converts
 * domain models into Media3 [MediaItem]s. The tree mirrors the in-app IA:
 *
 * ```
 * root → { Albums, Artists, Playlists }
 *   Albums    → album/<id>    → track/<id> (playable)
 *   Artists   → artist/<id>   → album/<id> → track/<id>
 *   Playlists → playlist/<id> → track/<id>
 * ```
 */
@Singleton
class MediaItemTree @Inject constructor(
    private val musicRepository: pt.aguiarvieira.jellymusic.domain.repository.MusicRepository,
    private val settingsStore: SettingsStore,
    private val urlBuilder: StreamUrlBuilder,
) {
    fun rootItem(): MediaItem = browsable(ROOT_ID, "JellyMusic", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun getChildren(parentId: String): List<MediaItem> = when {
        parentId == ROOT_ID -> listOf(
            browsable(ALBUMS_ID, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            browsable(ARTISTS_ID, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
            browsable(PLAYLISTS_ID, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        )

        parentId == ALBUMS_ID ->
            musicRepository.getAlbums(libraryId()).getOrDefault(emptyList()).map { it.toMediaItem() }

        parentId == ARTISTS_ID ->
            musicRepository.getArtists(libraryId()).getOrDefault(emptyList()).map { it.toMediaItem() }

        parentId == PLAYLISTS_ID ->
            musicRepository.getPlaylists(libraryId()).getOrDefault(emptyList()).map { it.toMediaItem() }

        parentId.startsWith(ARTIST_PREFIX) ->
            musicRepository.getArtistAlbums(parentId.removePrefix(ARTIST_PREFIX))
                .getOrDefault(emptyList()).map { it.toMediaItem() }

        parentId.startsWith(ALBUM_PREFIX) -> {
            val quality = settingsStore.streamingQuality.first()
            musicRepository.getAlbumTracks(parentId.removePrefix(ALBUM_PREFIX))
                .getOrDefault(emptyList()).map { trackMediaItem(it, quality) }
        }

        parentId.startsWith(PLAYLIST_PREFIX) -> {
            val quality = settingsStore.streamingQuality.first()
            musicRepository.getPlaylistTracks(parentId.removePrefix(PLAYLIST_PREFIX))
                .getOrDefault(emptyList()).map { trackMediaItem(it, quality) }
        }

        else -> emptyList()
    }

    /** Public so the in-app player ([PlaybackConnection]) builds identical playable items. */
    fun trackMediaItem(track: Track, quality: AudioQuality): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.name)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.artworkUrl?.toUri())
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            .setMediaId(TRACK_PREFIX + track.id)
            .setUri(urlBuilder.audioStreamUrl(track.id, quality))
            .setMediaMetadata(metadata)
            .build()
    }

    private suspend fun libraryId(): String? = settingsStore.selectedLibrary.first()?.id

    private fun Album.toMediaItem() = browsable(
        id = ALBUM_PREFIX + id,
        title = name,
        mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
        subtitle = artist,
        artworkUri = artworkUrl?.toUri(),
    )

    private fun Artist.toMediaItem() = browsable(
        id = ARTIST_PREFIX + id,
        title = name,
        mediaType = MediaMetadata.MEDIA_TYPE_ARTIST,
        artworkUri = artworkUrl?.toUri(),
    )

    private fun Playlist.toMediaItem() = browsable(
        id = PLAYLIST_PREFIX + id,
        title = name,
        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
        artworkUri = artworkUrl?.toUri(),
    )

    private fun browsable(
        id: String,
        title: String,
        mediaType: Int,
        subtitle: String? = null,
        artworkUri: Uri? = null,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUri)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    private fun String.toUri(): Uri = Uri.parse(this)

    companion object {
        const val ROOT_ID = "root"
        const val ALBUMS_ID = "albums"
        const val ARTISTS_ID = "artists"
        const val PLAYLISTS_ID = "playlists"
        const val TRACK_PREFIX = "track/"
        const val ALBUM_PREFIX = "album/"
        const val ARTIST_PREFIX = "artist/"
        const val PLAYLIST_PREFIX = "playlist/"
    }
}
