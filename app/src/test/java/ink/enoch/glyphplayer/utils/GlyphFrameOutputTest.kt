package ink.enoch.glyphplayer.utils

import org.junit.Assert.*
import org.junit.Test

class GlyphFrameOutputTest {
    @Test fun percentChangesWithinTheSameLedDoNotWriteAnotherFrame() {
        val writes = mutableListOf<Long>()
        val output = GlyphFrameOutput(writes::add)
        for (percent in 0..100_000) output.show(GlyphLayout.flowMask(percent / 100_000f, 'a', false, false))
        assertEquals(37, writes.size) // off + the 36 distinct bar lengths
        assertEquals((1L shl 36) - 1, writes.last())
    }

    @Test fun failedWritesAreRetriedAndNewSessionsInvalidateTheCache() {
        var attempts = 0
        val output = GlyphFrameOutput {
            attempts++
            if (attempts == 1) throw IllegalStateException("Disconnected")
        }
        try { output.show(1L); fail("Expected a failed hardware write") } catch (_: IllegalStateException) { }
        output.show(1L)
        output.show(1L)
        assertEquals(2, attempts)
        output.reset()
        output.show(1L)
        assertEquals(3, attempts)
    }
}
