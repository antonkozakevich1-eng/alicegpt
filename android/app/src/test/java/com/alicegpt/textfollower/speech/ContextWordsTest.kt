package com.alicegpt.textfollower.speech

import com.alicegpt.textfollower.text.Doc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWordsTest {

    private val doc = Doc.parse((0 until 300).joinToString(" ") { "слово${it / 10}x${it % 10}" })

    @Test
    fun windowCoversWordsAroundTheCenter() {
        val w = ContextWords.window(doc, center = 100, back = 10, ahead = 20)
        assertEquals(30, w.size)
        assertEquals(doc.norm[90], w.first())
        assertEquals(doc.norm[119], w.last())
    }

    @Test
    fun windowIsClampedToTheDocument() {
        assertEquals(15, ContextWords.window(doc, 5, 10, 10).size)
        val end = ContextWords.window(doc, 295, 10, 100)
        assertEquals(doc.norm[285], end.first())
        assertEquals(doc.norm[299], end.last())
        assertTrue(ContextWords.window(Doc.parse(""), 0, 10, 10).isEmpty())
    }

    @Test
    fun wordsWrittenWithYoAreGivenBothWays() {
        val d = Doc.parse("Всё идёт к концу, а лодка плывёт, и ВСЁ тихо; еще раз ёлка")
        val w = ContextWords.window(d, 0, 0, d.size)
        // нормализованные слова (по ним трекер сравнивает речь с текстом) и слова, как они написаны (их слышит распознаватель)
        for (x in listOf("все", "всё", "идет", "идёт", "плывет", "плывёт", "елка", "ёлка")) assertTrue("нет «$x» в $w", x in w)
        // слово без «ё» в тексте остаётся одним
        assertEquals(1, w.count { it == "еще" })
        assertTrue("ещё" !in w)
        assertEquals(w.size, w.toSet().size)
    }

    @Test
    fun wordsAreUniqueAndKeepTheirOrder() {
        val d = Doc.parse("лодка река лодка мост река лодка")
        assertEquals(listOf("лодка", "река", "мост"), ContextWords.window(d, 3, 10, 10))
    }
}
