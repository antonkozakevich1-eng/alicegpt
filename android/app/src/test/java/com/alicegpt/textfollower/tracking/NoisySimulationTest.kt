package com.alicegpt.textfollower.tracking

import com.alicegpt.textfollower.testutil.Noise
import com.alicegpt.textfollower.testutil.PseudoText
import com.alicegpt.textfollower.testutil.Runner
import com.alicegpt.textfollower.testutil.SpeechSim
import com.alicegpt.textfollower.testutil.Step
import com.alicegpt.textfollower.testutil.Utt
import com.alicegpt.textfollower.text.Doc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Имитация распознавания с шумом. Главное требование — ни одного ложного сдвига:
 * позиция не должна обгонять реально прочитанное и не должна прыгать в чужие места.
 */
class NoisySimulationTest {

    private val moderate = Noise(drop = 0.10, ending = 0.20, garbage = 0.08, stutter = 0.03)
    private val heavy = Noise(drop = 0.25, ending = 0.35, garbage = 0.20, stutter = 0.05)

    private fun doc(seed: Long): Doc = PseudoText.doc(seed, lines = 750)

    private fun lagShare(steps: List<Step>, warmup: Int, maxLag: Int): Double {
        val body = steps.drop(warmup)
        return body.count { it.truth - it.position > maxLag }.toDouble() / body.size
    }

    /**
     * Все сдвиги должны попадать рядом с истиной: не дальше самого дальнего прочитанного слова и не в чужое место.
     * [zones] — допустимые места посадки помимо текущей истины (на стыке фрагментов позиция ещё может
     * доехать до конца прежнего фрагмента по хвосту старых слов).
     */
    private fun assertMovesAreRight(steps: List<Step>, zones: List<IntRange> = emptyList(), tolerance: Int = 15) {
        for (s in steps) {
            val m = s.move ?: continue
            assertTrue("сдвиг за пределы прочитанного: шаг ${s.index}, позиция ${m.to}, дальше всех ${s.furthest + 1}", m.to <= s.furthest + 2)
            val ok = abs(m.to - s.truth) <= tolerance || zones.any { m.to in it }
            assertTrue("сдвиг в чужое место: шаг ${s.index}, позиция ${m.to}, истина ${s.truth}", ok)
        }
    }

    @Test
    fun cleanReadingIsFollowedExactly() {
        for (seed in 1L..3L) {
            val d = doc(seed)
            val t = TextTracker(d)
            val steps = Runner.trace(t, SpeechSim(d, Random(seed)).read(0, 2000))
            assertTrue("опережение ${Runner.maxAhead(steps)}", Runner.maxAhead(steps) <= 0)
            // позиция не ставится на короткое слово в хвосте, поэтому в конце возможен запас в пару слов
            assertTrue("итог ${t.position}", t.position in 1997..2000)
            assertTrue("отставание ${lagShare(steps, 10, 3)}", lagShare(steps, warmup = 10, maxLag = 3) < 0.02)
        }
    }

    @Test
    fun moderateNoiseNoFalseShifts() {
        for (seed in 1L..8L) {
            val d = doc(seed)
            val t = TextTracker(d)
            val steps = Runner.trace(t, SpeechSim(d, Random(seed), moderate).read(0, 2500))
            assertTrue("seed $seed: опережение ${Runner.maxAhead(steps)}", Runner.maxAhead(steps) <= 1)
            assertMovesAreRight(steps)
            assertTrue("seed $seed: итог ${t.position}", t.position >= 2500 - 12)
            assertTrue("seed $seed: отставание ${lagShare(steps, 20, 20)}", lagShare(steps, 20, 20) < 0.05)
        }
    }

    @Test
    fun heavyNoiseStillNoFalseShifts() {
        for (seed in 1L..8L) {
            val d = doc(seed)
            val t = TextTracker(d)
            val steps = Runner.trace(t, SpeechSim(d, Random(seed), heavy).read(0, 2500))
            // при 20% мусорных слов «лишнее» слово иногда совпадает со следующим словом текста — это ±2 слова, не прыжок
            assertTrue("seed $seed: опережение ${Runner.maxAhead(steps)}", Runner.maxAhead(steps) <= 3)
            assertMovesAreRight(steps)
            assertTrue("seed $seed: итог ${t.position}", t.position >= 2500 * 0.8)
        }
    }

    @Test
    fun garbageCoughAndPausesNeverMoveHighlight() {
        for (seed in 1L..5L) {
            val d = doc(seed)
            val t = TextTracker(d)
            t.seek(700)
            val steps = Runner.trace(t, SpeechSim(d, Random(seed)).garbage(2000, truthEnd = 699))
            assertEquals("seed $seed", 700, t.position)
            assertTrue(steps.none { it.move != null })
        }
    }

    @Test
    fun foreignSpeechWithTheSameWordsNeverMovesHighlight() {
        for (seed in 1L..5L) {
            val d = doc(seed)
            val t = TextTracker(d)
            t.seek(700)
            // чужая речь собрана из слов этой же книги, но в случайном порядке
            val vocabulary = d.norm.toList()
            val steps = Runner.trace(t, SpeechSim(d, Random(seed)).garbage(3000, truthEnd = 699, vocabulary = vocabulary))
            assertTrue("seed $seed: сдвигов ${steps.count { it.move != null }}", steps.none { it.move != null })
            assertEquals(700, t.position)
        }
    }

    @Test
    fun skipAheadWithinTheNearWindowIsFollowedOnlyToTheRightPlace() {
        for (seed in 1L..6L) {
            val d = doc(seed)
            val t = TextTracker(d)
            val sim = SpeechSim(d, Random(seed), moderate)
            val steps = Runner.trace(t, sim.read(0, 300) + sim.read(420, 800))
            assertMovesAreRight(steps, zones = listOf(280..301))
            assertTrue("seed $seed: итог ${t.position}", t.position >= 800 - 12)
        }
    }

    @Test
    fun farJumpIsFollowedAfterLongExactMatch() {
        for (seed in 1L..6L) {
            val d = doc(seed)
            val t = TextTracker(d)
            val sim = SpeechSim(d, Random(seed), Noise(ending = 0.1))
            val before = sim.read(0, 300)
            val after = sim.read(3500, 3800)
            val steps = Runner.trace(t, before + after)
            assertMovesAreRight(steps, zones = listOf(280..301))
            assertTrue("seed $seed: итог ${t.position}", t.position in 3797..3800)
            // за первые ~10 событий после прыжка позиция уже на новом месте
            val firstFar = steps.drop(before.size).first { it.position > 3000 }
            assertTrue("seed $seed: нашли только на шаге ${firstFar.index - before.size}", firstFar.index - before.size <= 25)
        }
    }

    @Test
    fun reReadingIsFollowed() {
        for (seed in 1L..6L) {
            val d = doc(seed)
            val t = TextTracker(d)
            val sim = SpeechSim(d, Random(seed), moderate)
            // прочитали 0..400, вернулись на 20 слов назад, потом на 200 слов назад, дальше вперёд
            val events = sim.read(0, 400) + sim.read(380, 450) + sim.read(250, 300) + sim.read(300, 700)
            val steps = Runner.trace(t, events)
            assertMovesAreRight(steps, zones = listOf(380..401, 430..451, 280..301))
            assertTrue("seed $seed: итог ${t.position}", t.position >= 700 - 12)
        }
    }

    @Test
    fun repeatedLineAndStutterDoNotRunAhead() {
        for (seed in 1L..6L) {
            val d = doc(seed)
            val t = TextTracker(d)
            val sim = SpeechSim(d, Random(seed), Noise(ending = 0.1, stutter = 0.15))
            // каждую строку читаем дважды (повтор последних слов)
            val events = ArrayList<Utt>()
            var at = 0
            while (at < 1200) {
                events += sim.read(at, at + 14)
                events += sim.read(at + 6, at + 14)
                at += 14
            }
            val steps = Runner.trace(t, events)
            assertTrue("seed $seed: за пределами прочитанного ${Runner.maxBeyondFurthest(steps)}", Runner.maxBeyondFurthest(steps) <= 1)
            assertTrue("seed $seed: итог ${t.position}", t.position >= 1200 - 14 - 12)
        }
    }

    @Test
    fun junkBetweenPhrasesDoesNotBreakFollowing() {
        for (seed in 1L..6L) {
            val d = doc(seed)
            val t = TextTracker(d)
            val sim = SpeechSim(d, Random(seed), moderate)
            val events = ArrayList<Utt>()
            var at = 0
            while (at < 1500) {
                events += sim.read(at, at + 12)
                events += sim.garbage(1, truthEnd = at + 11)
                at += 12
            }
            val steps = Runner.trace(t, events)
            assertTrue("seed $seed: опережение ${Runner.maxAhead(steps)}", Runner.maxAhead(steps) <= 1)
            assertMovesAreRight(steps)
            assertTrue("seed $seed: итог ${t.position}", t.position >= 1500 - 24)
        }
    }

    @Test
    fun lostReaderIsRecoveredAfterGarbageThenText() {
        val d = doc(3)
        val t = TextTracker(d)
        val sim = SpeechSim(d, Random(3), moderate)
        val events = sim.read(0, 200) + sim.garbage(300, truthEnd = 199, vocabulary = d.norm.toList()) + sim.read(200, 500)
        val steps = Runner.trace(t, events)
        assertTrue(Runner.maxAhead(steps) <= 1)
        assertTrue("итог ${t.position}", t.position >= 500 - 12)
    }
}
