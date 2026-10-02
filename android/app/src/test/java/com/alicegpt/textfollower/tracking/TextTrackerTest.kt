package com.alicegpt.textfollower.tracking

import com.alicegpt.textfollower.testutil.PseudoText
import com.alicegpt.textfollower.text.Doc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Правила подтверждения позиции на маленьком придуманном тексте и на длинном псевдотексте. */
class TextTrackerTest {

    private val doc = Doc.parse(
        """
        Синяя лодка плывёт по тихой реке,
        старый мастер чинит деревянный мост.
        Холодный ветер приносит запах мокрой травы,
        маленькая птица прячется под широким листом.
        """.trimIndent(),
    )

    // Индексы слов: синяя0 лодка1 плывёт2 по3 тихой4 реке5 | старый6 мастер7 чинит8 деревянный9 мост10 |
    // холодный11 ветер12 приносит13 запах14 мокрой15 травы16 | маленькая17 птица18 прячется19 под20 широким21 листом22

    private fun tracker() = TextTracker(doc)

    // ---------- подсветка идёт за словами, но одно слово ничего не значит ----------

    @Test
    fun oneWordIsNotEnough() {
        val t = tracker()
        assertNull(t.onPartial("синяя"))
        assertNull(t.onPartial("лодка"))
        assertEquals(0, t.position)
    }

    @Test
    fun twoConfirmingWordsMoveTheHighlightAtOnce() {
        val t = tracker()
        val move = t.onPartial("синяя лодка")
        assertNotNull(move)
        assertEquals(2, t.position)
        assertEquals(0, move!!.from)
        assertEquals(2, move.delta)
        assertFalse(move.far)
    }

    @Test
    fun highlightFollowsEveryNewWordOfAGrowingPhrase() {
        val t = tracker()
        val spoken = "синяя лодка плывет по тихой реке старый мастер".split(' ')
        for (k in 2..spoken.size) {
            t.onPartial(spoken.take(k).joinToString(" "))
            assertEquals("после $k слов", k, t.position)
        }
    }

    @Test
    fun finalResultOfTheSamePhraseDoesNotMoveItAgain() {
        val t = tracker()
        t.onPartial("синяя лодка плывет по тихой реке")
        assertNull(t.onFinal("синяя лодка плывет по тихой реке"))
        assertEquals(6, t.position)
    }

    @Test
    fun skipOfSeveralWordsNeedsMoreConfirmation() {
        val t = tracker()
        // «старый» — седьмое слово: перескок на шесть слов вперёд двух слов недостаточно
        assertNull(t.onPartial("старый мастер"))
        assertEquals(0, t.position)
        assertNull(t.onPartial("старый мастер чинит"))
        assertEquals(0, t.position)
        assertNotNull(t.onPartial("старый мастер чинит деревянный"))
        assertEquals(10, t.position) // следующее слово — «мост»
    }

    @Test
    fun shortWordInTheTailIsFine() {
        val t = tracker()
        t.onPartial("синяя лодка плывет по")
        assertEquals(4, t.position)
        t.onPartial("синяя лодка плывет по тихой")
        assertEquals(5, t.position)
    }

    @Test
    fun lastWordOfAnUnfinishedPhraseMatchesByPrefix() {
        val t = tracker()
        t.onPartial("синяя лодка плывет по тих") // «тихой» ещё не дослушано
        assertEquals(5, t.position)
    }

    // ---------- ошибки распознавателя прощаются ----------

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
    fun junkAtTheEndKeepsThePosition() {
        val t = tracker()
        t.onPartial("синяя лодка плывет по тихой")
        assertEquals(5, t.position)
        assertNull(t.onPartial("синяя лодка плывет по тихой хм"))
        assertEquals(5, t.position)
    }

    @Test
    fun junkInTheMiddleCostsAtMostOneWord() {
        val t = tracker()
        // распознаватель присылает фразу по слову
        val spoken = "синяя лодка плывет по тихой хм реке старый мастер".split(' ')
        val seen = ArrayList<Int>()
        for (k in 2..spoken.size) {
            t.onPartial(spoken.take(k).joinToString(" "))
            seen += t.position
        }
        // после «хм» подсветка стоит на «реке», после «реке» — не дальше чем на слово позади
        assertEquals("позиции $seen", 5, seen[spoken.indexOf("хм") - 2])
        assertTrue("позиции $seen", seen[spoken.indexOf("реке") - 2] in 5..6)
        assertEquals("позиции $seen", 8, seen.last())
    }

    @Test
    fun repeatedLongWordDoesNotRunAhead() {
        val t = tracker()
        t.onPartial("синяя лодка плывет по тихой реке")
        assertEquals(6, t.position)
        t.onPartial("синяя лодка плывет по тихой реке реке") // заикание
        assertEquals(6, t.position)
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
    fun foreignSpeechWithSimilarLengthDoesNotMove() {
        val t = tracker()
        t.onPartial("синяя лодка плывет по тихой реке")
        val pos = t.position
        assertNull(t.onPartial("холодный день завтра обещают дождь без ветра"))
        assertEquals(pos, t.position)
    }

    // ---------- паузы и фразы ----------

    @Test
    fun continuesAfterPauseWithoutWaitingForNewWords() {
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
        t.onPartial("синяя лодка плывет по тихой реке") // распознаватель «передумал» насчёт последнего слова
        assertEquals(7, t.position)
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

    @Test
    fun seekIsClampedToTheText() {
        val t = tracker()
        t.seek(-5)
        assertEquals(0, t.position)
        t.seek(10_000)
        assertEquals(doc.size, t.position)
    }

    @Test
    fun emptyTextNeverMoves() {
        val t = TextTracker(Doc.parse(""))
        assertNull(t.onPartial("синяя лодка"))
        assertNull(t.onFinal("синяя лодка"))
        assertEquals(0, t.position)
    }

    // ---------- уверенность ----------

    @Test
    fun confidenceFallsOnGarbageAndComesBackWithText() {
        val t = tracker()
        t.onPartial("синяя лодка плывет по тихой реке")
        t.onFinal("синяя лодка плывет по тихой реке")
        assertTrue(t.locked)
        assertTrue(t.confidence > 0.9)

        t.onFinal("кхм кхм кхм")
        t.onFinal("один два три четыре пять")
        assertFalse("уверенность ${t.confidence}", t.locked)
        assertEquals(6, t.position)

        t.onPartial("старый мастер чинит")
        assertTrue("уверенность ${t.confidence}", t.locked)
        assertEquals(9, t.position)
    }

    // ---------- перескоки на длинном тексте ----------

    private val big: Doc = PseudoText.doc(seed = 11, lines = 700)

    /** Первое место не раньше [from], где [len] слов подряд достаточно длинные и разные, чтобы быть уверенным совпадением. */
    private fun pick(from: Int, len: Int = 8): Int {
        var i = from
        while (true) {
            val w = (i until i + len).map { big.norm[it] }
            if (w.all { it.length >= 4 } && w.toSet().size == len && w.all { x -> big.norm.count { it == x } <= 8 }) return i
            i++
        }
    }

    private fun spoken(from: Int, count: Int) = (from until from + count).joinToString(" ") { big.norm[it] }

    /** Читатель замолчал по делу: много слов мимо текста подряд — трекер сам переходит в режим поиска места. */
    private fun lose(t: TextTracker) {
        t.onFinal("кхм ну это самое да кхм ага да так")
        assertTrue("режим поиска должен включиться", t.searchMode)
    }

    /** Сколько слов с места [from] надо произнести, чтобы подсветка перешла туда с позиции [start]; null — не дошла. */
    private fun wordsNeeded(start: Int, from: Int, searching: Boolean, max: Int = 12): Int? {
        val t = TextTracker(big)
        t.seek(start)
        if (searching) lose(t)
        for (k in 1..max) {
            t.onPartial(spoken(from, k))
            if (t.position == from + k) return k
            check(t.position == start) { "позиция ушла не туда: ${t.position}, слов: $k" }
        }
        return null
    }

    @Test
    fun nearSkipNeedsFewWordsFarJumpNeedsMore() {
        val near = pick(250)
        val far = pick(3000)
        val nearWords = wordsNeeded(start = 100, from = near, searching = false)
        val farWords = wordsNeeded(start = 100, from = far, searching = false)
        assertNotNull("до ближнего места не дошли", nearWords)
        assertNotNull("до дальнего места не дошли", farWords)
        assertTrue("ближний: $nearWords", nearWords!! in 2..4)
        assertTrue("дальний: $farWords", farWords!! in 4..8)
        assertTrue("дальнее место должно требовать больше слов", farWords > nearWords)
    }

    @Test
    fun farJumpBackwardAlsoNeedsSeveralWords() {
        val start = pick(500)
        val words = wordsNeeded(start = 3000, from = start, searching = false)
        assertNotNull(words)
        assertTrue("слов: $words", words!! in 4..8)
    }

    @Test
    fun searchModeFindsTheNewPlaceFaster() {
        val far = pick(3000)
        val normal = wordsNeeded(start = 100, from = far, searching = false)!!
        val searching = wordsNeeded(start = 100, from = far, searching = true)!!
        assertTrue("обычный: $normal, поиск: $searching", searching < normal)
        assertTrue("в режиме поиска слов: $searching", searching <= 4)
    }

    @Test
    fun searchModeDoesNotTakeGarbageForAPlace() {
        val t = TextTracker(big)
        t.seek(1000)
        lose(t)
        for (junk in listOf("а", "ну да", "кхм кхм кхм", "и в на не", "один два три четыре пять")) {
            assertNull(junk, t.onPartial(junk))
            assertNull(junk, t.onFinal(junk))
        }
        assertEquals(1000, t.position)
    }

    @Test
    fun searchStartsOnlyAfterSeveralUnconfirmedWordsAndEndsWithAMove() {
        val t = TextTracker(big)
        val at = pick(300, len = 6)
        t.seek(at)
        t.onPartial(spoken(at, 6))
        t.onFinal(spoken(at, 6))
        assertFalse(t.searchMode)
        // пара лишних слов — ещё не «ушёл»
        t.onFinal("кхм ну")
        assertFalse(t.searchMode)
        // много слов мимо текста — ушёл
        t.onFinal("это самое да так вот ага")
        assertTrue(t.searchMode)
        assertFalse(t.locked)
        // стоило прочитать пару слов оттуда, где остановились, — поиск закончился
        t.onPartial(spoken(at + 6, 3))
        assertFalse(t.searchMode)
        assertEquals(at + 9, t.position)
    }

    @Test
    fun seekAndStopSearchingLeaveSearchMode() {
        val t = TextTracker(big)
        t.seek(500)
        lose(t)
        t.seek(700)
        assertFalse(t.searchMode)
        lose(t)
        t.stopSearching()
        assertFalse(t.searchMode)
        // и счёт слов начался заново: пары слов мимо текста для поиска мало
        t.onFinal("кхм ну")
        assertFalse(t.searchMode)
    }

    @Test
    fun commonWordsAloneDoNotMakeALongJump() {
        val t = TextTracker(big)
        t.seek(1000)
        t.onPartial("и в на не")
        assertTrue("позиция ${t.position}", kotlin.math.abs(t.position - 1000) <= 8)
        t.onFinal("и в на не как что она то там так")
        assertTrue("позиция ${t.position}", kotlin.math.abs(t.position - 1000) <= 40)
    }

    @Test
    fun reReadingFewWordsBackIsFollowedAfterConfirmation() {
        val t = TextTracker(big)
        t.seek(400)
        val start = pick(340, len = 5)
        assertTrue("слишком далеко назад", start + 5 < 398 && 400 - start < 60)
        t.onPartial(spoken(start, 2))
        assertEquals(400, t.position)
        t.onPartial(spoken(start, 5))
        assertEquals(start + 5, t.position)
    }

    @Test
    fun smallStepBackIsIgnoredAsRecognizerNoise() {
        val t = TextTracker(big)
        val at = pick(200, len = 6)
        t.seek(at)
        t.onPartial(spoken(at, 6))
        assertEquals(at + 6, t.position)
        // распознаватель переставил слова: позиция на 1–2 слова назад — это не перечитывание
        t.onPartial(spoken(at, 5))
        assertEquals(at + 6, t.position)
    }
}
