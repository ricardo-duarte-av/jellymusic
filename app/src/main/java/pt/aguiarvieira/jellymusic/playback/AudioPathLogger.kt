package pt.aguiarvieira.jellymusic.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink

private const val TAG = "AudioPath"

/**
 * Logs, once per audio track, why playback did or didn't go through hardware offload, the path that
 * lets the CPU sleep through long screen-off listening. It uses `Log.i` on purpose: release builds
 * strip `Log.d` (proguard-rules.pro), which hid this from the Play build. Read it with
 * `adb logcat -s AudioPath`.
 *
 * Per track it prints the source codec, whether the AudioTrack was opened in offload mode and with
 * which PCM encoding, where audio is routed, and what the platform itself says about offloading this
 * format there. That covers each gate offload can fail: a codec Android has no compressed encoding
 * for (FLAC, Vorbis: never offloadable), the device/route refusing it, gapless not supported (we
 * require it), or the app being in int16 mode (ReplayGain on).
 */
@UnstableApi
internal class AudioPathLogger(context: Context) : AnalyticsListener, ExoPlayer.AudioOffloadListener {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var inputFormat: Format? = null

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        inputFormat = format
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, config: AudioSink.AudioTrackConfig) {
        val format = inputFormat
        Log.i(
            TAG,
            "track opened: source=${format?.sampleMimeType} ${format?.sampleRate}Hz " +
                "${format?.channelCount}ch ${format?.bitrate?.takeIf { it > 0 }?.let { "${it / 1000}kbps" } ?: ""}" +
                " | offload=${config.offload} sinkEncoding=${encodingName(config.encoding)} " +
                "rate=${config.sampleRate} | route=${route()} | platformOffload=${format?.let(::platformSupport)}",
        )
    }

    /**
     * After each track selection, logs the inputs DefaultTrackSelector used to decide on offload: the
     * offload preferences the player actually holds, each audio renderer's offload capability for its
     * tracks, and any non-audio track that is selected (which on its own vetoes offload).
     */
    override fun onEvents(player: Player, events: AnalyticsListener.Events) {
        if (!events.contains(AnalyticsListener.EVENT_TRACKS_CHANGED)) return
        val exo = player as? ExoPlayer ?: return
        val prefs = exo.trackSelectionParameters.audioOffloadPreferences
        val pref = "mode=${offloadModeName(prefs.audioOffloadMode)} gaplessRequired=${prefs.isGaplessSupportRequired}"
        val mapped = (exo.trackSelector as? MappingTrackSelector)?.currentMappedTrackInfo
        if (mapped == null) {
            Log.i(TAG, "selection: offloadPrefs[$pref] | no mapped track info")
            return
        }
        val audioCaps = mutableListOf<String>()
        for (r in 0 until mapped.rendererCount) {
            if (mapped.getRendererType(r) != C.TRACK_TYPE_AUDIO) continue
            val groups = mapped.getTrackGroups(r)
            for (g in 0 until groups.length) {
                val group = groups[g]
                for (t in 0 until group.length) {
                    val caps = mapped.getCapabilities(r, g, t)
                    val f = group.getFormat(t)
                    audioCaps += "r$r(${mapped.getRendererName(r)}) ${f.sampleMimeType}/${f.codecs} " +
                        "delay=${f.encoderDelay} pad=${f.encoderPadding} " +
                        "support=${RendererCapabilities.getFormatSupport(caps)} " +
                        "offloadCaps=${offloadCapsName(RendererCapabilities.getAudioOffloadSupport(caps))}"
                }
            }
        }
        val selectedNonAudio = exo.currentTracks.groups
            .filter { it.isSelected && it.type != C.TRACK_TYPE_AUDIO }
            .joinToString { "type${it.type}:${it.getTrackFormat(0).sampleMimeType}" }
            .ifEmpty { "none" }
        val selectedAudio = exo.currentTracks.groups.count { it.isSelected && it.type == C.TRACK_TYPE_AUDIO }
        Log.i(
            TAG,
            "selection: offloadPrefs[$pref] | audio=${audioCaps.joinToString(" ; ").ifEmpty { "none" }}" +
                " | selectedAudioGroups=$selectedAudio selectedNonAudio=$selectedNonAudio",
        )
    }

    private fun offloadModeName(mode: Int): String = when (mode) {
        AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED -> "disabled"
        AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED -> "enabled"
        AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_REQUIRED -> "required"
        else -> "mode$mode"
    }

    private fun offloadCapsName(caps: Int): String {
        if (caps == RendererCapabilities.AUDIO_OFFLOAD_NOT_SUPPORTED) return "none"
        return buildList {
            if (caps and RendererCapabilities.AUDIO_OFFLOAD_SUPPORTED != 0) add("supported")
            if (caps and RendererCapabilities.AUDIO_OFFLOAD_GAPLESS_SUPPORTED != 0) add("gapless")
            if (caps and RendererCapabilities.AUDIO_OFFLOAD_SPEED_CHANGE_SUPPORTED != 0) add("speed")
        }.joinToString("+")
    }

    override fun onOffloadedPlayback(isOffloadedPlayback: Boolean) {
        Log.i(TAG, "offloaded playback: $isOffloadedPlayback")
    }

    override fun onSleepingForOffloadChanged(sleepingForOffload: Boolean) {
        Log.i(TAG, "sleeping for offload (CPU free to sleep): $sleepingForOffload")
    }

    /** What the platform reports for offloading [format] on the current route. */
    private fun platformSupport(format: Format): String {
        val mime = format.sampleMimeType ?: return "unknown format"
        val encoding = MimeTypes.getEncoding(mime, format.codecs)
        if (encoding == C.ENCODING_INVALID) return "impossible: Android has no compressed encoding for $mime"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "not queryable below API 31"
        if (format.sampleRate <= 0 || format.channelCount <= 0) return "unknown rate/channels"
        return runCatching {
            val audioFormat = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(format.sampleRate)
                .setChannelMask(Util.getAudioTrackChannelConfig(format.channelCount))
                .build()
            when (AudioManager.getPlaybackOffloadSupport(audioFormat, musicAttributes())) {
                AudioManager.PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED -> "supported (gapless)"
                AudioManager.PLAYBACK_OFFLOAD_SUPPORTED -> "supported, but NOT gapless (we require gapless)"
                else -> "not supported on this route"
            }
        }.getOrElse { "query failed: ${it.message}" }
    }

    private fun route(): String {
        val manager = audioManager ?: return "?"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "? (API < 33)"
        return manager.getAudioDevicesForAttributes(musicAttributes())
            .joinToString { deviceName(it.type) }
            .ifEmpty { "none" }
    }

    private fun musicAttributes() = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun deviceName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth-a2dp"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "bluetooth-le"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
        else -> "type$type"
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        C.ENCODING_PCM_16BIT -> "pcm16"
        C.ENCODING_PCM_FLOAT -> "pcm-float"
        C.ENCODING_PCM_24BIT -> "pcm24"
        C.ENCODING_PCM_32BIT -> "pcm32"
        C.ENCODING_MP3 -> "mp3"
        C.ENCODING_AAC_LC -> "aac"
        C.ENCODING_OPUS -> "opus"
        else -> "enc$encoding"
    }
}
