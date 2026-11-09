package ink.enoch.glyphplayer.utils

import org.junit.Assert.*
import org.junit.Test

class MusicEnvelopeTest {
    private fun settledMean(input: SpectrumLevels): SpectrumLevels {
        val envelope = MusicEnvelope()
        var low = 0f
        var mid = 0f
        var high = 0f
        repeat(500) { frame ->
            val result = envelope.process(input, 1.0 / 30)
            if (frame >= 400) { low += result.low; mid += result.mid; high += result.high }
        }
        return SpectrumLevels(low / 100, mid / 100, high / 100)
    }

    @Test fun quietAndLoudTracksBothUseTheAvailableLedRange() {
        val quiet = settledMean(SpectrumLevels(0.001f, 0.0005f, 0.0002f))
        val loud = settledMean(SpectrumLevels(0.3f, 0.15f, 0.06f))
        assertTrue("Quiet track should be visible: $quiet", quiet.low > 0.6f)
        assertTrue("Loud track should retain dynamics: $loud", loud.low > 0.6f)
        assertEquals(quiet.low, loud.low, 0.2f)
        assertTrue(quiet.low > quiet.mid && quiet.mid > quiet.high)
        assertTrue(loud.low > loud.mid && loud.mid > loud.high)
    }

    @Test fun silenceFallsToExactlyZeroWithoutGainPumping() {
        val envelope = MusicEnvelope()
        repeat(200) { envelope.process(SpectrumLevels(0.1f, 0.05f, 0.02f), 1.0 / 30) }
        assertTrue(envelope.process(SpectrumLevels.SILENT, 1.0 / 30).low > 0f)
        var result = SpectrumLevels.SILENT
        repeat(120) { result = envelope.process(SpectrumLevels.SILENT, 1.0 / 30) }
        assertEquals(SpectrumLevels.SILENT, result)
    }

    @Test fun valleysBecomeVisibleBetweenBeatsInsteadOfStayingSaturated() {
        val envelope = MusicEnvelope()
        repeat(300) { envelope.process(SpectrumLevels(0.1f, 0.05f, 0.02f), 1.0 / 30) }
        var peak = 0f
        repeat(5) { peak = maxOf(peak, envelope.process(SpectrumLevels(0.1f, 0.05f, 0.02f), 1.0 / 30).low) }
        var valley = 1f
        repeat(20) { valley = minOf(valley, envelope.process(SpectrumLevels(0.001f, 0.0005f, 0.0002f), 1.0 / 30).low) }
        assertTrue("Peak=$peak valley=$valley", peak - valley > 0.3f)
    }

    @Test fun invalidFramesCannotPoisonLaterMusic() {
        val envelope = MusicEnvelope()
        repeat(100) {
            assertEquals(SpectrumLevels.SILENT, envelope.process(SpectrumLevels(Float.NaN, Float.POSITIVE_INFINITY, -1f), Double.NaN))
        }
        val output = envelope.process(SpectrumLevels(0.2f, 0.1f, 0.05f), 1.0 / 30)
        assertTrue(output.low.isFinite() && output.low in 0f..1f)
    }
}
