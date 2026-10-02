package pt.aguiarvieira.jellymusic.ui.feature.player

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope

/** Alpha of the primary colour laid over the inactive track to mark the downloaded part. */
private const val DOWNLOADED_TRACK_ALPHA = 0.38f

/**
 * The "downloaded, not yet played" segment of a seek bar: a stronger tone of the inactive track,
 * between the played part (primary) and the part still to download (the plain inactive track).
 */
internal fun downloadedTrackColor(primary: Color, inactiveTrack: Color): Color =
    primary.copy(alpha = DOWNLOADED_TRACK_ALPHA).compositeOver(inactiveTrack)

/**
 * The seek bars draw their whole inactive track in [downloadedTrackColor], then this repaints the
 * part not downloaded yet, from [downloaded] (0..1 of the width) to the end, in the plain
 * [inactiveTrack] colour. It never reaches left of [inactiveStartPx], where the inactive track begins
 * after the thumb or the played part. The far end is rounded like the track's; the near edge gets the
 * track's small inside corner.
 */
internal fun DrawScope.drawNotDownloaded(
    downloaded: Float,
    inactiveStartPx: Float,
    inactiveTrack: Color,
    insideCornerPx: Float,
) {
    val start = maxOf(downloaded * size.width, inactiveStartPx)
    if (start >= size.width) return
    val outer = CornerRadius(size.height / 2)
    val inner = CornerRadius(insideCornerPx)
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                left = start,
                top = 0f,
                right = size.width,
                bottom = size.height,
                topLeftCornerRadius = inner,
                topRightCornerRadius = outer,
                bottomRightCornerRadius = outer,
                bottomLeftCornerRadius = inner,
            ),
        )
    }
    drawPath(path, inactiveTrack)
}
