package ink.enoch.glyphplayer.utils

import org.junit.Assert.*
import org.junit.Test

class GlyphLayoutTest {
    private fun channels(mask: Long) = (0..35).filter { mask and (1L shl it) != 0L }
    private fun strip(value: Float, strip: Char, reverse: Boolean, dot: Boolean) = channels(GlyphLayout.stripMask(value, strip, reverse, dot))
    private fun flow(value: Float, start: Char, reverse: Boolean, dot: Boolean) = channels(GlyphLayout.flowMask(value, start, reverse, dot))
    @Test fun reverseChangesTheOriginForEveryStrip() {
        assertEquals(listOf(20, 21), strip(0.1f, 'a', false, false))
        assertEquals(listOf(29, 30), strip(0.1f, 'a', true, false))
        assertEquals(listOf(31, 32), strip(0.3f, 'b', false, false))
        assertEquals(listOf(34, 35), strip(0.3f, 'b', true, false))
        assertEquals(listOf(0, 1), strip(0.1f, 'c', false, false))
        assertEquals(listOf(18, 19), strip(0.1f, 'c', true, false))
    }

    @Test fun reverseAlsoMirrorsDotAndFlowDirections() {
        assertEquals(listOf(22), strip(0.25f, 'a', false, true))
        assertEquals(listOf(28), strip(0.25f, 'a', true, true))
        assertEquals((20..30).toList() + listOf(31, 32), flow(0.35f, 'a', false, false))
        assertEquals(listOf(18, 19) + (20..30).toList(), flow(0.35f, 'a', true, false))
        assertEquals(listOf(31), flow(11f / 36, 'a', false, true))
        assertEquals(listOf(19), flow(11f / 36, 'a', true, true))
    }

    @Test fun silenceAndInvalidLevelsStayOffInBothModes() {
        for (dot in listOf(false, true)) {
            assertTrue(strip(0f, 'a', true, dot).isEmpty())
            assertTrue(strip(Float.NaN, 'b', false, dot).isEmpty())
            assertTrue(flow(0f, 'c', true, dot).isEmpty())
            assertEquals(36, flow(2f, 'a', false, false).size)
        }
    }
}
