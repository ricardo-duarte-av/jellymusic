package pt.aguiarvieira.jellymusic.domain.model

/**
 * Persisted shuffle/repeat state. These are *user* settings, not per-queue state: nothing but an
 * explicit tap on the shuffle/repeat controls (in the app, the notification, or Android Auto) may
 * change them, and they survive switching album/playlist/library as well as a full app restart.
 *
 * [repeatMode] holds an ExoPlayer `Player.REPEAT_MODE_*` constant (0 = off, 1 = one, 2 = all) — the
 * playback service reads and writes it directly against the player, so there's no mapping to keep in
 * sync. Defaults are shuffle off / repeat all.
 */
data class PlaybackModes(
    val shuffle: Boolean = false,
    val repeatMode: Int = DEFAULT_REPEAT_MODE,
) {
    companion object {
        /** `Player.REPEAT_MODE_ALL`, spelled out to keep this model free of the media3 dependency. */
        const val DEFAULT_REPEAT_MODE = 2
    }
}
