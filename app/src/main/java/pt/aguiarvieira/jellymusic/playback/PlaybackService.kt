package pt.aguiarvieira.jellymusic.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.model.api.PlayMethod
import pt.aguiarvieira.jellymusic.BuildConfig
import pt.aguiarvieira.jellymusic.MainActivity
import pt.aguiarvieira.jellymusic.data.settings.QueueStore
import pt.aguiarvieira.jellymusic.data.settings.SettingsStore
import pt.aguiarvieira.jellymusic.domain.model.PersistedQueue
import pt.aguiarvieira.jellymusic.domain.model.QueueTrack
import pt.aguiarvieira.jellymusic.domain.model.toTrack
import androidx.glance.appwidget.updateAll
import pt.aguiarvieira.jellymusic.widget.NowPlayingWidget
import pt.aguiarvieira.jellymusic.widget.nowPlayingWidgetData
import pt.aguiarvieira.jellymusic.widget.writeNowPlayingWidgetData
import javax.inject.Inject

// Slow heartbeat (only while playing) that keeps the server's "Now Playing" position and resume point
// fresh between events — matters mainly for a long, single, uninterrupted track where no track
// transition fires. Track-boundary/pause/seek reporting is event-driven; this just fills the gaps.
private const val PROGRESS_REPORT_INTERVAL_MS = 30_000L
private const val TAG = "PlaybackService"

// Custom session commands backing the notification / Android Auto shuffle & repeat buttons.
private const val CMD_TOGGLE_SHUFFLE = "pt.aguiarvieira.jellymusic.TOGGLE_SHUFFLE"
private const val CMD_CYCLE_REPEAT = "pt.aguiarvieira.jellymusic.CYCLE_REPEAT"

/**
 * Max children returned for a single browse node. Android Auto requests all children in one Binder
 * transaction (see [PlaybackService.LibraryCallback.onGetChildren]); this keeps even the largest
 * node (Albums) safely under the ~1MB transaction limit.
 */
private const val MAX_CHILDREN_PER_NODE = 500

/** Upper bound on the blocking final queue write in [PlaybackService.onDestroy]. */
private const val QUEUE_SAVE_TIMEOUT_MS = 1_000L

/**
 * How deep into a track "previous" still means *previous track*. Past this point every previous
 * button — widget, player screen, notification, Android Auto, headset — restarts the current track
 * instead. Media3's default is 3s; 5s is the familiar behaviour from most music players.
 */
private const val MAX_SEEK_TO_PREVIOUS_MS = 5_000L

/** Browse nodes whose contents depend on the selected library; re-queried when it changes. */
private val LIBRARY_SCOPED_BROWSE_NODES = listOf(
    MediaItemTree.ROOT_ID,
    MediaItemTree.RECENTLY_PLAYED_ID,
    MediaItemTree.MOST_PLAYED_ID,
    MediaItemTree.RECENTLY_ADDED_ID,
    MediaItemTree.FAVORITES_ID,
    MediaItemTree.ALBUMS_ID,
    MediaItemTree.ARTISTS_ID,
    MediaItemTree.PLAYLISTS_ID,
)

/** Browse nodes carrying the "Continue listening" entry; re-queried when the saved track changes. */
private val RESUME_BEARING_BROWSE_NODES = listOf(
    MediaItemTree.ROOT_ID,
    MediaItemTree.RESUME_ROOT_ID,
)

/**
 * The single [MediaLibraryService] that powers playback on the phone/tablet AND Android Auto. It
 * owns one [ExoPlayer] wrapped in a [MediaLibraryService.MediaLibrarySession]; the browse tree is
 * served from [MediaItemTree].
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject
    lateinit var mediaItemTree: MediaItemTree

    @Inject
    lateinit var queueStore: QueueStore

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var playbackReporter: PlaybackReporter

    @Inject
    lateinit var authRepository: pt.aguiarvieira.jellymusic.domain.repository.AuthRepository

    @Inject
    lateinit var clientProvider: pt.aguiarvieira.jellymusic.data.jellyfin.JellyfinClientProvider

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession

    /** Applies per-track ReplayGain in the audio pipeline; gain is (re)set on transitions/settings. */
    private val gainProcessor = GainAudioProcessor()
    private var replayGainEnabled = true
    private var replayGainPreampDb = 0f

    // Whether the current player's audio sink was built with float output. Float output preserves
    // hi-res FLAC fidelity but routes decoded audio down a sink branch that bypasses our custom
    // gain processor (see buildPlayer), so it's only usable when ReplayGain is off. Flipping the
    // ReplayGain toggle therefore requires rebuilding the player in the other mode (rebuildPlayer).
    private var usingFloatOutput = false

    // Track being reported to the server, and its last observed position.
    private var reportedItemId: String? = null
    private var lastPositionMs: Long = 0L

    /** Reports playback lifecycle to Jellyfin (play counts, resume points, now-playing). */
    private val reporterListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            reportedItemId?.let { playbackReporter.reportStop(it, lastPositionMs) }
            reportedItemId = mediaItem?.mediaId?.removePrefix("track/")
            lastPositionMs = player.currentPosition
            reportedItemId?.let { playbackReporter.reportStart(it, lastPositionMs, currentPlayMethod()) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            lastPositionMs = player.currentPosition
            reportedItemId?.let { playbackReporter.reportProgress(it, lastPositionMs, !isPlaying, currentPlayMethod()) }
            setProgressReporting(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                reportedItemId?.let { playbackReporter.reportStop(it, lastPositionMs) }
                reportedItemId = null
            }
        }

        // A manual scrub jumps the position with no other event, so report it immediately — otherwise
        // the server's resume point/now-playing would stay wrong until the next heartbeat. Auto
        // track-to-track transitions come through onMediaItemTransition, so only handle explicit seeks.
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            lastPositionMs = newPosition.positionMs
            reportedItemId?.let {
                playbackReporter.reportProgress(it, lastPositionMs, !player.isPlaying, currentPlayMethod())
            }
        }
    }

    private fun currentPlayMethod(): PlayMethod {
        val extras = player.currentMediaItem?.mediaMetadata?.extras
        val transcode = StreamSettingsExtras.settingsFrom(extras).transcode
        val isLocal = StreamSettingsExtras.isLocal(extras)
        return if (transcode && !isLocal) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY
    }

    /** Re-applies the ReplayGain level each time the playing track changes. */
    private val gainListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = applyGainForCurrentItem()
    }

    /**
     * DIAGNOSTIC (temporary): logs every audio-sink underrun — the moment the AudioTrack buffer runs
     * dry and playback glitches. `bufferSizeMs` is how much cushion the buffer holds; a small value
     * with `elapsedSinceLastFeedMs` spiking past it is a starved-CPU underrun (our screen-off stutter
     * theory). Correlate these timestamps with the audible stutters, then remove this listener.
     */
    private val underrunLogger = object : AnalyticsListener {
        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            val line = "AUDIO UNDERRUN: bufferSize=$bufferSize bytes, bufferSizeMs=$bufferSizeMs, " +
                "elapsedSinceLastFeedMs=$elapsedSinceLastFeedMs, positionMs=${player.currentPosition}"
            android.util.Log.w(TAG, line)
            recordDiagnostic(line)
        }
    }

    /**
     * DIAGNOSTIC (debug builds only): appends a timestamped line to <externalFilesDir>/underruns.log
     * so underruns can be captured with the device **fully disconnected** — no live adb session, which
     * keeps the phone charging/awake and would mask the very low-power state we're hunting. Reproduce
     * unplugged, then pull afterwards (debug build id has the `.debug` suffix):
     *   adb pull /sdcard/Android/data/pt.aguiarvieira.jellymusic.debug/files/underruns.log
     *
     * The log grows without bound, so it must never run in release — [underrunLogger] is only
     * attached in debug builds (see [buildPlayer]) and this is a second guard on the write itself.
     */
    private fun recordDiagnostic(line: String) {
        if (!BuildConfig.DEBUG) return
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                    .format(java.util.Date())
                java.io.File(getExternalFilesDir(null), "underruns.log").appendText("$stamp  $line\n")
            }
        }
    }

    /**
     * Computes and pushes the effective gain for the current track into [gainProcessor]: the track's
     * Jellyfin normalization gain plus the manual preamp when ReplayGain is on, else unity (bypass).
     */
    private fun applyGainForCurrentItem() {
        val trackGainDb = StreamSettingsExtras.gainDbFrom(player.currentMediaItem?.mediaMetadata?.extras)
        gainProcessor.setGainDb(
            if (replayGainEnabled) (trackGainDb ?: 0f) + replayGainPreampDb else null,
        )
    }

    /**
     * Keeps the shuffle/repeat buttons' icons in sync with the player (whatever toggled them — the
     * in-app UI, the notification, or Android Auto) and persists the modes so they survive a restart.
     */
    private val modeListener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = onPlaybackModesChanged()
        override fun onRepeatModeChanged(repeatMode: Int) = onPlaybackModesChanged()
    }

    private fun onPlaybackModesChanged() {
        mediaSession.setMediaButtonPreferences(mediaButtonPreferences())
        serviceScope.launch { settingsStore.setPlaybackModes(player.shuffleModeEnabled, player.repeatMode) }
    }

    /**
     * Mirrors the player's now-playing state into the home-screen widget's store and refreshes it.
     * The widget renders from that store (not a live player), so it must be kept current here — the
     * one place always alive during playback.
     */
    private val widgetListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) = pushWidgetUpdate()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = pushWidgetUpdate()
        override fun onIsPlayingChanged(isPlaying: Boolean) = pushWidgetUpdate()
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = pushWidgetUpdate()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = pushWidgetUpdate()
        override fun onRepeatModeChanged(repeatMode: Int) = pushWidgetUpdate()
    }

    private fun pushWidgetUpdate() {
        val data = player.nowPlayingWidgetData()
        serviceScope.launch {
            // The write is what actually refreshes the widget: its composition collects this store as
            // state (see NowPlayingWidget), so a write recomposes the live session. updateAll() stays
            // as a belt-and-suspenders trigger for the case where no session is currently running.
            applicationContext.writeNowPlayingWidgetData(data)
            NowPlayingWidget().updateAll(applicationContext)
        }
    }

    /**
     * Persists the play queue on every change, whoever caused it.
     *
     * This has to live in the service, not in the app-side [PlaybackConnection]: Android Auto drives
     * this same player with no app UI alive, so with persistence on the UI side a queue picked in the
     * car was never written. Both restore paths — [onPlaybackResumption] on reconnect and the app's
     * restore-on-launch — then replayed whatever the phone had saved last, making an Auto selection
     * look like a throwaway session.
     */
    private val queuePersistenceListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) =
            persistQueueIfChanged()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = persistQueueIfChanged()

        // Pausing is exactly when the resume point matters, and the position has moved since the last
        // structural change — so save it even though the queue itself is untouched.
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) persistQueue()
        }
    }

    private var lastPersistedCount = -1
    private var lastPersistedIndex = -1

    /** Saves only on a real structural change; timeline events fire far more often than that. */
    private fun persistQueueIfChanged() {
        if (player.mediaItemCount == lastPersistedCount && player.currentMediaItemIndex == lastPersistedIndex) return
        persistQueue()
    }

    private fun persistQueue() {
        val snapshot = queueSnapshot() ?: return
        val trackChanged = player.currentMediaItemIndex != lastPersistedIndex
        lastPersistedCount = player.mediaItemCount
        lastPersistedIndex = player.currentMediaItemIndex
        serviceScope.launch {
            queueStore.save(snapshot)
            // The "Continue listening" entry describes the saved track, so a connected browser
            // (Android Auto) needs to re-read it when that track changes. Best-effort, with the same
            // caveat as LIBRARY_SCOPED_BROWSE_NODES: Auto only re-queries nodes it's looking at.
            if (trackChanged && ::mediaSession.isInitialized) {
                RESUME_BEARING_BROWSE_NODES.forEach {
                    mediaSession.notifyChildrenChanged(it, Int.MAX_VALUE, null)
                }
            }
        }
    }

    /**
     * The current queue as a persistable snapshot, or null when there is nothing worth saving.
     *
     * An empty player never overwrites a stored queue: clearing is an explicit user action that wipes
     * the store itself (see [PlaybackConnection.stop]), while a player is also transiently empty during
     * teardown and while a restore is being loaded in.
     */
    private fun queueSnapshot(): PersistedQueue? {
        val count = player.mediaItemCount
        if (count == 0) return null
        return PersistedQueue(
            items = (0 until count).map { i ->
                val md = player.getMediaItemAt(i).mediaMetadata
                QueueTrack(
                    id = player.getMediaItemAt(i).mediaId.removePrefix("track/"),
                    title = md.title?.toString().orEmpty(),
                    artist = md.artist?.toString().orEmpty(),
                    album = md.albumTitle?.toString(),
                    albumId = StreamSettingsExtras.albumIdFrom(md.extras),
                    artistId = StreamSettingsExtras.artistIdFrom(md.extras),
                    artworkUrl = md.artworkUri?.toString(),
                    durationMs = md.durationMs,
                    normalizationGainDb = StreamSettingsExtras.gainDbFrom(md.extras),
                )
            },
            index = player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = player.currentPosition.coerceAtLeast(0L),
        )
    }

    /** The custom buttons (expanded-notification overflow + Android Auto), reflecting current state. */
    private fun mediaButtonPreferences(): List<CommandButton> = listOf(
        CommandButton.Builder(
            if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF,
        )
            .setDisplayName(if (player.shuffleModeEnabled) "Shuffle on" else "Shuffle off")
            .setSessionCommand(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
            // Overflow slot only → shows in the expanded notification (not the compact view) and Auto.
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            },
        )
            .setDisplayName(
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repeat one"
                    Player.REPEAT_MODE_ALL -> "Repeat all"
                    else -> "Repeat off"
                },
            )
            .setSessionCommand(SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    /**
     * Builds an [ExoPlayer] with the ReplayGain [gainProcessor] installed in its audio pipeline, with
     * all playback listeners attached.
     *
     * [floatOutput] selects the audio-sink output mode, and it is a hard either/or with ReplayGain:
     *
     * `false` (int): the decoder emits native integer PCM, everything flows through DefaultAudioSink's
     * *int16* pipeline branch, and the gain processor runs — this is the only mode ReplayGain works in.
     * DefaultAudioSink only adds custom AudioProcessors on the int16 branch: configure() appends our
     * chain solely in the `!shouldUseFloatOutput` case.
     *
     * `true` (float): enabling float output makes MediaCodecAudioRenderer.getMediaFormat() request
     * ENCODING_PCM_FLOAT straight from the decoder (getFormatSupport reports float as supported), which
     * the FLAC decoder honors for every FLAC (16- or 24-bit). That float PCM then trips
     * shouldUseFloatOutput() and takes the branch that appends only ToFloatPcmAudioProcessor and stops
     * — bypassing the gain processor entirely. So float preserves hi-res fidelity (no forced 16-bit
     * downconvert) but silently disables ReplayGain. media3 offers no way to run a custom processor on
     * the float path, hence the mode switch: we use float only while ReplayGain is off.
     */
    private fun buildPlayer(floatOutput: Boolean): ExoPlayer {
        // Constant-bitrate seeking lets ADTS-AAC/MP3 files be scrubbed even though they carry no seek
        // index — this is what makes seeking work on downloaded (transcoded-AAC) tracks, which are
        // played as local progressive files. Without it, seekTo on those is a no-op.
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(floatOutput)
                .setAudioProcessors(arrayOf(gainProcessor))
                // Ride out audio-thread stalls. The default AudioTrack buffer is ~500ms, but on a busy
                // 2.4GHz environment (Wi-Fi/BT coexistence, screen off) the feed thread can be starved
                // for ~1s at a time — measured via onAudioUnderrun — which drains a 500ms buffer dry and
                // glitches. A 2–5s cushion absorbs those stalls silently. Cost is higher play/pause/skip
                // latency and ~a few MB RAM, both acceptable for a music player.
                .setAudioTrackBufferSizeProvider(
                    DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(2_500_000)
                        .setMaxPcmBufferDurationUs(5_000_000)
                        .build(),
                )
                .build()
        }
        return ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractorsFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Threshold for Player.seekToPrevious(): below it "previous" steps back a track, above it
            // it restarts the current one. Every previous button in the app goes through
            // seekToPrevious(), so setting it here is what makes them all behave the same.
            .setMaxSeekToPreviousPositionMs(MAX_SEEK_TO_PREVIOUS_MS)
            // Hold a partial wake lock (+ Wi-Fi lock) *while playing* so the CPU/radio can't doze mid-
            // track with the screen off and starve the audio pipeline — the classic screen-off stutter.
            // media3 acquires these only during active playback and releases them on pause/stop, so idle
            // battery cost is nil. NETWORK (not LOCAL) because the app streams by default; the added
            // Wi-Fi lock is what keeps streamed playback smooth, and it's harmless for offline downloads.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also {
                it.addListener(reporterListener)
                it.addListener(modeListener)
                it.addListener(widgetListener)
                it.addListener(gainListener)
                it.addListener(queuePersistenceListener)
                // Diagnostic only — see [underrunLogger]. Keep it out of release builds entirely.
                if (BuildConfig.DEBUG) it.addAnalyticsListener(underrunLogger)
            }
    }

    /**
     * Detaches every listener [buildPlayer] attached. All of them read the `player` *field*, which by
     * the time a replaced player is released already points at its successor — so any late callback
     * from the dying player would be applied to the live one (e.g. an `onIsPlayingChanged(false)`
     * cancelling the new player's progress heartbeat). Cheap to do, and it removes the dependency on
     * exactly what media3 dispatches during `release()`.
     */
    private fun detachListeners(target: ExoPlayer) {
        target.removeListener(reporterListener)
        target.removeListener(modeListener)
        target.removeListener(widgetListener)
        target.removeListener(gainListener)
        target.removeListener(queuePersistenceListener)
        if (BuildConfig.DEBUG) target.removeAnalyticsListener(underrunLogger)
    }

    /**
     * Swaps the running player for a fresh one built in the given [floatOutput] mode, carrying over the
     * queue, current index, position, play/pause and shuffle/repeat so the switch is as seamless as
     * possible (a brief re-buffer of the current track is unavoidable). Called only when the ReplayGain
     * toggle flips the required audio-sink mode.
     */
    private fun rebuildPlayer(floatOutput: Boolean) {
        val old = player
        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex.coerceAtLeast(0)
        val position = old.currentPosition.coerceAtLeast(0L)
        val resumePlayback = old.playWhenReady
        val shuffle = old.shuffleModeEnabled
        val repeat = old.repeatMode

        val fresh = buildPlayer(floatOutput)
        fresh.shuffleModeEnabled = shuffle
        fresh.repeatMode = repeat
        if (items.isNotEmpty()) {
            fresh.setMediaItems(items, index, position)
            fresh.playWhenReady = resumePlayback
            fresh.prepare()
        }
        player = fresh
        usingFloatOutput = floatOutput
        mediaSession.setPlayer(fresh)
        detachListeners(old)
        old.release()
    }

    override fun onCreate() {
        super.onCreate()
        // Start in int (ReplayGain-capable) mode; the replayGainSettings collector below rebuilds
        // into float mode if ReplayGain is off (see buildPlayer / rebuildPlayer). At startup nothing
        // is playing yet, so that initial rebuild is seamless.
        player = buildPlayer(floatOutput = false)
        usingFloatOutput = false

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openPlayerPendingIntent())
            .build()

        // Restore persisted shuffle/repeat, then keep them (and the buttons) in sync going forward.
        // Setting the modes here also refreshes the layout via [modeListener] when they differ from
        // the player's defaults. Reads the `player` field lazily so it targets whatever player is
        // current if a rebuild races this.
        serviceScope.launch {
            val modes = settingsStore.playbackModes.first()
            player.shuffleModeEnabled = modes.shuffle
            player.repeatMode = modes.repeatMode
            mediaSession.setMediaButtonPreferences(mediaButtonPreferences())
        }
        pushWidgetUpdate()

        // Keep ReplayGain settings live: re-apply to the current track whenever the toggle or preamp
        // changes (and once on startup to seed the values). Float output (better hi-res FLAC
        // fidelity) is only possible while ReplayGain is off, so switch the player's audio-sink mode
        // to match whenever the toggle flips.
        serviceScope.launch {
            settingsStore.replayGainSettings.collect { rg ->
                replayGainEnabled = rg.enabled
                replayGainPreampDb = rg.preampDb
                val wantFloatOutput = !rg.enabled
                if (wantFloatOutput != usingFloatOutput) rebuildPlayer(floatOutput = wantFloatOutput)
                applyGainForCurrentItem()
            }
        }

        // Library selection is a phone-only action (Android Auto is read-only for it). When it
        // changes in the app, the browse nodes now serve a different library — tell any subscribed
        // browser (Android Auto) to re-query. Note: Auto ignores this for tabs that aren't currently
        // focused (verified in DHU) and only re-reads a node when navigated to, so this is best-effort;
        // the reliable path is Auto re-querying on (re)connect / tab open. Skip the initial emission.
        serviceScope.launch {
            settingsStore.selectedLibrary
                .drop(1)
                .distinctUntilChanged()
                .collect { lib ->
                    android.util.Log.d(TAG, "selectedLibrary changed -> ${lib?.id} (${lib?.name}); notifying browse nodes")
                    LIBRARY_SCOPED_BROWSE_NODES.forEach {
                        mediaSession.notifyChildrenChanged(it, Int.MAX_VALUE, null)
                    }
                }
        }

        // The progress-report heartbeat is started/stopped by onIsPlayingChanged (see reporterListener)
        // so it only ticks while audio is actually playing — no periodic wakeup sitting paused/stopped.
    }

    /**
     * Runs the progress-report heartbeat for as long as audio is playing, and nothing when it isn't.
     * Driven by [Player.Listener.onIsPlayingChanged]; the job is cancelled the moment playback pauses
     * or stops, so a paused/idle session costs no periodic wakeups.
     */
    private var progressReportJob: Job? = null

    private fun setProgressReporting(playing: Boolean) {
        if (playing) {
            if (progressReportJob?.isActive == true) return
            progressReportJob = serviceScope.launch {
                while (isActive) {
                    delay(PROGRESS_REPORT_INTERVAL_MS)
                    lastPositionMs = player.currentPosition
                    reportedItemId?.let {
                        playbackReporter.reportProgress(it, lastPositionMs, isPaused = false, currentPlayMethod())
                    }
                    // Keep the local resume point fresh too: the queue is otherwise only written on
                    // structural changes, on pause and in onDestroy, so a kill *while playing* would
                    // resume from wherever playback last paused. Piggybacks this existing tick.
                    persistQueue()
                }
            }
        } else {
            progressReportJob?.cancel()
            progressReportJob = null
        }
    }

    /** Tapping the media notification opens the app on the full-screen player. */
    private fun openPlayerPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        mediaSession

    /**
     * Restores the persisted Jellyfin session if none is active. Idempotent and cheap when already
     * signed in; needed because the browse/playback entry points can run before (or without) the app
     * UI, which is otherwise the only thing that restores the session.
     */
    private suspend fun ensureSession() {
        if (clientProvider.session.value == null) authRepository.restoreSession()
    }

    /**
     * The persisted queue rebuilt into playable items at its saved index/position, for every
     * cold-resume entry point (see [LibraryCallback.onPlaybackResumption] and
     * [LibraryCallback.onSetMediaItems]). Throws when there is nothing saved, which is how media3
     * expects "not resumable" to be reported.
     *
     * When [isForPlayback] is false the caller only renders metadata, so build just the current item.
     */
    private suspend fun resumeItems(isForPlayback: Boolean): MediaSession.MediaItemsWithStartPosition {
        ensureSession()
        val saved = queueStore.load()
        if (saved == null || saved.items.isEmpty()) {
            throw UnsupportedOperationException("No saved queue to resume")
        }
        val index = saved.index.coerceIn(0, saved.items.lastIndex)
        val settings = settingsStore.streamSettings.first()
        val source = if (isForPlayback) saved.items else listOf(saved.items[index])
        val items = source.map { mediaItemTree.trackMediaItem(it.toTrack(), settings) }
        return MediaSession.MediaItemsWithStartPosition(
            items,
            if (isForPlayback) index else 0,
            saved.positionMs.coerceAtLeast(0L),
        )
    }

    override fun onDestroy() {
        reportedItemId?.let { playbackReporter.reportStop(it, player.currentPosition) }
        // Final snapshot before teardown, so the stored resume point is the position we actually
        // stopped at rather than the last structural change. Bounded and blocking on purpose:
        // [serviceScope] is cancelled just below and the process often dies right after, so a
        // fire-and-forget write here would usually not land.
        queueSnapshot()?.let { snapshot ->
            runBlocking { withTimeoutOrNull(QUEUE_SAVE_TIMEOUT_MS) { queueStore.save(snapshot) } }
        }
        mediaSession.release()
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        /** Advertise the custom shuffle/repeat commands and publish the initial button layout. */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setMediaButtonPreferences(mediaButtonPreferences())
                .build()
        }

        /**
         * Diagnostic: log which nodes Android Auto subscribes to and when. Tells us whether AA keeps a
         * live subscription to the top-level tabs (so notifyChildrenChanged could refresh them) or only
         * subscribes to the currently-focused node. Behaviour is otherwise the framework default.
         */
        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            android.util.Log.d(TAG, "onSubscribe($parentId) by ${browser.packageName}")
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onUnsubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
        ): ListenableFuture<LibraryResult<Void>> {
            android.util.Log.d(TAG, "onUnsubscribe($parentId) by ${browser.packageName}")
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        /** Toggle shuffle / cycle repeat when a button is tapped; [modeListener] refreshes the icons. */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_TOGGLE_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                CMD_CYCLE_REPEAT -> player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * Resolve items played from the Android Auto browse tree into playable ones. Media3 strips the
         * stream URI from a MediaItem when it crosses the process boundary, so the raw items Auto sends
         * back carry only their mediaId — [MediaItemTree.resolveForPlayback] re-attaches the URI (and
         * expands an album/playlist mediaId into its tracks). Without this, tapping a track in Auto is
         * a no-op.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = serviceScope.future {
            ensureSession()
            android.util.Log.d(TAG, "onAddMediaItems: ${mediaItems.map { it.mediaId }}")
            val resolved = mediaItemTree.resolveForPlayback(mediaItems)
            val haveUris = resolved.map { it.localConfiguration?.uri != null }
            android.util.Log.d(TAG, "onAddMediaItems resolved ${resolved.size} items, uris=$haveUris")
            resolved
        }

        /**
         * Play a browse selection. Delegates to [onAddMediaItems] for the normal case, but
         * intercepts the "Continue listening" marker item ([MediaItemTree.RESUME_ITEM_ID]), which
         * stands in for the whole persisted queue and carries no URI of its own: playing it must
         * restore every track *and* the saved index/position, which only this callback can express.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future {
            if (mediaItems.singleOrNull()?.mediaId == MediaItemTree.RESUME_ITEM_ID) {
                android.util.Log.d(TAG, "onSetMediaItems: resuming saved queue")
                return@future resumeItems(isForPlayback = true)
            }
            ensureSession()
            MediaSession.MediaItemsWithStartPosition(
                mediaItemTree.resolveForPlayback(mediaItems),
                startIndex,
                startPositionMs,
            )
        }

        /**
         * Resume from cold (Bluetooth/media button, or the system's media resumption UI) using the
         * saved queue. Only reachable because the app declares a manifest ACTION_MEDIA_BUTTON
         * receiver — see the comment on it in AndroidManifest.xml.
         *
         * [isForPlayback] is false when the caller only wants metadata to render a resumption
         * affordance (media3 asks this at device boot to populate the notification-tray player), so
         * skip rebuilding the rest of the queue for it.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future {
            resumeItems(isForPlayback)
        }

        /**
         * Android Auto asks for the "recent" tree when the car connects, to offer resuming without
         * touching the phone. Media3 only answers that itself for the system UI controller, so serve
         * it here: a root whose single child is the "Continue listening" item. With no saved queue
         * there is nothing to resume — fall back to the normal root so the car still gets a library.
         */
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (params?.isRecent != true) {
                return Futures.immediateFuture(LibraryResult.ofItem(mediaItemTree.rootItem(), params))
            }
            return serviceScope.future {
                val hasResume = mediaItemTree.resumeItem() != null
                android.util.Log.d(TAG, "onGetLibraryRoot(isRecent) by ${browser.packageName}, hasResume=$hasResume")
                val root = if (hasResume) mediaItemTree.recentRootItem() else mediaItemTree.rootItem()
                LibraryResult.ofItem(root, params)
            }
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            serviceScope.future {
                // Android Auto can start this service cold (car connected, app never opened), in which
                // case no Jellyfin session has been restored and every query returns empty. Restore it
                // before serving any browse request so the tree is populated without opening the app.
                // The resume node is the exception: it's served straight from the persisted queue, so
                // don't make the car wait on the network for the one thing it asks for on connect.
                if (parentId != MediaItemTree.RESUME_ROOT_ID) ensureSession()
                // Android Auto asks for every child in one call (page=0, pageSize=Int.MAX_VALUE), so the
                // whole list crosses the Binder in a single transaction. A large node (typically Albums)
                // exceeds the ~1MB transaction limit and throws TransactionTooLargeException, which surfaces
                // as an *empty* tab. Cap the payload to keep every node well under that limit.
                val children = mediaItemTree.getChildren(parentId).let {
                    if (it.size > MAX_CHILDREN_PER_NODE) it.subList(0, MAX_CHILDREN_PER_NODE) else it
                }
                android.util.Log.d(TAG, "onGetChildren($parentId) -> ${children.size} items")
                LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
            }
    }
}
