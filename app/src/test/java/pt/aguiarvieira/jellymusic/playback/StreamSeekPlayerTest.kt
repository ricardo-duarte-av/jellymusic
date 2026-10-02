package pt.aguiarvieira.jellymusic.playback

import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The session listens to the player through [StreamSeekPlayer]'s listener wrapper. It once swallowed
 * every callback but one (Kotlin `by` delegation skips Java default methods), which blanked the
 * now-playing screen and mini-player. Every callback must reach the session's listener.
 */
class StreamSeekPlayerTest {

    private fun seekPlayer(inner: Player): StreamSeekPlayer {
        val cache = mockk<StreamCache> { every { completed } returns MutableSharedFlow() }
        val handler = StreamSeekHandler({ inner }, cache, CoroutineScope(Dispatchers.Unconfined))
        return StreamSeekPlayer(inner, handler)
    }

    @Test
    fun `callbacks reach the listener through the wrapper`() {
        val inner = mockk<Player>(relaxed = true)
        val registered = slot<Player.Listener>()
        every { inner.addListener(capture(registered)) } returns Unit
        val listener = mockk<Player.Listener>(relaxed = true)

        seekPlayer(inner).addListener(listener)
        assertNotNull(registered.captured)
        registered.captured.onIsPlayingChanged(true)
        registered.captured.onRepeatModeChanged(Player.REPEAT_MODE_ALL)
        registered.captured.onPlaybackStateChanged(Player.STATE_READY)

        verify { listener.onIsPlayingChanged(true) }
        verify { listener.onRepeatModeChanged(Player.REPEAT_MODE_ALL) }
        verify { listener.onPlaybackStateChanged(Player.STATE_READY) }
    }

    @Test
    fun `removing a listener unregisters its wrapper`() {
        val inner = mockk<Player>(relaxed = true)
        val registered = slot<Player.Listener>()
        every { inner.addListener(capture(registered)) } returns Unit
        val listener = mockk<Player.Listener>(relaxed = true)

        val player = seekPlayer(inner)
        player.addListener(listener)
        player.removeListener(listener)

        verify { inner.removeListener(registered.captured) }
    }
}
