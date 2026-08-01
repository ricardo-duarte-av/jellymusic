package pt.aguiarvieira.jellymusic.ui.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The cover-plus-metadata block at the top of the full player, laid out at any point along the
 * morph between its two arrangements:
 *
 * - `fraction = 0` — cover at full width with the title, album and artist centred beneath it.
 * - `fraction = 1` — cover shrunk to a quarter-width thumbnail in the top-left corner with the
 *   metadata beside it, freeing the rest of the screen for the lyrics.
 *
 * This is one continuous [Layout] rather than two layouts swapping over, so the cover really does
 * travel and shrink into the corner instead of cross-fading between two positions. [fraction] is
 * expected to be driven by an animation.
 */
@Composable
fun NowPlayingHeader(
    fraction: Float,
    modifier: Modifier = Modifier,
    art: @Composable () -> Unit,
    metadata: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(art, metadata),
        modifier = modifier,
    ) { (artMeasurables, metaMeasurables), constraints ->
        val width = constraints.maxWidth
        val gap = COLLAPSED_GAP.roundToPx()
        val stackGap = STACKED_GAP.roundToPx()

        // The cover is always square and always anchored at the top-left; only its size changes, so
        // shrinking it is what moves it "into the corner".
        val artSize = lerp(width.toFloat(), width * COLLAPSED_ART_FRACTION, fraction).roundToInt()
        val artPlaceable = artMeasurables.first().measure(Constraints.fixed(artSize, artSize))

        // Metadata takes the full width while stacked, and whatever the thumbnail leaves once beside it.
        val metaWidth = lerp(width.toFloat(), (width - artSize - gap).toFloat(), fraction)
            .roundToInt()
            .coerceIn(0, width)
        val metaPlaceable = metaMeasurables.first().measure(
            Constraints(maxWidth = metaWidth, maxHeight = Constraints.Infinity),
        )

        val metaX = (fraction * (artSize + gap)).roundToInt()
        val metaY = lerp(
            (artSize + stackGap).toFloat(),
            ((artSize - metaPlaceable.height) / 2).coerceAtLeast(0).toFloat(),
            fraction,
        ).roundToInt()

        val height = lerp(
            (artSize + stackGap + metaPlaceable.height).toFloat(),
            max(artSize, metaPlaceable.height).toFloat(),
            fraction,
        ).roundToInt()

        layout(width, height) {
            artPlaceable.place(0, 0)
            metaPlaceable.place(metaX, metaY)
        }
    }
}

/** Width of the collapsed cover thumbnail, as a fraction of the header width — a quarter-size cover. */
const val COLLAPSED_ART_FRACTION = 0.25f

private val COLLAPSED_GAP = 16.dp

/**
 * Gap between the cover and the title while stacked. Deliberately tighter than the old 32.dp: the
 * space it gives back is what the lyrics box lives in.
 */
private val STACKED_GAP = 16.dp
