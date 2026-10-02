package com.alicegpt.textfollower.tracking

import com.alicegpt.textfollower.testutil.Noise
import com.alicegpt.textfollower.testutil.Runner
import com.alicegpt.textfollower.testutil.SpeechSim
import com.alicegpt.textfollower.text.Doc
import com.alicegpt.textfollower.text.TextLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Проверка на настоящей книге из assets. Текст читается из файла — в коде тестов его нет.
 * В книге много повторяющихся оборотов, поэтому это жёсткая проверка на ложные совпадения.
 */
class BookSimulationTest {

    companion object {
        private val file = File("src/main/assets/odyssey_zhukovsky.txt")
        private var doc: Doc? = null

        @BeforeClass
        @JvmStatic
        fun load() {
            if (file.exists()) doc = Doc.parse(TextLoader.load(file.readBytes()))
        }
    }

    private fun book(): Doc {
        assumeTrue("нет ${file.absolutePath}", doc != null)
        return doc!!
    }

    @Test
    fun structureIsParsedCorrectly() {
        val d = book()
        val toc = d.sections.filter { it.inToc }
        assertEquals(25, toc.size) // 24 песни и примечания
        assertEquals("Песнь первая", toc.first().title)
        assertEquals("Примечания", toc.last().title)
        assertEquals(2411, d.markerRanges.size / 2) // номера строк [5], [10], …
        assertTrue("слов ${d.size}", d.size in 95_000..105_000)
        // номера строк и заголовки не попали в слова: ни одно слово не лежит внутри заголовка
        for (i in 0 until d.headingRanges.size / 2) {
            val from = d.headingRanges[2 * i]
            val to = d.headingRanges[2 * i + 1]
            assertFalse("слово внутри заголовка", d.wordStart.any { it in from until to })
        }
        for (i in 0 until d.markerRanges.size / 2) {
            val text = d.text.substring(d.markerRanges[2 * i], d.markerRanges[2 * i + 1])
            assertTrue(text, Regex("\\[\\d+]").matches(text))
        }
        // секции покрывают весь текст и не слишком большие для TextView
        assertEquals(d.text.length, d.sections.last().end)
        assertTrue(d.sections.all { it.end - it.start <= Doc.DEFAULT_SECTION_CHARS })
    }

    private fun simulate(seed: Long, from: Int, count: Int, noise: Noise): Pair<TextTracker, List<com.alicegpt.textfollower.testutil.Step>> {
        val d = book()
        val t = TextTracker(d)
        t.seek(from)
        val steps = Runner.trace(t, SpeechSim(d, Random(seed), noise).read(from, from + count))
        return t to steps
    }

    @Test
    fun noisyReadingOfRealTextHasNoFalseShifts() {
        val noise = Noise(drop = 0.10, ending = 0.20, garbage = 0.08, stutter = 0.03)
        // старты в разных песнях (в том числе рядом со стыками песен)
        for ((k, from) in listOf(0, 3500, 9000, 21000, 40000, 63000, 88000).withIndex()) {
            val (t, steps) = simulate(100L + k, from, 3000, noise)
            assertTrue("старт $from: опережение ${Runner.maxAhead(steps)}", Runner.maxAhead(steps) <= 1)
            assertTrue("старт $from: итог ${t.position}", t.position >= from + 3000 - 15)
        }
    }

    @Test
    fun foreignSpeechAndGarbageDoNotMoveHighlightInRealText() {
        val d = book()
        val vocabulary = d.norm.toList()
        for (seed in 1L..4L) {
            val t = TextTracker(d)
            t.seek(50_000)
            val steps = Runner.trace(t, SpeechSim(d, Random(seed)).garbage(3000, truthEnd = 49_999, vocabulary = vocabulary))
            assertEquals("seed $seed", 50_000, t.position)
            assertTrue(steps.none { it.move != null })
        }
    }

    @Test
    fun jumpsAcrossSongsAreFollowedOnlyToTheRightPlace() {
        val d = book()
        val noise = Noise(drop = 0.05, ending = 0.15, garbage = 0.04)
        for (seed in 1L..4L) {
            val t = TextTracker(d)
            val sim = SpeechSim(d, Random(seed), noise)
            val a = sim.read(1000, 1200)
            val b = sim.read(70_000, 70_300)
            val steps = Runner.trace(t, a + b)
            for (s in steps) {
                val m = s.move ?: continue
                assertTrue("шаг ${s.index}: позиция ${m.to}", m.to <= s.furthest + 2)
                assertTrue("шаг ${s.index}: чужое место ${m.to}", Math.abs(m.to - s.truth) <= 15 || m.to in 1180..1201)
            }
            assertTrue("seed $seed: итог ${t.position}", t.position >= 70_300 - 15)
        }
    }
}
