package pt.aguiarvieira.jellymusic.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.flow.first
import pt.aguiarvieira.jellymusic.data.download.MusicDownloadManager
import pt.aguiarvieira.jellymusic.data.jellyfin.StreamUrlBuilder
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.Artist
import pt.aguiarvieira.jellymusic.domain.model.Playlist
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import pt.aguiarvieira.jellymusic.domain.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the browsable media tree consumed by Android Auto (and any MediaBrowser) and converts
 * domain models into Media3 [MediaItem]s. The tree mirrors the in-app IA:
 *
 * ```
 * root → { Albums, Artists, Playlists }
 *   Albums    → A..Z,# → album/<id>    → track/<id> (playable)
 *   Artists   → A..Z,# → artist/<id>   → album/<id> → track/<id>
 *   Playlists → playlist/<id> → track/<id>
 * ```
 *
 * Albums and Artists get an A–Z index level: Android Auto can't paginate a flat list (it requests
 * every child in one Binder transaction, and a large catalogue overflows the ~1MB transaction
 * limit, surfacing as an empty tab), so each catalogue is split by initial letter to keep every
 * node small and every item reachable. This structure only affects the browse tree consumed by
 * Android Auto / external MediaBrowsers — the in-app UI reads the repository directly.
 */
@Singleton
class MediaItemTree @Inject constructor(
    private val musicRepository: pt.aguiarvieira.jellymusic.domain.repository.MusicRepository,
    private val settingsStore: SettingsStore,
    private val urlBuilder: StreamUrlBuilder,
    private val downloadManager: MusicDownloadManager,
) {
    fun rootItem(): MediaItem = browsable(ROOT_ID, "JellyMusic", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun getChildren(parentId: String): List<MediaItem> = when {
        parentId == ROOT_ID -> listOf(
            browsable(ALBUMS_ID, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            browsable(ARTISTS_ID, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
            browsable(PLAYLISTS_ID, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        )

        parentId == ALBUMS_ID ->
            letterNodes(albums().map { it.name }, ALBUM_LETTER_PREFIX, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)

        parentId == ARTISTS_ID ->
            letterNodes(artists().map { it.name }, ARTIST_LETTER_PREFIX, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)

        parentId.startsWith(ALBUM_LETTER_PREFIX) -> {
            val letter = parentId.removePrefix(ALBUM_LETTER_PREFIX)
            albums().filter { letterOf(it.name) == letter }.map { it.toMediaItem() }
        }

        parentId.startsWith(ARTIST_LETTER_PREFIX) -> {
            val letter = parentId.removePrefix(ARTIST_LETTER_PREFIX)
            artists().filter { letterOf(it.name) == letter }.map { it.toMediaItem() }
        }

        parentId == PLAYLISTS_ID ->
            musicRepository.getPlaylists(libraryId()).getOrDefault(emptyList()).map { it.toMediaItem() }

        parentId.startsWith(ARTIST_PREFIX) ->
            musicRepository.getArtistAlbums(parentId.removePrefix(ARTIST_PREFIX))
                .getOrDefault(emptyList()).map { it.toMediaItem() }

        parentId.startsWith(ALBUM_PREFIX) -> {
            val settings = settingsStore.streamSettings.first()
            musicRepository.getAlbumTracks(parentId.removePrefix(ALBUM_PREFIX))
                .getOrDefault(emptyList()).map { trackMediaItem(it, settings) }
        }

        parentId.startsWith(PLAYLIST_PREFIX) -> {
            val settings = settingsStore.streamSettings.first()
            musicRepository.getPlaylistTracks(parentId.removePrefix(PLAYLIST_PREFIX))
                .getOrDefault(emptyList()).map { trackMediaItem(it, settings) }
        }

        else -> emptyList()
    }

    /** Public so the in-app player ([PlaybackConnection]) builds identical playable items. */
    fun trackMediaItem(track: Track, settings: StreamSettings): MediaItem {
        // Prefer a completed offline copy; fall back to streaming. When local, the item's real format
        // is the download's format, not the current streaming settings.
        val localUri = downloadManager.localFileUri(track.id)
        val isLocal = localUri != null
        val playbackSettings = if (isLocal) downloadManager.localFormat(track.id) ?: settings else settings
        val metadata = MediaMetadata.Builder()
            .setTitle(track.name)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.artworkUrl?.toUri())
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            // Jellyfin knows each track's exact duration; stamp it into the metadata so the seek bar
            // has a correct length from the first frame. A transcoded HTTP-progressive stream reports
            // an unknown timeline duration (TIME_UNSET) until ~15s of buffering, which otherwise left
            // the bar and time labels stuck at 0:00 (and made seeks compute a target of 0).
            .setDurationMs(track.durationMs)
            // Record what's actually playing (format + local/stream) so the UI can report it; this is
            // fixed for the item's lifetime rather than tracking the live settings.
            .setExtras(StreamSettingsExtras.toBundle(playbackSettings, isLocal, track.normalizationGainDb))
            .build()
        // Transcoded playback is served as HLS (seekable); direct play and local files are progressive.
        val transcodeStream = settings.transcode && !isLocal
        return MediaItem.Builder()
            .setMediaId(TRACK_PREFIX + track.id)
            .setUri(localUri ?: urlBuilder.playbackStreamUrl(track.id, settings))
            // Tag transcoded streams so ExoPlayer builds an HlsMediaSource for the .m3u8 playlist
            // instead of trying to play it as a progressive file.
            .apply { if (transcodeStream) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(metadata)
            .build()
    }

    private suspend fun libraryId(): String? = settingsStore.selectedLibrary.first()?.id

    // The A–Z index calls getChildren once for the letter list and again for each opened letter.
    // Cache the (name-sorted) catalogue briefly so navigating between letters doesn't re-download the
    // whole list every tap; a short TTL keeps it fresh enough after library edits.
    private var albumCache: CatalogCache<Album>? = null
    private var artistCache: CatalogCache<Artist>? = null

    private suspend fun albums(): List<Album> {
        val libraryId = libraryId()
        albumCache?.takeIf { it.isFresh(libraryId) }?.let { return it.items }
        val items = musicRepository.getAlbums(libraryId).getOrDefault(emptyList())
        albumCache = CatalogCache(libraryId, items)
        return items
    }

    private suspend fun artists(): List<Artist> {
        val libraryId = libraryId()
        artistCache?.takeIf { it.isFresh(libraryId) }?.let { return it.items }
        val items = musicRepository.getArtists(libraryId).getOrDefault(emptyList())
        artistCache = CatalogCache(libraryId, items)
        return items
    }

    /** Builds the browsable A–Z (plus "#") index nodes for a name-sorted catalogue. */
    private fun letterNodes(names: List<String>, prefix: String, mediaType: Int): List<MediaItem> =
        names.map { letterOf(it) }
            .distinct()
            // A–Z in natural order; the "#" bucket (numbers/symbols) sorts last.
            .sortedWith(compareBy({ it == NON_ALPHA_LETTER }, { it }))
            .map { letter -> browsable(prefix + letter, letter, mediaType) }

    /** First letter of [name], uppercased; anything non-A–Z collapses into the "#" bucket. */
    private fun letterOf(name: String): String {
        val c = name.trimStart().firstOrNull()?.uppercaseChar()
        return if (c != null && c in 'A'..'Z') c.toString() else NON_ALPHA_LETTER
    }

    private class CatalogCache<T>(
        private val libraryId: String?,
        val items: List<T>,
        private val loadedAtMs: Long = System.currentTimeMillis(),
    ) {
        fun isFresh(libraryId: String?): Boolean =
            this.libraryId == libraryId && System.currentTimeMillis() - loadedAtMs < CACHE_TTL_MS
    }

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
        const val ALBUM_LETTER_PREFIX = "album_letter/"
        const val ARTIST_LETTER_PREFIX = "artist_letter/"
        private const val NON_ALPHA_LETTER = "#"
        private const val CACHE_TTL_MS = 60_000L
    }
}
