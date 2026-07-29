package pt.aguiarvieira.jellymusic.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score

/**
 * The single source of truth for "what colour is this album cover?".
 *
 * Every surface that tints itself from artwork — the now-playing screen, the mini player, the
 * widget — must seed from this, and *only* this. The notification in the shade and the Android Auto
 * player are drawn by the system, which runs Monet's own seed selection (Celebi quantization scored
 * by [Score]) over the same artwork; by using that identical pipeline here, all four surfaces land
 * on the same hue by construction instead of by coincidence.
 *
 * This replaced `Palette.vibrantSwatch`, which was the odd one out: Palette scores saturation-first,
 * so on a cover with a small vivid accent over a large muted mass it picks the accent while the
 * system picks the mass. On The Prodigy's *The Fat of the Land* — small saturated cyan sky over a
 * large sandy-olive beach — Palette chose the cyan (#3ec2fa) while the system chose the sand
 * (#9bb285), which is exactly why the app and the notification used to disagree. Don't reintroduce a
 * saturation-first seed here without fixing the system surfaces too, which is not possible: neither
 * the shade's media controls nor Android Auto expose a colour API.
 */
object AlbumSeed {

    /** Covers are quantized at this resolution — enough colour detail, cheap enough to run inline. */
    private const val BITMAP_PX = 128

    /** Palette size handed to [Score]; matches Monet's own bucket count. */
    private const val MAX_COLORS = 128

    /**
     * The seed colour for [bitmap], or null when no colour qualifies (e.g. a greyscale or blank
     * cover) — callers then fall back to the ambient theme rather than inventing a hue.
     */
    fun from(bitmap: Bitmap): Color? {
        val scaled = Bitmap.createScaledBitmap(bitmap, BITMAP_PX, BITMAP_PX, true)
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        if (scaled !== bitmap) scaled.recycle()

        val quantized = QuantizerCelebi.quantize(pixels, MAX_COLORS)
        // desired = 1: we only ever want the winner. fallback = null so an unscoreable cover reports
        // "no seed" instead of silently theming everything Monet's default blue.
        val argb = Score.score(quantized, 1, null, true).firstOrNull() ?: return null
        return Color(argb)
    }
}
