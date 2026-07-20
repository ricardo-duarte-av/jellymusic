package pt.aguiarvieira.jellymusic.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Shared-element plumbing for cross-screen transitions (album grid ↔ album viewer).
 *
 * The [SharedTransitionScope] is provided once, around the whole NavHost; each nav destination
 * provides its own [AnimatedVisibilityScope] (the `AnimatedContentScope` receiver of its
 * `composable { }` block). A tagged element reads both locals via [sharedElementArt] — when either
 * is absent (e.g. a Compose preview) it degrades to a plain modifier.
 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Shared-element key for an album's cover, matched between the grid card and the detail hero. */
fun albumArtSharedKey(albumId: String): Any = "album-art-$albumId"

/**
 * Marks this element as one half of a shared-element transition keyed by [key]. Applied to the same
 * key on both the source (grid card artwork) and destination (detail hero artwork), Compose tweens
 * its bounds between the two screens as the nav transition runs.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedElementArt(key: Any): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val animScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        this@sharedElementArt.sharedElement(
            rememberSharedContentState(key = key),
            animatedVisibilityScope = animScope,
        )
    }
}
