package pt.aguiarvieira.jellymusic.data.jellyfin

import pt.aguiarvieira.jellymusic.domain.model.AudioQuality
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
 *  - [audioStreamUrl] / [audioDownloadUrl] for playback and offline, both driven by server-side
 *    transcoding on the universal audio endpoint. Streaming requests HLS (so the server can
 *    transcode on the fly); downloads request a single progressive file.
 *
 * See the "Transcoding" section of the plan.
 */
@Singleton
class StreamUrlBuilder @Inject constructor(
    private val clientProvider: JellyfinClientProvider,
) {
    /** Containers we can direct-play, advertised so the server only transcodes when it must. */
    private val supportedContainers = listOf("mp3", "aac", "m4a", "flac", "ogg", "opus", "wav", "webma")

    fun imageUrl(itemId: String, type: ImageType = ImageType.PRIMARY): String? {
        val api = clientProvider.api ?: return null
        return ImageApi(api).getItemImageUrl(itemId = itemId.toUuid(), imageType = type)
    }

    /**
     * Streaming URL for playback. Uses the universal endpoint over HTTP: the server direct-plays
     * when the source fits [quality], otherwise transcodes to a progressive stream. (HLS is a later
     * refinement for seek-during-transcode; progressive is simpler and reliable for MVP.)
     */
    fun audioStreamUrl(itemId: String, quality: AudioQuality): String? =
        buildUniversalUrl(
            itemId = itemId,
            quality = quality,
            protocol = MediaStreamProtocol.HTTP,
            transcodingContainer = "mp3",
        )

    /** Download URL. Server transcodes to a single progressive file when [quality] is capped. */
    fun audioDownloadUrl(itemId: String, quality: AudioQuality): String? =
        buildUniversalUrl(
            itemId = itemId,
            quality = quality,
            protocol = MediaStreamProtocol.HTTP,
            transcodingContainer = "opus",
        )

    private fun buildUniversalUrl(
        itemId: String,
        quality: AudioQuality,
        protocol: MediaStreamProtocol,
        transcodingContainer: String,
    ): String? {
        val session = clientProvider.session.value ?: return null
        val api = clientProvider.api ?: return null
        val url = UniversalAudioApi(api).getUniversalAudioStreamUrl(
            itemId = itemId.toUuid(),
            container = supportedContainers,
            userId = session.userId.toUuid(),
            deviceId = api.deviceInfo.id,
            maxStreamingBitrate = quality.maxBitrate,
            transcodingContainer = if (quality.isTranscoded) transcodingContainer else null,
            transcodingProtocol = protocol,
            audioCodec = if (quality.isTranscoded) "opus" else null,
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
}
