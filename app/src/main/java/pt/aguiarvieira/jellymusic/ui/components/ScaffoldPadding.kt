package pt.aguiarvieira.jellymusic.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * The Scaffold body inset with its bottom component dropped.
 *
 * The mini player lives in the `bottomBar` slot, which Scaffold draws *over* the body. Applying the
 * full inset to the body would stop the content above the bar, leaving a bare strip of window
 * background around the floating card. Padding only the other edges lets the list scroll underneath
 * it — pair this with [bottomInset] as the list's `contentPadding` so the last item still clears.
 */
@Composable
fun PaddingValues.withoutBottom(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = calculateTopPadding(),
        end = calculateEndPadding(direction),
    )
}

/** Just the bottom component of a Scaffold inset — the mini player's height, or zero when hidden. */
@Composable
fun PaddingValues.bottomInset(): PaddingValues = PaddingValues(bottom = calculateBottomPadding())
