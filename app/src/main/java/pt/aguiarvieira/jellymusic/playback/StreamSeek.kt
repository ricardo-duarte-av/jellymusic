package pt.aguiarvieira.jellymusic.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Seeking in a transcode that started playing before it had fully downloaded.
 *
 * A transcode has no known length until its download completes. If it's opened before that (see
 * [StreamCacheDataSource]), the player decides once, for the whole play, that it can't be seeked.
 * The download usually completes seconds later, so a seek is honoured by reloading the item from the
 * now-complete file and seeking in that: a brief re-buffer, on that seek only. A seek made before the
 * download completes is held and applied the moment it does.
 */
class StreamSeekHandler(
    private val player: () -> Player,
    private val streamCache: StreamCache,
    private val scope: CoroutineScope,
) {
    private class Pending(val mediaId: String, val positionMs: Long)

    private var pending: Pending? = null
    private var reloads = 0

    init {
        scope.launch {
            streamCache.completed.collect { trackId ->
                if (pending?.mediaId?.removePrefix("track/") == trackId) apply()
            }
        }
    }

    /** Whether [seekTo] has to take over a seek in the current item. */
    fun handles(p: Player): Boolean =
        StreamCache.isStreamUri(p.currentMediaItem?.localConfiguration?.uri) && !p.isCurrentMediaItemSeekable

    /** Seeks the current (unseekable, streamed) item to [positionMs]. Main thread. */
    fun seekTo(positionMs: Long) {
        val mediaId = player().currentMediaItem?.mediaId ?: return
        pending = Pending(mediaId, positionMs)
        scope.launch {
            val complete = withContext(Dispatchers.IO) {
                runCatching { streamCache.isTrackComplete(mediaId.removePrefix("track/")) }.getOrDefault(false)
            }
            if (complete) apply()
        }
    }

    private fun apply() {
        val target = pending ?: return
        pending = null
        val p = player()
        val item = p.currentMediaItem
        if (item?.mediaId != target.mediaId) return
        val index = p.currentMediaItemIndex
        // A different URI makes the player prepare the item afresh, this time from a complete file.
        val trackId = target.mediaId.removePrefix("track/")
        p.replaceMediaItem(index, item.buildUpon().setUri(StreamCache.streamUri(trackId, ++reloads)).build())
        p.seekTo(index, target.positionMs)
    }
}

/**
 * The player as the media session sees it: it advertises seeking for streamed items even while the
 * underlying player can't seek them yet, and routes those seeks to [seeks]. Without advertising the
 * command, controllers (the app, the notification, Android Auto) wouldn't even send the seek.
 */
class StreamSeekPlayer(
    player: Player,
    private val seeks: StreamSeekHandler,
) : ForwardingPlayer(player) {

    private val wrappedListeners = HashMap<Player.Listener, Player.Listener>()

    override fun getAvailableCommands(): Player.Commands = withStreamSeek(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean =
        super.isCommandAvailable(command) || (command == COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM && seeks.handles(this))

    override fun seekTo(positionMs: Long) {
        if (seeks.handles(this)) seeks.seekTo(positionMs) else super.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex == currentMediaItemIndex && seeks.handles(this)) {
            seeks.seekTo(positionMs)
        } else {
            super.seekTo(mediaItemIndex, positionMs)
        }
    }

    // The session takes the available commands from this callback's argument, so it needs the same
    // adjustment as getAvailableCommands().
    override fun addListener(listener: Player.Listener) {
        val wrapped = object : Player.Listener by listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
                listener.onAvailableCommandsChanged(withStreamSeek(availableCommands))
        }
        wrappedListeners[listener] = wrapped
        super.addListener(wrapped)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(wrappedListeners.remove(listener) ?: listener)
    }

    private fun withStreamSeek(commands: Player.Commands): Player.Commands =
        if (seeks.handles(this) && !commands.contains(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            commands.buildUpon().add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).build()
        } else {
            commands
        }
}
