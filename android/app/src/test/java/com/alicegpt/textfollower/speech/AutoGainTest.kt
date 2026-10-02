package com.alicegpt.textfollower.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AutoGainTest {

    private fun tone(amplitude: Double, n: Int = 1600, phase: Int = 0) =
        ShortArray(n) { (amplitude * 32767 * sin(2 * PI * 220 * (it + phase) / 16000.0)).toInt().toShort() }

    private fun rms(b: ShortArray) = sqrt(b.sumOf { (it / 32768.0) * (it / 32768.0) } / b.size)

    /** Речь всплесками по полсекунды с паузами шума, как при чтении вслух. */
    private fun feed(g: AutoGain, amplitude: Double, seconds: Int): ShortArray {
        var last = ShortArray(0)
        var t = 0
        repeat(seconds * 10) { i ->
            val speaking = (i % 8) < 5
            val b = if (speaking) tone(amplitude, phase = t) else ShortArray(1600) { ((i * 7919 + it * 31) % 9 - 4).toShort() } // тихий шум
            g.process(b, b.size)
            if (speaking) last = b
            t += 1600
        }
        return last
    }

    @Test
    fun quietSpeechIsBoostedTowardsTarget() {
        val g = AutoGain().apply { sensitivity = 80 }
        val last = feed(g, 0.01, 8)
        assertTrue("после усиления RMS ${rms(last)}", rms(last) > 0.04)
    }

    @Test
    fun veryQuietSpeechIsBoostedToo() {
        val g = AutoGain().apply { sensitivity = 100 }
        val last = feed(g, 0.003, 12)
        assertTrue("после усиления RMS ${rms(last)}", rms(last) > 0.02)
    }

    @Test
    fun higherSensitivityGivesStrongerBoost() {
        val low = rms(feed(AutoGain().apply { sensitivity = 10 }, 0.005, 12))
        val high = rms(feed(AutoGain().apply { sensitivity = 90 }, 0.005, 12))
        assertTrue("низкая $low, высокая $high", high > low * 2)
    }

    @Test
    fun loudSignalIsNeverClipped() {
        val g = AutoGain().apply { sensitivity = 100 }
        repeat(30) {
            val b = tone(0.95, phase = it * 1600)
            g.process(b, b.size)
            assertTrue(b.all { abs(it.toInt()) <= 32767 })
        }
    }

    @Test
    fun silenceStaysQuietAndLevelIsZero() {
        val g = AutoGain().apply { sensitivity = 100 }
        val b = ShortArray(1600)
        assertEquals(0f, g.process(b, b.size), 0.001f)
        assertTrue(b.all { it.toInt() == 0 })
    }

    @Test
    fun levelGrowsWithLoudness() {
        val quiet = AutoGain().process(tone(0.005), 1600)
        val loud = AutoGain().process(tone(0.3), 1600)
        assertTrue(loud > quiet)
        assertTrue(loud in 0f..1f)
    }
}
