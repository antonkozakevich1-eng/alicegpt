package com.alicegpt.textfollower.speech

import com.alicegpt.textfollower.text.Doc
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarBuilderTest {

    private fun words(json: String): List<String> {
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getString(it) }
    }

    @Test
    fun yoVariantsCoverEveryWayToPutYo() {
        assertEquals(listOf("слово"), GrammarBuilder.yoVariants("слово"))
        assertEquals(setOf("все", "всё"), GrammarBuilder.yoVariants("все").toSet())
        // два «е» — четыре варианта
        assertEquals(setOf("ее", "ёе", "её", "ёё"), GrammarBuilder.yoVariants("ее").toSet())
    }

    @Test
    fun manyLettersEDoNotExplode() {
        assertTrue(GrammarBuilder.yoVariants("перевезенье").size <= 8)
    }

    @Test
    fun windowCoversWordsAroundThePositionAndUnknownMarker() {
        val d = Doc.parse((0 until 1000).joinToString(" ") { "слово${it}а" })
        val w = words(GrammarBuilder.forWindow(d, 500, window = 300))
        assertTrue(w.contains("[unk]"))
        assertTrue(w.contains("слово500а"))
        assertTrue(w.contains("слово700а"))     // вперёд
        assertTrue(w.contains("слово460а"))     // немного назад
        assertFalse(w.contains("слово300а"))
        assertFalse(w.contains("слово900а"))
        assertEquals(301, w.size)               // 300 слов и «[unk]»
    }

    @Test
    fun windowAtTheEdgesIsClamped() {
        val d = Doc.parse("раз два три четыре пять")
        for (center in listOf(0, 5)) {
            val w = words(GrammarBuilder.forWindow(d, center))
            assertTrue(w.containsAll(listOf("раз", "два", "три", "четыре", "пять", "[unk]")))
        }
        assertTrue(words(GrammarBuilder.forWindow(Doc.parse(""), 0)).contains("[unk]"))
    }

    @Test
    fun wordsAreNormalizedAndUnique() {
        val d = Doc.parse("Всё, все; ВСЕ! все")
        val w = words(GrammarBuilder.forWindow(d, 0))
        assertEquals(1, w.count { it == "все" })
        assertTrue(w.contains("всё"))
    }
}
