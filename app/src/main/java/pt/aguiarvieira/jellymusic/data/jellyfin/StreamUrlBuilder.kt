package pt.aguiarvieira.jellymusic.data.jellyfin

import pt.aguiarvieira.jellymusic.domain.model.StreamSettings
import org.jellyfin.sdk.api.operations.ImageApi
import org.jellyfin.sdk.api.operations.UniversalAudioApi
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds Jellyfin URLs for the active session:
 *  - [imageUrl] for album/artist artwork (consumed by Coil).
 *  - [audioStreamUrl] for playback and downloads, driven by server-side transcoding on the universal
 *    audio endpoint (HTTP progressive; direct-play when transcoding is off).
 */
@Singleton
class StreamUrlBuilder @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
) {
    /** Containers we can direct-play, advertised so the server only transcodes when it must. */
    private val supportedContainers = listOf("mp3", "aac", "m4a", "flac", "ogg", "opus", "wav", "webma")

    /**
     * Artwork URL for [itemId]. Capped to [ARTWORK_MAX_PX] on the server so we fetch thumbnail-sized
     * images instead of multi-megabyte originals — the same URL feeds grid cards, list thumbnails,
     * the media notification and Android Auto, none of which need full resolution. The cap is
     * comfortably larger than the full-screen player hero on a phone.
     */
    fun imageUrl(itemId: String, type: ImageType = ImageType.PRIMARY): String? {
        val api = clientProvider.api ?: return null
        return ImageApi(api).getItemImageUrl(
            itemId = itemId.toUuid(),
            imageType = type,
            maxWidth = ARTWORK_MAX_PX,
            maxHeight = ARTWORK_MAX_PX,
            quality = ARTWORK_QUALITY,
        )
    }

    /**
     * Progressive (HTTP) stream URL over the universal endpoint. Used for **downloads**, which save a
     * plain file. When [settings].transcode is off the server direct-plays the original; otherwise it
     * transcodes to the chosen codec/bitrate cap. See [playbackStreamUrl] for in-app playback.
     */
    fun audioStreamUrl(itemId: String, settings: StreamSettings): String? {
        val (bps, codec, container) = transcodeArgs(settings)
        return buildUniversalUrl(itemId, bps, codec, container, MediaStreamProtocol.HTTP)
    }

    /**
     * Stream URL for in-app **playback**. Transcoded playback is requested as HLS — a seekable VOD
     * playlist — because the progressive transcode stream is not byte-range seekable
     * (`Accept-Ranges: none`), so `Player.seekTo` on it is a no-op. Direct play (transcode off) stays
     * progressive: the original file *is* byte-range seekable. The caller must tag transcoded items
     * with the HLS MIME type so ExoPlayer builds an HlsMediaSource.
     */
    fun playbackStreamUrl(itemId: String, settings: StreamSettings): String? {
        val (bps, codec, container) = transcodeArgs(settings)
        val protocol = if (settings.transcode) MediaStreamProtocol.HLS else MediaStreamProtocol.HTTP
        return buildUniversalUrl(itemId, bps, codec, container, protocol)
    }

    /** Transcode (bitrate, codec, container) args, or all-null for direct play. */
    private fun transcodeArgs(settings: StreamSettings): Triple<Int?, String?, String?> =
        if (settings.transcode) {
            Triple(settings.maxBitrateKbps * 1000, settings.codec.jellyfinCodec, settings.codec.container)
        } else {
            Triple(null, null, null)
        }

    private fun buildUniversalUrl(
        itemId: String,
        maxBitrateBps: Int?,
        codec: String?,
        transcodingContainer: String?,
        protocol: MediaStreamProtocol,
    ): String? {
        val session = clientProvider.session.value ?: return null
        val api = clientProvider.api ?: return null
        val url = UniversalAudioApi(api).getUniversalAudioStreamUrl(
            itemId = itemId.toUuid(),
            container = supportedContainers,
            userId = session.userId.toUuid(),
            deviceId = api.deviceInfo.id,
            maxStreamingBitrate = maxBitrateBps,
            transcodingContainer = transcodingContainer,
            transcodingProtocol = protocol,
            audioCodec = codec,
            enableRedirection = true,
        )
        // Audio streaming is authenticated; ExoPlayer fetches the URL directly with no auth header,
        // so make sure the access token rides along as a query param (append only if absent).
        return url.withApiKey(session.accessToken)
    }

    private fun String.withApiKey(token: String): String =
        if (contains("ApiKey=") || contains("api_key=")) {
            this
        } else {
            this + (if (contains("?")) "&" else "?") + "ApiKey=" + token
        }

    private fun String.toUuid(): UUID = UUID.fromString(this)

    private companion object {
        /** Server-side cap for artwork so we download thumbnails, not full-res originals. */
        const val ARTWORK_MAX_PX = 512
        const val ARTWORK_QUALITY = 90
    }
}
