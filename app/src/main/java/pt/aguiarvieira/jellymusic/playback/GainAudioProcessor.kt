package pt.aguiarvieira.jellymusic.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A Media3 audio processor that applies a linear gain to the PCM stream — the mechanism behind
 * ReplayGain / loudness normalization. The gain is set per track (from Jellyfin's LUFS
 * `NormalizationGain`, plus the user's manual preamp) via [setGainDb] and can change live mid-stream.
 *
 * Handles the two encodings ExoPlayer's FLAC/AAC/MP3 decoders commonly emit — 16-bit integer and
 * float PCM. Other encodings (e.g. 24-bit hi-res) pass through untouched: the processor declares
 * itself inactive for them, so playback is never broken, it just isn't normalized. Positive gains
 * (boosting quiet tracks) are hard-clipped at full scale; loudness-normalization gains rarely push a
 * track into clipping since quiet tracks have headroom by definition.
 */
@UnstableApi
class GainAudioProcessor : BaseAudioProcessor() {

    // Linear multiplier (10^(dB/20)); 1.0 = unity (no change). Read on the audio thread, written from
    // the playback thread on track transitions / settings changes, hence @Volatile.
    @Volatile
    private var gain: Float = 1f

    /** Set the effective gain in dB, or null to disable normalization (unity gain). */
    fun setGainDb(db: Float?) {
        gain = if (db == null) 1f else Math.pow(10.0, db / 20.0).toFloat()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        ) {
            inputAudioFormat // active: output matches input, we scale samples in place
        } else {
            // Unsupported PCM encoding — declare inactive so the pipeline bypasses us entirely.
            AudioProcessor.AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val g = gain
        if (g == 1f) {
            // Unity gain: straight byte copy, no per-sample math (order is irrelevant here).
            output.put(inputBuffer)
            output.flip()
            return
        }
        // Media3 PCM is little-endian; a raw ByteBuffer defaults to big-endian, so set both views to
        // little-endian before reading/writing samples or they'd be byte-swapped into noise.
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT ->
                while (inputBuffer.remaining() >= 2) {
                    val scaled = inputBuffer.short * g
                    output.putShort(scaled.coerceIn(SHORT_MIN, SHORT_MAX).toInt().toShort())
                }

            C.ENCODING_PCM_FLOAT ->
                while (inputBuffer.remaining() >= 4) {
                    output.putFloat((inputBuffer.float * g).coerceIn(-1f, 1f))
                }
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    private companion object {
        const val SHORT_MIN = Short.MIN_VALUE.toFloat()
        const val SHORT_MAX = Short.MAX_VALUE.toFloat()
    }
}
