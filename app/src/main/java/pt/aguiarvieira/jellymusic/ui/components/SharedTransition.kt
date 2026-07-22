package pt.aguiarvieira.jellymusic.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
const val NOW_PLAYING_ART_KEY = "nowplaying-art"

/** Shared-bounds key for an album's whole surface (the container that morphs card ↔ screen). */
private fun albumContainerKey(albumId: String): Any = "album-container-$albumId"

/** Shared-bounds key for the now-playing surface (mini player bar ↔ full player screen). */
private const val NOW_PLAYING_CONTAINER_KEY = "nowplaying-container"

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
    containerTransform(NOW_PLAYING_CONTAINER_KEY)

/**
 * Fast exit fade for a full-bleed child of a container transform (e.g. the album screen's surface
 * backdrop).
 *
 * Predictive back seeks the transition's *bounds* but not its exit *alpha*, and it disposes the
 * popped screen before any exit spec ([fadeOut]/`animateEnterExit`) can play — so a spec-based fade
 * never shows during the gesture, and the opaque backdrop just shrinks with the bounds (slower than
 * the shared cover flies) and gets cut when the cover lands. So don't animate a spec that gets
 * seeked and disposed: read the destination's target state and drive alpha with a *real-time*
 * [animateFloatAsState] on its own clock. The instant a back gesture or the back button starts, the
 * target leaves [EnterExitState.Visible] and the backdrop fades out over [durationMillis],
 * regardless of seek or disposal, revealing whatever is beneath early. Entering keeps it opaque (the
 * initial state is already Visible, so no fade-in). Degrades to a plain modifier outside a nav
 * destination (e.g. previews).
 */
@Composable
fun Modifier.fastExitFade(durationMillis: Int = 120): Modifier {
    val animScope = LocalNavAnimatedVisibilityScope.current ?: return this
    val leaving = animScope.transition.targetState != EnterExitState.Visible
    val alpha by animateFloatAsState(
        targetValue = if (leaving) 0f else 1f,
        animationSpec = tween(durationMillis),
        label = "backdropExitAlpha",
    )
    return this.graphicsLayer { this.alpha = alpha }
}

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
