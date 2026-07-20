package pt.aguiarvieira.jellymusic.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

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
 * Shared-element key for the now-playing cover, matched between the mini player bar and the full
 * player. Constant — only one track is playing at a time, so only one of each is ever on screen.
 */
fun nowPlayingArtSharedKey(): Any = "nowplaying-art"

/** Shared-bounds key for an album's whole surface (the container that morphs card ↔ screen). */
private fun albumContainerKey(albumId: String): Any = "album-container-$albumId"

/** Shared-bounds key for the now-playing surface (mini player bar ↔ full player screen). */
private fun nowPlayingContainerKey(): Any = "nowplaying-container"

/**
 * M3 container transform: a whole surface grows into a destination screen (and back). The outgoing
 * content fades as the bounds grow while the incoming content fades in, so the two screens read as
 * one morphing container instead of two pages cross-fading against each other. A cover tagged
 * separately with [sharedElementArt] rides on top and stays crisp throughout.
 *
 * Apply to the source surface and to the destination screen's root with the same [key].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.containerTransform(key: Any): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val animScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        this@containerTransform.sharedBounds(
            rememberSharedContentState(key = key),
            animatedVisibilityScope = animScope,
            enter = fadeIn(),
            exit = fadeOut(),
            // Scale (cheap) rather than remeasure the whole Scaffold every frame; the content fades
            // fast enough that the transient stretch isn't visible.
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
            ),
        )
    }
}

/** Container transform for the album card ↔ album screen. See [containerTransform]. */
@Composable
fun Modifier.albumContainerTransform(albumId: String): Modifier =
    containerTransform(albumContainerKey(albumId))

/** Container transform for the mini player bar ↔ full player screen. See [containerTransform]. */
@Composable
fun Modifier.nowPlayingContainerTransform(): Modifier =
    containerTransform(nowPlayingContainerKey())

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
