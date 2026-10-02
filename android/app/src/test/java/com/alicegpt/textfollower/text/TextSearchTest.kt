package com.alicegpt.textfollower.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {

    private val doc = Doc.parse(
        """
        ПЕСНЬ ПЕРВАЯ

        Синяя лодка плывёт по тихой реке,
        [5] старый мастер чинит деревянный мост.

        ПЕСНЬ ВТОРАЯ

        Холодный ветер приносит запах мокрой травы,
        маленькая птица прячется под широким листом,
        синяя лодка стоит у моста.
        """.trimIndent(),
    )

    @Test
    fun findsAPhraseAndReportsTheFirstWord() {
        val hits = TextSearch.find(doc, "старый мастер чинит")
        assertEquals(1, hits.size)
        assertEquals(6, hits[0].word)
        assertEquals("Песнь первая", hits[0].chapter)
    }

    @Test
    fun ignoresCaseYoAndPunctuation() {
        assertEquals(1, TextSearch.find(doc, "СИНЯЯ, лодка стоит").size)
        assertEquals(1, TextSearch.find(doc, "плывёт по тихой").size)
        assertEquals(1, TextSearch.find(doc, "плывет по тихой").size)
    }

    @Test
    fun forgivesEndingsAndAnUnfinishedLastWord() {
        assertEquals(2, TextSearch.find(doc, "синяя лодки").size)
        assertEquals(2, TextSearch.find(doc, "синяя лод").size)
        assertEquals(1, TextSearch.find(doc, "широким лист").size)
    }

    @Test
    fun findsEveryOccurrenceInOrderWithTheirChapters() {
        val hits = TextSearch.find(doc, "синяя лодка")
        assertEquals(listOf(0, 23), hits.map { it.word })
        assertEquals(listOf("Песнь первая", "Песнь вторая"), hits.map { it.chapter })
    }

    @Test
    fun snippetIsReadableWithoutLineNumbersAndLineBreaks() {
        val hit = TextSearch.find(doc, "старый мастер").single()
        assertTrue(hit.snippet, !hit.snippet.contains("[5]"))
        assertTrue(hit.snippet, !hit.snippet.contains('\n'))
        assertTrue(hit.snippet, hit.snippet.contains("старый мастер чинит"))
        assertTrue(hit.snippet, hit.snippet.contains("/"))
    }

    @Test
    fun noMatchEmptyQueryAndEmptyDocument() {
        assertTrue(TextSearch.find(doc, "ъъъъ").isEmpty())
        assertTrue(TextSearch.find(doc, "   ").isEmpty())
        assertTrue(TextSearch.find(doc, "[5]").isEmpty())
        assertTrue(TextSearch.find(Doc.parse(""), "лодка").isEmpty())
    }

    @Test
    fun phraseLongerThanTheTextFindsNothing() {
        assertTrue(TextSearch.find(doc, "синяя лодка плывёт по тихой реке старый мастер чинит деревянный мост и ещё много слов подряд " + "слово ".repeat(60)).isEmpty())
    }

    @Test
    fun resultsAreLimited() {
        val many = Doc.parse("лодка ".repeat(500))
        assertEquals(60, TextSearch.find(many, "лодка").size)
        assertEquals(5, TextSearch.find(many, "лодка", limit = 5).size)
    }

    @Test
    fun shortWordsMatchOnlyExactly() {
        val d = Doc.parse("он идёт по дороге и поёт")
        assertEquals(1, TextSearch.find(d, "он идет").size)
        assertTrue(TextSearch.find(d, "она идет").isEmpty())
    }
}
