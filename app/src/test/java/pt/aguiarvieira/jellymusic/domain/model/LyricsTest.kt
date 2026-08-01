package pt.aguiarvieira.jellymusic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTest {

    private fun synced(vararg starts: Long) = Lyrics(
        lines = starts.mapIndexed { index, start -> LyricLine("line $index", start) },
        synced = true,
    )

    @Test
    fun `active line is the last one whose start has passed`() {
        val lyrics = synced(1_000, 5_000, 9_000)
        assertEquals(0, lyrics.activeLineAt(1_000))
        assertEquals(0, lyrics.activeLineAt(4_999))
        assertEquals(1, lyrics.activeLineAt(5_000))
        assertEquals(2, lyrics.activeLineAt(120_000))
    }

    @Test
    fun `nothing is active during an intro before the first line`() {
        assertEquals(-1, synced(8_000).activeLineAt(0))
        assertEquals(-1, synced(8_000).activeLineAt(7_999))
    }

    @Test
    fun `unsynced lyrics never have an active line`() {
        val lyrics = Lyrics(
            lines = listOf(LyricLine("first", null), LyricLine("second", null)),
            synced = false,
        )
        assertEquals(-1, lyrics.activeLineAt(0))
        assertEquals(-1, lyrics.activeLineAt(60_000))
    }

    @Test
    fun `empty lyrics report no active line`() {
        assertEquals(-1, Lyrics(lines = emptyList(), synced = true).activeLineAt(1_000))
    }
}
