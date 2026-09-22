package pt.aguiarvieira.jellymusic.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A Media3 audio processor that applies a linear gain to the PCM stream — the mechanism behind
 * ReplayGain / loudness normalization. The gain is set per track (from Jellyfin's LUFS
 * `NormalizationGain`, plus the user's manual preamp) and can change live mid-stream.
 *
 * The per-track gain is picked up *here*, in [onFlush], rather than on the player's media-item
 * transition. The audio sink decodes and processes well ahead of what is audible (its AudioTrack
 * buffer holds 2.5–5 s), so a gain set at the transition callback was applied to audio that had
 * already gone through — the start of the next track played at the previous track's level until
 * the buffer caught up. Media3 re-flushes the processor chain with [AudioProcessor.StreamMetadata]
 * exactly where a new track's samples begin, carrying the timeline and period, which resolve to that
 * track's MediaItem and its gain — so the switch lands on the first sample of the track.
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
    // the playback thread (stream changes) and the main thread (settings changes), hence @Volatile.
    @Volatile
    private var gain: Float = 1f

    @Volatile private var enabled = true
    @Volatile private var preampDb = 0f

    // Normalization gain of the track whose samples are currently being processed (not necessarily
    // the one audible yet), or null when it has none.
    @Volatile private var trackGainDb: Float? = null

    /**
     * True once a stream change has resolved a track from its [AudioProcessor.StreamMetadata]. Until
     * then the service falls back to [setTrackGainDb] on media-item transitions, so a sink that never
     * delivers stream metadata still gets normalized (with the old, late timing).
     */
    @Volatile
    var followsStreams = false
        private set

    /** ReplayGain on/off and the manual preamp; applies immediately to the audio being processed. */
    fun setSettings(enabled: Boolean, preampDb: Float) {
        this.enabled = enabled
        this.preampDb = preampDb
        updateGain()
    }

    /** Fallback track-gain source for when [followsStreams] is false. */
    fun setTrackGainDb(db: Float?) {
        trackGainDb = db
        updateGain()
    }

    private fun updateGain() {
        gain = if (enabled) Math.pow(10.0, ((trackGainDb ?: 0f) + preampDb) / 20.0).toFloat() else 1f
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val timeline = streamMetadata.timeline
        val periodUid = streamMetadata.periodUid ?: return
        // A plain flush (seek within the same track) carries no stream: keep the current track's gain.
        if (timeline.isEmpty || timeline.getIndexOfPeriod(periodUid) == C.INDEX_UNSET) return
        val windowIndex = timeline.getPeriodByUid(periodUid, Timeline.Period()).windowIndex
        val item = timeline.getWindow(windowIndex, Timeline.Window()).mediaItem
        followsStreams = true
        trackGainDb = StreamSettingsExtras.gainDbFrom(item.mediaMetadata.extras)
        updateGain()
        Log.d(TAG, "stream -> ${item.mediaId}, trackGainDb=$trackGainDb, gain=$gain")
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
        const val TAG = "GainAudioProcessor"
        const val SHORT_MIN = Short.MIN_VALUE.toFloat()
        const val SHORT_MAX = Short.MAX_VALUE.toFloat()
    }
}
