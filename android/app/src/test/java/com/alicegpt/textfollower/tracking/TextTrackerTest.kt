package com.alicegpt.textfollower.tracking

import com.alicegpt.textfollower.testutil.PseudoText
import com.alicegpt.textfollower.text.Doc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Правила подтверждения позиции на маленьком придуманном тексте. */
class TextTrackerTest {

    private val doc = Doc.parse(
        """
        Синяя лодка плывёт по тихой реке,
        старый мастер чинит деревянный мост.
        Холодный ветер приносит запах мокрой травы,
        маленькая птица прячется под широким листом.
        """.trimIndent(),
    )

    private fun tracker() = TextTracker(doc)

    @Test
    fun oneWordIsNotEnough() {
        val t = tracker()
        assertNull(t.onPartial("синяя"))
        assertNull(t.onPartial("лодка"))
        assertEquals(0, t.position)
    }

    @Test
    fun threeWordsAreNotEnoughButFourConfirm() {
        val t = tracker()
        assertNull(t.onPartial("старый мастер чинит"))
        assertEquals(0, t.position)
        val move = t.onPartial("старый мастер чинит деревянный")
        assertNotNull(move)
        assertEquals(10, t.position) // следующее слово — «мост»
    }

    @Test
    fun shortWordInTheTailIsNotEvidence() {
        val t = tracker()
        // четыре слова, но последнее — «по»: короткое слово позицию не подтверждает, ждём содержательное
        assertNull(t.onPartial("синяя лодка плывет по"))
        assertEquals(0, t.position)
        t.onPartial("синяя лодка плывет по тихой")
        assertEquals(5, t.position)
    }

    @Test
    fun stutterOnAShortWordDoesNotSkipAhead() {
        val d = Doc.parse("рыба плавает там и сям но то но потом уходит домой")
        val t = TextTracker(d)
        t.onPartial("рыба плавает там и сям но")
        val pos = t.position
        t.onPartial("рыба плавает там и сям но но") // заикание: «но то но» совпало бы с пропуском слова «то»
        assertEquals(pos, t.position)
    }

    @Test
    fun wrongEndingsAreForgiven() {
        val t = tracker()
        t.onPartial("синяя лодки плывет по тихая")
        assertEquals(5, t.position)
    }

    @Test
    fun oneJunkWordInsideIsForgiven() {
        val t = tracker()
        t.onPartial("синяя лодка ну плывет по тихой реке")
        assertEquals(6, t.position)
    }

    @Test
    fun oneSkippedWordIsForgiven() {
        val t = tracker()
        t.onPartial("синяя лодка по тихой реке") // «плывёт» не распознано
        assertEquals(6, t.position)
    }

    @Test
    fun junkAtTheEndIsForgiven() {
        val t = tracker()
        t.onPartial("синяя лодка плывет по тихой хм")
        assertEquals(5, t.position)
    }

    @Test
    fun garbageDoesNotMoveHighlight() {
        val t = tracker()
        t.onPartial("синяя лодка плывет по тихой реке")
        val pos = t.position
        for (junk in listOf("а", "ну да", "кхм кхм кхм", "и в на не", "один два три четыре пять", "[unk] [unk] [unk]")) {
            assertNull(junk, t.onPartial(junk))
            assertNull(junk, t.onFinal(junk))
        }
        assertEquals(pos, t.position)
    }

    @Test
    fun commonShortWordsDoNotConfirmAnything() {
        val d = Doc.parse("и в на не и в на не и в на не")
        val t = TextTracker(d)
        assertNull(t.onPartial("и в на не"))
        assertEquals(0, t.position)
    }

    @Test
    fun foreignSpeechWithSimilarLengthDoesNotMove() {
        val t = tracker()
        t.onPartial("синяя лодка плывет по тихой реке")
        val pos = t.position
        assertNull(t.onPartial("холодный день завтра обещают дождь без ветра"))
        assertEquals(pos, t.position)
    }

    @Test
    fun continuesAfterPauseWithoutWaitingForFourNewWords() {
        val t = tracker()
        t.onPartial("синяя лодка плывет по тихой реке")
        t.onFinal("синяя лодка плывет по тихой реке")
        assertEquals(6, t.position)
        // пауза; новая фраза с одного слова — хвост предыдущей фразы подтверждает позицию
        t.onPartial("старый")
        assertEquals(7, t.position)
        t.onPartial("старый мастер")
        assertEquals(8, t.position)
    }

    @Test
    fun partialRevisionsDoNotJitterBackwards() {
        val t = tracker()
        t.onPartial("синяя лодка плывет по тихой реке старый")
        assertEquals(7, t.position)
        val pos = t.position
        t.onPartial("синяя лодка плывет по тихой реке") // распознаватель «передумал» насчёт последнего слова
        assertEquals(pos, t.position)
    }

    @Test
    fun seekResetsContext() {
        val t = tracker()
        t.onFinal("синяя лодка плывет по тихой реке")
        t.seek(15)
        assertEquals(15, t.position)
        assertNull(t.onPartial("старый"))
        assertEquals(15, t.position)
    }

    // ---------- перескоки на длинном тексте ----------

    private val big: Doc = PseudoText.doc(seed = 11, lines = 700)

    /** Первое место не раньше [from], где [len] слов подряд достаточно длинные, чтобы быть уверенным совпадением. */
    private fun pick(from: Int, len: Int = 8): Int {
        var i = from
        while (true) {
            val w = (i until i + len).map { big.norm[it] }
            if (w.all { it.length >= 4 } && w.toSet().size == len) return i
            i++
        }
    }

    private fun spoken(from: Int, count: Int) = (from until from + count).joinToString(" ") { big.norm[it] }

    @Test
    fun nearSkipForwardNeedsFourWordsFarJumpNeedsEight() {
        val t = TextTracker(big)
        t.seek(100)
        // +150 слов вперёд: ещё «рядом» — хватает четырёх подряд совпавших слов
        val near = pick(250)
        assertNull(t.onPartial(spoken(near, 3)))
        assertNotNull(t.onPartial(spoken(near, 4)))
        assertEquals(near + 4, t.position)

        // далёкий прыжок (в другую главу): пять-семь слов ничего не двигают, восемь двигают
        val far = pick(3000)
        for (k in 4..7) {
            t.onPartial(spoken(far, k))
            assertEquals("слов: $k", near + 4, t.position)
        }
        val move = t.onPartial(spoken(far, 8))
        assertNotNull(move)
        assertTrue(move!!.far)
        assertEquals(far + 8, t.position)
    }

    @Test
    fun farJumpBackwardAlsoNeedsEight() {
        val t = TextTracker(big)
        t.seek(3000)
        val start = pick(500)
        for (k in 4..7) {
            t.onPartial(spoken(start, k))
            assertEquals(3000, t.position)
        }
        t.onPartial(spoken(start, 8))
        assertEquals(start + 8, t.position)
    }

    @Test
    fun reReadingNeedsFiveWordsAndGoesBack() {
        val t = TextTracker(big)
        t.seek(400)
        val start = pick(352, len = 5)
        assertTrue("слишком далеко назад", start + 5 < 398 && 400 - start < 50)
        t.onPartial(spoken(start, 4))
        assertEquals(400, t.position)
        t.onPartial(spoken(start, 5))
        assertEquals(start + 5, t.position)
    }
}
