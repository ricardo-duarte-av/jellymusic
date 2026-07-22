package pt.aguiarvieira.jellymusic.ui.feature.settings

/**
 * One version's user-facing changes. Hand-written — NOT generated from commits.
 *
 * A [version] of `null` is the "Unreleased" bucket: changes committed to `main` but not yet cut into
 * a tagged release. When a release is tagged, its unreleased entries move under a new heading stamped
 * with the [version] and [date]. See CLAUDE.md — the changelog must be updated before every commit.
 */
data class ChangelogVersion(
    val version: String?,
    val date: String?, // dd/MM/yyyy
    val changes: List<String>,
)

/** Newest first. Unreleased (version = null) always on top. */
val CHANGELOG: List<ChangelogVersion> = listOf(
    ChangelogVersion(
        version = null,
        date = null,
        changes = listOf(
            "The album loading placeholder now shows a header outline where the title, artist and " +
                "buttons will appear, so the track rows no longer sit directly under the cover.",
            "Opening an artist now shows a grid of album placeholders while loading, instead of a " +
                "spinner.",
            "Opening a playlist now shows cover, title and track-row placeholders while loading, " +
                "instead of a spinner.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.68",
        date = "22/07/2026",
        changes = listOf(
            "The now-playing screen now shows the album name between the track title and the artist.",
            "Tap the track title or album name on the now-playing screen to open its album, or the " +
                "artist name to open the artist.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.67",
        date = "22/07/2026",
        changes = listOf(
            "The album screen's top bar now clears with the rest of the screen when back-swiping " +
                "out, instead of lingering and shrinking on its own.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.66",
        date = "22/07/2026",
        changes = listOf(
            "Fixed the leftover background frame that lingered and shrank when back-swiping out of " +
                "an album, so the browse grid shows through cleanly as the cover returns to its card.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.65",
        date = "22/07/2026",
        changes = listOf(
            "Back-swipe out of an album now clears the screen background as soon as the gesture " +
                "starts, so the browse grid shows through while the cover settles into its card.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.64",
        date = "22/07/2026",
        changes = listOf(
            "Back-swipe out of an album now clears the screen background early instead of it " +
                "lingering and getting cut off after the cover has already settled into the card.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.63",
        date = "22/07/2026",
        changes = listOf(
            "Back-swipe out of an album now collapses the screen background into the card together " +
                "with the rest of the content, instead of it lingering behind on its own.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.62",
        date = "22/07/2026",
        changes = listOf(
            "Back-swipe out of an album now dissolves the screen background along with the rest of " +
                "the animation, instead of it staying solid and popping away at the end.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.61",
        date = "22/07/2026",
        changes = listOf(
            "Swiping back out of an album now fades the whole screen into the card as one, instead " +
                "of the background lingering and popping away at the end of the animation.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.60",
        date = "20/07/2026",
        changes = listOf(
            "Opening the full player now expands it out of the mini player bar — cover and all — " +
                "instead of sliding the screen up.",
            "Albums, artists, playlists and the album track list now show pulsing placeholders " +
                "while they load, so the layout no longer jumps when content arrives.",
            "Swipe left or right across the browse area to move between the Albums, Artists and " +
                "Playlists tabs; tapping a tab still crossfades.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.59",
        date = "20/07/2026",
        changes = listOf(
            "Tapping an album now smoothly expands the card into the album screen, and collapses " +
                "back on the way out.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.58",
        date = "20/07/2026",
        changes = listOf(
            "Larger audio buffer to smooth out occasional playback stutter with the screen off, " +
                "especially on busy or congested Wi-Fi.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.57",
        date = "20/07/2026",
        changes = listOf(
            "Added temporary diagnostics to help track down occasional playback stutter with the " +
                "screen off. No change to how the app works.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.56",
        date = "20/07/2026",
        changes = listOf(
            "Fixed occasional audio stutter during playback with the screen off.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.55",
        date = "20/07/2026",
        changes = listOf(
            "Fixed volume normalization (ReplayGain) not being applied to FLAC tracks when streaming " +
                "without transcoding.",
            "With volume normalization turned off, hi-res FLAC now plays at full float precision " +
                "instead of being downscaled to 16-bit.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.54",
        date = "19/07/2026",
        changes = listOf(
            "Cover art updated on the server (e.g. re-tagged in Picard) now shows in the app instead " +
                "of the old cached image — everywhere covers appear.",
            "Pull down on an album to refresh it and pick up a cover change straight away.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.53",
        date = "19/07/2026",
        changes = listOf(
            "New setting: Download favourites to device. Keep your hearted tracks, albums and " +
                "playlists downloaded for offline listening — they sync automatically (on app start, " +
                "when you switch library, when you toggle a favourite, or when you open the " +
                "favourites filter) and only download on Wi-Fi, with an optional 'Allow on mobile " +
                "data' override.",
            "A favourited album or playlist now shows the downloaded badge on its artwork once its " +
                "tracks are on the device.",
            "The favourite heart shown over album/artwork now uses your theme colour instead of " +
                "plain white.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.52",
        date = "19/07/2026",
        changes = listOf(
            "Fixed Android Auto: tapping a track now actually plays it (it was previously a no-op).",
            "Android Auto: albums and playlists can now be played directly (tap play to queue the " +
                "whole thing), while tapping the row still opens the track list.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.51",
        date = "18/07/2026",
        changes = listOf(
            "Fixed Android Auto browsing coming up empty when the car connected before the app had " +
                "been opened — the session is now restored by the player service itself.",
            "Android Auto's Recently Played and Most Played now work from your listening history " +
                "(previously always empty).",
            "Android Auto now always uses the library you've selected in the app; the in-car library " +
                "menu has been removed. Switch library on your phone and the tabs re-scope to it " +
                "automatically.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.50",
        date = "18/07/2026",
        changes = listOf(
            "Android Auto now opens with quick-start rows — Recently Played, Most Played, Recently " +
                "Added and Favourites — so you can start music from the car without reaching for " +
                "your phone.",
            "Android Auto: you can now switch music library from the car via the new Libraries menu.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.49",
        date = "17/07/2026",
        changes = listOf(
            "Shuffling an album (or playlist) now turns on real shuffle mode instead of building a " +
                "one-off scrambled queue — toggling shuffle off in the player restores the original " +
                "order.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.48",
        date = "17/07/2026",
        changes = listOf(
            "Larger home-screen widgets (4x2) now show a frosted, blurred cover as the background, " +
                "with the sharp album art as a bold square thumbnail in the top-right corner. " +
                "Smaller widgets keep the darkened full-cover look.",
            "The widget's cover art now crossfades smoothly when the track changes instead of " +
                "snapping to the new image.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.47",
        date = "17/07/2026",
        changes = listOf(
            "Search results: long-press a song or album to download it for offline playback (or " +
                "remove the local copy), just like on the browse and detail screens.",
            "Download a whole playlist at once: long-press a playlist (on the Playlists tab or in " +
                "search) to queue all its tracks for offline playback. Playlists show a download " +
                "badge and progress like albums, appear as their own group in Downloads, and can be " +
                "removed in one tap.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.46",
        date = "17/07/2026",
        changes = listOf(
            "Tidied the browse header: the Favourites filter is now a compact heart button, and the " +
                "search and settings buttons line up with the library and sort controls.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.45",
        date = "17/07/2026",
        changes = listOf(
            "Favourites: albums, artists, playlists and tracks you've favourited on Jellyfin now " +
                "show a heart, and you can favourite items from the album, artist, playlist and " +
                "now-playing screens.",
            "New \"Favourites\" filter on the Albums, Artists and Playlists tabs to show only your " +
                "favourites.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.44",
        date = "17/07/2026",
        changes = listOf(
            "Pull down on the Artists or Playlists list to refresh it from the server.",
            "Artists and other items without cover art now show a placeholder icon instead of an " +
                "empty grey square.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.43",
        date = "16/07/2026",
        changes = listOf(
            "Swipe the now-playing bar left or right to stop playback and clear the queue.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.42",
        date = "16/07/2026",
        changes = listOf(
            "The now-playing bar and album-header shades now take on the album's colour instead " +
                "of a flat grey.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.41",
        date = "16/07/2026",
        changes = listOf(
            "The now-playing bar now has a soft top shade so it stands out from the list behind it.",
            "The album screen's pinned header now has a bottom shade marking where the track list starts.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.40",
        date = "16/07/2026",
        changes = listOf(
            "Search results are now ordered Playlists, Artists, Albums, Songs, with filter " +
                "buttons above the results to show only the kinds you tap.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.39",
        date = "16/07/2026",
        changes = listOf(
            "Widget app icon now shows a music note on an album-coloured disc, using colours that " +
                "match the cover.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.38",
        date = "15/07/2026",
        changes = listOf(
            "Album-art colours now match the cover more closely (primary, a muted variant, " +
                "and a complementary accent) instead of being shifted to unrelated hues.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.37",
        date = "15/07/2026",
        changes = listOf(
            "Now-playing screen shows a banner of the colours extracted from the album art.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.36",
        date = "15/07/2026",
        changes = listOf(
            "Added a Changelog screen and an About screen to Settings.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.35",
        date = "15/07/2026",
        changes = listOf(
            "Track cards now show full audio detail: codec, sample rate, bit depth, bitrate and ReplayGain.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.34",
        date = "15/07/2026",
        changes = listOf(
            "Show codec and ReplayGain info on track cards.",
            "Show the current track's ReplayGain value on the now-playing screen.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.33",
        date = "15/07/2026",
        changes = listOf(
            "ReplayGain / loudness normalization: even out volume between tracks using the server's LUFS scan.",
            "Added a Playback setting to toggle normalization and a pre-amp slider.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.32",
        date = "14/07/2026",
        changes = listOf("Reduced battery drain during playback."),
    ),
    ChangelogVersion(
        version = "0.1.31",
        date = "14/07/2026",
        changes = listOf("Fixed the home-screen widget buttons and playback-state mirroring."),
    ),
    ChangelogVersion(
        version = "0.1.24",
        date = "14/07/2026",
        changes = listOf(
            "Added the now-playing home-screen widget, with compact sizes.",
            "New app icon.",
        ),
    ),
    ChangelogVersion(
        version = "0.1.0",
        date = "10/07/2026",
        changes = listOf(
            "Initial releases: browse albums/artists/playlists, playback, search, offline downloads and Android Auto.",
        ),
    ),
)
