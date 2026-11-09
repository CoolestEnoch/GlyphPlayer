package ink.enoch.glyphplayer.utils

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioSpectrumTest {
    private fun tone(frequency: Double, amplitude: Double = 0.1) = ShortArray(2048) {
        (32767 * amplitude * sin(2 * PI * frequency * it / 44100)).toInt().toShort()
    }

    @Test fun silenceAndDcDoNotLightAnyBand() {
        val analyzer = PcmSpectrumAnalyzer()
        assertEquals(SpectrumLevels.SILENT, analyzer.analyze(ShortArray(2048)))
        assertEquals(SpectrumLevels.SILENT, analyzer.analyze(ShortArray(2048) { 1200 }))
    }

    @Test fun microphoneTonesReachTheCorrectStrip() {
        val analyzer = PcmSpectrumAnalyzer()
        val bass = analyzer.analyze(tone(100.0))
        val voice = analyzer.analyze(tone(1000.0))
        val treble = analyzer.analyze(tone(5000.0))
        assertTrue(bass.low > 0.5f && bass.low > bass.mid && bass.low > bass.high)
        assertTrue(voice.mid > 0.5f && voice.mid > voice.low && voice.mid > voice.high)
        assertTrue(treble.high > 0.5f && treble.high > treble.low && treble.high > treble.mid)
    }

    @Test fun quietMicrophoneAudioStillProducesVisibleLevels() {
        val analyzer = PcmSpectrumAnalyzer()
        val quiet = analyzer.analyze(tone(1000.0, 0.005)).mid
        val loud = analyzer.analyze(tone(1000.0, 0.5)).mid
        assertTrue(quiet > 0.1f)
        assertTrue(loud > quiet)
        assertTrue(loud <= 1f)
    }

    @Test fun playbackPcmKeepsAmplitudeChangesBeforeTheEnvelope() {
        val spectrum = PcmSpectrumAnalyzer()
        val quiet = spectrum.musicMagnitudes(tone(1000.0, 0.01)).mid
        val loud = spectrum.musicMagnitudes(tone(1000.0, 0.1)).mid
        assertEquals(10f, loud / quiet, 0.1f)
    }

    @Test fun pcmThroughEnvelopeAndGlyphUsesTheWholeStrip() {
        val spectrum = PcmSpectrumAnalyzer()
        val envelope = MusicEnvelope()
        val positions = mutableSetOf<Long>()
        repeat(5) {
            for (frame in 0..20) {
                val amount = if (frame <= 10) frame / 10.0 else (20 - frame) / 10.0
                val levels = envelope.process(spectrum.musicMagnitudes(tone(1000.0, 0.2 * amount)), 1.0 / 30)
                positions.add(GlyphLayout.stripMask(levels.mid, 'b', false, true))
                assertTrue("A pure mid tone must not amplify FFT leakage into other strips", levels.low < 0.01f && levels.high < 0.01f)
            }
        }
        assertTrue("Expected all five B dot positions plus silence, got $positions", positions.size >= 6)
    }

    @Test fun visualizerUsesMillihertzAndPackedBinIndices() {
        fun bandAt(bin: Int): SpectrumLevels {
            val fft = ByteArray(1024)
            fft[2 * bin] = 64
            return visualizerSpectrum(fft, 44100000)
        }
        assertEquals(SpectrumLevels(1f, 0f, 0f), bandAt(2))
        assertEquals(SpectrumLevels(0f, 1f, 0f), bandAt(23))
        assertEquals(SpectrumLevels(0f, 0f, 1f), bandAt(116))
    }

    @Test fun silentVisualizerAndInvalidRateStayOff() {
        assertEquals(SpectrumLevels.SILENT, visualizerSpectrum(ByteArray(1024), 44100000))
        assertEquals(SpectrumLevels.SILENT, visualizerSpectrum(ByteArray(1024) { 127 }, 0))
    }

    @Test fun smoothingEventuallyReachesSilenceInsteadOfLeavingOneLedOn() {
        var levels = SpectrumLevels(1f, 1f, 1f)
        repeat(40) { levels = SpectrumLevels.SILENT.smooth(levels) }
        assertEquals(SpectrumLevels.SILENT, levels)
    }
}
