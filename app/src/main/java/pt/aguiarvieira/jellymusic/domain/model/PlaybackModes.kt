package pt.aguiarvieira.jellymusic.domain.model

/**
 * Persisted shuffle/repeat state so they survive process death (and, trivially, switching album).
 *
 * [repeatMode] holds an ExoPlayer `Player.REPEAT_MODE_*` constant (0 = off, 1 = one, 2 = all) — the
 * playback service reads and writes it directly against the player, so there's no mapping to keep in
 * sync. Default is shuffle off / repeat off.
 */
data class PlaybackModes(
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
)
