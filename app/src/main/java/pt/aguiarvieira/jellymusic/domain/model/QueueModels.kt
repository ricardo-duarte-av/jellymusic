package pt.aguiarvieira.jellymusic.domain.model

import kotlinx.serialization.Serializable

/** A single queue entry, persisted so the play queue survives process death. */
@Serializable
data class QueueTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val normalizationGainDb: Float? = null,
)

/** A snapshot of the play queue: the ordered items, the current index and position. */
@Serializable
data class PersistedQueue(
    val items: List<QueueTrack>,
    val index: Int,
    val positionMs: Long,
)

/** Rebuild a playable [Track] from a persisted queue entry (missing fields aren't needed to play). */
fun QueueTrack.toTrack(): Track = Track(
    id = id,
    name = title,
    artist = artist.ifEmpty { null },
    album = album,
    albumId = albumId,
    artistId = artistId,
    discNumber = null,
    trackNumber = null,
    durationMs = durationMs,
    artworkUrl = artworkUrl,
    normalizationGainDb = normalizationGainDb,
)
