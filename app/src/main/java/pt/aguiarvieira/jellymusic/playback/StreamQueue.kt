package pt.aguiarvieira.jellymusic.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import pt.aguiarvieira.jellymusic.domain.model.StreamSettings

/**
 * Track ids of the streamed items among the next [scan] queue entries, starting at the current one,
 * in play order (following shuffle and repeat). Downloaded items are skipped: they need no fetching.
 */
internal fun Player.upcomingStreamTrackIds(scan: Int): List<String> {
    val timeline = currentTimeline
    if (timeline.isEmpty) return emptyList()
    // Repeat-one would just name the current track again; look at what follows it instead.
    val repeat = if (repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else repeatMode
    val ids = mutableListOf<String>()
    var index = currentMediaItemIndex
    var steps = 0
    while (index != C.INDEX_UNSET && steps < scan) {
        val item = getMediaItemAt(index)
        if (StreamCache.isStreamUri(item.localConfiguration?.uri)) ids += item.mediaId.removePrefix("track/")
        index = timeline.getNextWindowIndex(index, repeat, shuffleModeEnabled)
        steps++
    }
    return ids
}

/**
 * Rewrites the quality recorded in each queued streamed item to the one the [StreamCache] pinned for
 * it (e.g. an original already cached, played while on mobile data), so the now-playing label and
 * the play method reported to Jellyfin say what's really playing. Only metadata changes, so the
 * player swaps it in without re-preparing the item.
 */
internal fun Player.applyPinnedQualities(pins: Map<String, StreamSettings>) {
    if (pins.isEmpty()) return
    for (i in 0 until mediaItemCount) {
        val item = getMediaItemAt(i)
        val quality = pins[item.mediaId.removePrefix("track/")]
            ?.takeIf { StreamCache.isStreamUri(item.localConfiguration?.uri) }
        val extras = item.mediaMetadata.extras
        if (quality != null && StreamSettingsExtras.settingsFrom(extras) != quality) {
            val metadata = item.mediaMetadata.buildUpon()
                .setExtras(StreamSettingsExtras.withSettings(extras, quality))
                .build()
            replaceMediaItem(i, item.buildUpon().setMediaMetadata(metadata).build())
        }
    }
}
