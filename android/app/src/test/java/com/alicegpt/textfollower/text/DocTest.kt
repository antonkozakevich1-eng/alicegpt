package com.alicegpt.textfollower.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocTest {

    private val sample = """
        ПЕСНЬ ПЕРВАЯ

        Синяя лодка плывёт по реке,
        Ёжик собирает яблоки в мешок
        [5] Тихий ветер гонит облака.
        Ёлка́ стоит у окна.

        ПЕСНЬ ВТОРАЯ

        Новый день встречает город.
        [10]Совсем другой берег виден.
    """.trimIndent()

    @Test
    fun wordsAreNormalizedAndMarkersIgnored() {
        val doc = Doc.parse(sample)
        val words = doc.norm.toList()
        assertEquals(listOf("синяя", "лодка", "плывет", "по", "реке"), words.take(5))
        assertFalse("номер строки не должен быть словом", words.any { it == "5" || it == "10" })
        assertTrue(words.contains("елка")) // ё→е и ударение убраны
        assertTrue(words.contains("совсем")) // «[10]Совсем» без пробела
    }

    @Test
    fun markersAreRecordedForGrayDisplay() {
        val doc = Doc.parse(sample)
        val ranges = doc.markerRanges.toList().chunked(2).map { doc.text.substring(it[0], it[1]) }
        assertEquals(listOf("[5]", "[10]"), ranges)
    }

    @Test
    fun headingsAreNotWords() {
        val doc = Doc.parse(sample)
        assertFalse(doc.norm.contains("песнь"))
        assertFalse(doc.norm.contains("первая"))
        val heads = doc.headingRanges.toList().chunked(2).map { doc.text.substring(it[0], it[1]) }
        assertEquals(listOf("ПЕСНЬ ПЕРВАЯ", "ПЕСНЬ ВТОРАЯ"), heads)
    }

    @Test
    fun tableOfContentsFollowsHeadings() {
        val doc = Doc.parse(sample)
        val toc = doc.sections.filter { it.inToc }
        assertEquals(listOf("Песнь первая", "Песнь вторая"), toc.map { it.title })
        assertEquals(0, toc[0].firstWord)
        assertEquals("новый", doc.norm[toc[1].firstWord])
        assertEquals(1, doc.sectionOfWord(toc[1].firstWord))
        assertEquals(0, doc.sectionOfWord(3))
    }

    @Test
    fun textWithoutHeadingsIsOneSection() {
        val doc = Doc.parse("Один два три\nчетыре пять")
        assertEquals(1, doc.sections.size)
        assertEquals("Весь текст", doc.sections[0].title)
        assertEquals(5, doc.size)
    }

    @Test
    fun longChaptersAreSplitButStayInOneTocEntry() {
        val line = "слово слово слово слово слово слово слово слово\n"
        val doc = Doc.parse("ГЛАВА 1\n" + line.repeat(100), maxSectionChars = 1000)
        assertTrue(doc.sections.size > 3)
        assertEquals(1, doc.sections.count { it.inToc })
        // секции покрывают весь текст без дыр
        assertEquals(0, doc.sections.first().start)
        assertEquals(doc.text.length, doc.sections.last().end)
        for (i in 1 until doc.sections.size) assertEquals(doc.sections[i - 1].end, doc.sections[i].start)
        assertEquals(doc.size, doc.sections.last().endWord)
    }

    @Test
    fun ordinaryLinesAreNotHeadings() {
        assertFalse(Doc.isHeading("Часть дороги он шёл молча и думал"))
        assertFalse(Doc.isHeading("Глава была длинной, и все устали."))
        assertFalse(Doc.isHeading("Песнь XX."))
        assertFalse(Doc.isHeading("Песнь I. Начало пути"))
        assertTrue(Doc.isHeading("Глава 12"))
        assertTrue(Doc.isHeading("Глава 12."))
        assertTrue(Doc.isHeading("Глава первая"))
        assertTrue(Doc.isHeading("   КНИГА ВТОРАЯ  "))
        assertTrue(Doc.isHeading("ПРИМЕЧАНИЯ"))
    }

    @Test
    fun wordAtOffsetFindsTappedWord() {
        val doc = Doc.parse("раз два три")
        assertEquals(1, doc.wordAtOffset(5))
        assertEquals(2, doc.wordAtOffset(10))
        assertEquals(0, doc.wordAtOffset(0))
    }
}
