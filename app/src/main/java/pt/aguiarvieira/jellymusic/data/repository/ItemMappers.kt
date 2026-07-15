package pt.aguiarvieira.jellymusic.data.repository

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemSortBy
import pt.aguiarvieira.jellymusic.data.jellyfin.StreamUrlBuilder
import pt.aguiarvieira.jellymusic.domain.model.Album
import pt.aguiarvieira.jellymusic.domain.model.AlbumSort
import pt.aguiarvieira.jellymusic.domain.model.Artist
import pt.aguiarvieira.jellymusic.domain.model.Playlist
import pt.aguiarvieira.jellymusic.domain.model.Track

private const val TICKS_PER_MS = 10_000L

/** Shared Jellyfin DTO → domain mappers, used by both the repository and the album PagingSource. */
internal fun BaseItemDto.toAlbum(urlBuilder: StreamUrlBuilder) = Album(
    id = id.toString(),
    name = name.orEmpty(),
    artist = albumArtist ?: artists?.firstOrNull(),
    year = productionYear,
    artworkUrl = urlBuilder.imageUrl(id.toString()),
)

internal fun BaseItemDto.toArtist(urlBuilder: StreamUrlBuilder) = Artist(
    id = id.toString(),
    name = name.orEmpty(),
    artworkUrl = urlBuilder.imageUrl(id.toString()),
)

internal fun BaseItemDto.toPlaylist(urlBuilder: StreamUrlBuilder) = Playlist(
    id = id.toString(),
    name = name.orEmpty(),
    trackCount = childCount,
    artworkUrl = urlBuilder.imageUrl(id.toString()),
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
    artworkUrl = urlBuilder.imageUrl(albumId?.toString() ?: id.toString()),
    normalizationGainDb = normalizationGain,
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
