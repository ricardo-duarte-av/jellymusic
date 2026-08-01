package pt.aguiarvieira.jellymusic.domain.model

/**
 * One line of a track's lyrics. [startMs] is the moment the line is sung, present only for
 * *synchronized* lyrics (LRC-style); plain text lyrics carry `null` for every line.
 */
data class LyricLine(
    val text: String,
    val startMs: Long?,
)

/**
 * A track's lyrics as served by Jellyfin.
 *
 * [synced] is true when every line carries a timestamp, which is what lets the player follow along
 * with playback. Mixed files (some lines timed, some not) are treated as unsynced — a partial
 * highlight would jump around more than it would help.
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
) {
    /**
     * Index of the line being sung at [positionMs] — the last line whose start has passed. Returns
     * -1 before the first timed line (an intro), or for unsynced lyrics where nothing is "current".
     */
    fun activeLineAt(positionMs: Long): Int {
        if (!synced) return -1
        // Lines are start-ordered, so a binary search finds the insertion point in O(log n) and the
        // line before it is the one currently playing.
        var low = 0
        var high = lines.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if ((lines[mid].startMs ?: 0L) <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}
