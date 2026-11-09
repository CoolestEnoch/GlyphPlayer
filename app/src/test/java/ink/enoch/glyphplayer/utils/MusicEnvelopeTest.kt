package ink.enoch.glyphplayer.utils

import org.junit.Assert.*
import org.junit.Test

class MusicEnvelopeTest {
    private fun settled(input: SpectrumLevels): SpectrumLevels {
        val envelope = MusicEnvelope()
        var result = SpectrumLevels.SILENT
        repeat(150) { result = envelope.process(input, 1.0 / 30) }
        return result
    }

    @Test fun quietAndLoudBandsCanEachUseTheirStripRange() {
        for (input in listOf(SpectrumLevels(0.001f, 0.0005f, 0.0002f), SpectrumLevels(0.3f, 0.15f, 0.06f))) {
            val levels = settled(input)
            assertTrue("Weak bands must not be pinned by the strongest band: $levels", minOf(levels.low, levels.mid, levels.high) > 0.9f)
        }
    }

    @Test fun moderatelyCompressedMusicStillHasVisiblePeaksAndValleys() {
        val envelope = MusicEnvelope()
        repeat(30) { envelope.process(SpectrumLevels(0.1f, 0.02f, 0.003f), 1.0 / 30) }
        var peak = 0f
        var valley = 1f
        repeat(8) {
            repeat(6) {
                peak = maxOf(peak, envelope.process(SpectrumLevels(0.1f, 0.02f, 0.003f), 1.0 / 30).high)
            }
            repeat(6) {
                valley = minOf(valley, envelope.process(SpectrumLevels(0.08f, 0.016f, 0.0024f), 1.0 / 30).high)
            }
        }
        assertTrue("20% audio modulation must not collapse to a few middle LEDs: peak=$peak valley=$valley", peak - valley > 0.55f)
    }

    @Test fun strongBeatsTraverseMostOfTheDotPositions() {
        val envelope = MusicEnvelope()
        val positions = mutableSetOf<Long>()
        repeat(8) {
            for (frame in 0..20) {
                val amount = if (frame <= 10) frame / 10f else (20 - frame) / 10f
                val result = envelope.process(SpectrumLevels(amount * 0.1f, amount * 0.02f, amount * 0.003f), 1.0 / 30)
                positions.add(GlyphLayout.stripMask(result.low, 'a', false, true))
            }
        }
        assertTrue("Expected travel along A, got ${positions.size} positions", positions.size >= 9)
    }

    @Test fun silenceFallsToExactlyZeroPromptly() {
        val envelope = MusicEnvelope()
        repeat(30) { envelope.process(SpectrumLevels(0.1f, 0.05f, 0.02f), 1.0 / 30) }
        assertTrue(envelope.process(SpectrumLevels.SILENT, 1.0 / 30).low > 0f)
        var result = SpectrumLevels.SILENT
        repeat(12) { result = envelope.process(SpectrumLevels.SILENT, 1.0 / 30) }
        assertEquals(SpectrumLevels.SILENT, result)
    }

    @Test fun constantAudioDoesNotInventOscillation() {
        val envelope = MusicEnvelope()
        val input = SpectrumLevels(0.2f, 0.1f, 0.05f)
        repeat(30) { envelope.process(input, 1.0 / 30) }
        val reference = envelope.process(input, 1.0 / 30)
        repeat(300) { assertEquals(reference, envelope.process(input, 1.0 / 30)) }
    }

    @Test fun oldLoudPassagesExpireInsteadOfSuppressingQuietMusicForever() {
        val envelope = MusicEnvelope()
        repeat(30) { envelope.process(SpectrumLevels(0.5f, 0.2f, 0.1f), 1.0 / 30) }
        var result = SpectrumLevels.SILENT
        repeat(120) { result = envelope.process(SpectrumLevels(0.005f, 0.002f, 0.001f), 1.0 / 30) }
        assertTrue(minOf(result.low, result.mid, result.high) > 0.9f)
    }

    @Test fun rollingHistorySurvivesLongStreamsAndExpiresByTimeAtDifferentFrameRates() {
        val longStream = MusicEnvelope()
        repeat(100_000) { frame ->
            val value = 1f / (1f + frame * 0.00001f)
            val result = longStream.process(SpectrumLevels(value, value * 0.2f, value * 0.05f), 1.0 / 240)
            assertTrue(result.low.isFinite() && result.low in 0f..1f)
        }
        for (fps in listOf(15, 30, 60)) {
            val envelope = MusicEnvelope()
            repeat(fps) { envelope.process(SpectrumLevels(0.5f, 0.2f, 0.1f), 1.0 / fps) }
            var result = SpectrumLevels.SILENT
            repeat(4 * fps) { result = envelope.process(SpectrumLevels(0.005f, 0.002f, 0.001f), 1.0 / fps) }
            assertTrue("History must expire at $fps fps", minOf(result.low, result.mid, result.high) > 0.9f)
        }
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
