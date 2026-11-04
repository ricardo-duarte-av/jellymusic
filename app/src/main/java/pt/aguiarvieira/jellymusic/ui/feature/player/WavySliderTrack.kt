package pt.aguiarvieira.jellymusic.ui.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Seek bar track in the style of Android's media controls: the played part is a wave that drifts
 * forward while playing, the rest is a straight line. The wave flattens smoothly when playback
 * pauses or the user is dragging the thumb, so the position is easy to read while scrubbing.
 *
 * While the track is still downloading, the straight part is split as before: downloaded (a stronger
 * inactive tone) · not downloaded yet (the plain inactive track). [downloaded] null means fully
 * available.
 */
@Composable
internal fun WavySliderTrack(
    sliderState: SliderState,
    downloaded: Float?,
    isPlaying: Boolean,
    isScrubbing: Boolean,
) {
    val colors = SliderDefaults.colors()
    val activeColor = colors.activeTrackColor
    val inactiveColor = colors.inactiveTrackColor
    val downloadedColor = downloadedTrackColor(activeColor, inactiveColor)

    val wavy = isPlaying && !isScrubbing
    // 0 = flat line, 1 = full wave. Read only while drawing, so it never recomposes the slider.
    val waveScale = remember { Animatable(if (wavy) 1f else 0f) }
    LaunchedEffect(wavy) {
        waveScale.animateTo(if (wavy) 1f else 0f, tween(WAVE_FADE_MS))
    }
    // How far the wave has drifted, in wavelengths. Only advances while there is a wave to show.
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(wavy) {
        if (!wavy) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                phase.floatValue = (phase.floatValue + (now - last) / WAVE_PERIOD_NANOS) % 1f
                last = now
            }
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT),
    ) {
        val stroke = TRACK_STROKE.toPx()
        val half = stroke / 2
        val clearance = THUMB_CLEARANCE.toPx()
        val cy = size.height / 2
        val thumbX = sliderState.value.coerceIn(0f, 1f) * size.width
        val activeEnd = thumbX - clearance - half
        val inactiveStart = thumbX + clearance + half
        val lineEnd = size.width - half

        // Played: the wave, tapering to the centre line just before the thumb so it meets it cleanly.
        if (activeEnd > half) {
            val amplitude = WAVE_AMPLITUDE.toPx() * waveScale.value
            val wavelength = WAVELENGTH.toPx()
            val taper = wavelength / 2
            val step = 1.dp.toPx()
            val path = Path()
            var x = half
            while (true) {
                val toThumb = activeEnd - x
                val envelope = (toThumb / taper).coerceIn(0f, 1f)
                val angle = 2 * PI * (x / wavelength - phase.floatValue)
                val y = cy + amplitude * envelope * sin(angle).toFloat()
                if (x == half) path.moveTo(x, y) else path.lineTo(x, y)
                if (x >= activeEnd) break
                x = minOf(x + step, activeEnd)
            }
            drawPath(path, activeColor, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }

        // Still to play: a straight line, with the downloaded part painted over the not-downloaded one.
        if (inactiveStart < lineEnd) {
            val start = Offset(inactiveStart, cy)
            val end = Offset(lineEnd, cy)
            if (downloaded == null) {
                drawLine(inactiveColor, start, end, stroke, StrokeCap.Round)
            } else {
                drawLine(inactiveColor, start, end, stroke, StrokeCap.Round)
                val downloadedX = downloaded.coerceIn(0f, 1f) * size.width
                if (downloadedX > inactiveStart - half) {
                    clipRect(left = inactiveStart - half, right = downloadedX) {
                        drawLine(downloadedColor, start, end, stroke, StrokeCap.Round)
                    }
                }
            }
        }

        // End-of-track dot, as on the stock M3 track.
        with(SliderDefaults) {
            drawStopIndicator(
                Offset(lineEnd, cy),
                STOP_INDICATOR_SIZE,
                activeColor,
            )
        }
    }
}

/** Height of the area the track draws in: the stroke plus the wave's swing either side. */
private val TRACK_HEIGHT = 16.dp

/** Thickness of both the wave and the straight line. */
private val TRACK_STROKE = 6.dp

/** Peak height of the wave from the centre line. Stroke + 2 × amplitude stays inside [TRACK_HEIGHT]. */
private val WAVE_AMPLITUDE = 4.dp

private val WAVELENGTH = 40.dp

/** Gap between the thumb's centre and either side of the track (half the 4dp thumb + its 6dp gap). */
private val THUMB_CLEARANCE = 8.dp

/** Smaller than the stock stop dot so it sits inside the thinner track. */
private val STOP_INDICATOR_SIZE = 2.dp

/** Time for the wave to drift forward by one wavelength. */
private const val WAVE_PERIOD_NANOS = 2_000_000_000f

/** How long the wave takes to flatten (pause, start of a drag) or come back (play, release). */
private const val WAVE_FADE_MS = 400
