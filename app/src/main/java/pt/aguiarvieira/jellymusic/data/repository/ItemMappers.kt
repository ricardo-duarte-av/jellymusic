package pt.aguiarvieira.jellymusic.data.repository

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaStreamType
import pt.aguiarvieira.jellymusic.data.jellyfin.StreamUrlBuilder
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.AlbumSort
import pt.aguiarvieira.jellymusic.domain.model.Artist
import pt.aguiarvieira.jellymusic.domain.model.Playlist
import pt.aguiarvieira.jellymusic.domain.model.Track
import pt.aguiarvieira.jellymusic.domain.model.TrackAudioInfo

private const val TICKS_PER_MS = 10_000L

/** This item's own primary-image tag, so the artwork URL changes when the cover is re-tagged. */
private val BaseItemDto.primaryImageTag: String?
    get() = imageTags?.get(ImageType.PRIMARY)

/** Shared Jellyfin DTO → domain mappers, used by both the repository and the album PagingSource. */
internal fun BaseItemDto.toAlbum(urlBuilder: StreamUrlBuilder) = Album(
    id = id.toString(),
    name = name.orEmpty(),
    artist = albumArtist ?: artists?.firstOrNull(),
    year = productionYear,
    artworkUrl = urlBuilder.imageUrl(id.toString(), tag = primaryImageTag),
    isFavorite = userData?.isFavorite == true,
)

internal fun BaseItemDto.toArtist(urlBuilder: StreamUrlBuilder) = Artist(
    id = id.toString(),
    name = name.orEmpty(),
    artworkUrl = urlBuilder.imageUrl(id.toString(), tag = primaryImageTag),
    isFavorite = userData?.isFavorite == true,
)

internal fun BaseItemDto.toPlaylist(urlBuilder: StreamUrlBuilder) = Playlist(
    id = id.toString(),
    name = name.orEmpty(),
    trackCount = childCount,
    artworkUrl = urlBuilder.imageUrl(id.toString(), tag = primaryImageTag),
    isFavorite = userData?.isFavorite == true,
)

internal fun BaseItemDto.toTrack(urlBuilder: StreamUrlBuilder) = Track(
    id = id.toString(),
    name = name.orEmpty(),
    artist = artists?.firstOrNull() ?: albumArtist,
    album = album,
    albumId = albumId?.toString(),
    // Jellyfin exposes the disc number as parentIndexNumber for audio tracks.
    discNumber = parentIndexNumber,
    trackNumber = indexNumber,
    durationMs = runTimeTicks?.let { it / TICKS_PER_MS },
    // Track artwork is the album's cover: use the album id + its primary-image tag so the URL
    // changes when the album cover is re-tagged. Fall back to the track's own image if album-less.
    artworkUrl = albumId?.let { urlBuilder.imageUrl(it.toString(), tag = albumPrimaryImageTag) }
        ?: urlBuilder.imageUrl(id.toString(), tag = primaryImageTag),
    normalizationGainDb = normalizationGain,
    isFavorite = userData?.isFavorite == true,
    // Populated only when the query requested MediaSources (e.g. album/playlist/search track lists).
    audioInfo = mediaSources?.firstOrNull()?.mediaStreams
        ?.firstOrNull { it.type == MediaStreamType.AUDIO }
        ?.let {
            TrackAudioInfo(
                codec = it.codec,
                sampleRateHz = it.sampleRate,
                bitDepth = it.bitDepth,
                bitrateKbps = it.bitRate?.let { bps -> bps / 1000 },
                channels = it.channels,
            )
        },
)

internal fun AlbumSort.toItemSortBy(): ItemSortBy = when (this) {
    AlbumSort.ALBUM_ARTIST -> ItemSortBy.ALBUM_ARTIST
    AlbumSort.ID -> ItemSortBy.DEFAULT
    AlbumSort.COMMUNITY_RATING -> ItemSortBy.COMMUNITY_RATING
    AlbumSort.CRITIC_RATING -> ItemSortBy.CRITIC_RATING
    AlbumSort.NAME -> ItemSortBy.SORT_NAME
    AlbumSort.PLAY_COUNT -> ItemSortBy.PLAY_COUNT
    AlbumSort.RANDOM -> ItemSortBy.RANDOM
    AlbumSort.DATE_ADDED -> ItemSortBy.DATE_CREATED
    AlbumSort.DATE_RELEASED -> ItemSortBy.PREMIERE_DATE
}
