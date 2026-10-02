package com.alicegpt.textfollower.text

import org.junit.Assert.assertEquals
import org.junit.Test

class WordsTest {

    @Test
    fun normalizeLowersCaseReplacesYoAndDropsStressMarks() {
        assertEquals("все", Words.normalize("ВСЁ"))
        assertEquals("ее", Words.normalize("Её"))
        assertEquals("замок", Words.normalize("за́мок")) // знак ударения (комбинируемый акут)
    }

    @Test
    fun spelledKeepsYo() {
        assertEquals("всё", Words.spelled("ВСЁ"))
        assertEquals("замок", Words.spelled("За́мок"))
    }

    @Test
    fun recognizedTextIsSplitIntoNormalizedWordsWithoutUnknownMarks() {
        assertEquals(listOf("синяя", "лодка", "плывет"), Words.recognized("Синяя  лодка, плывёт!"))
        assertEquals(listOf("и", "лодка"), Words.recognized("и [unk] лодка [unk]"))
        assertEquals(emptyList<String>(), Words.recognized("   "))
        assertEquals(emptyList<String>(), Words.recognized("[unk] [unk]"))
    }

    @Test
    fun hyphenatedWordsFallApartAtTheHyphen() {
        // дефис — не часть слова: «из-за» это два слова «из» и «за»
        assertEquals(listOf("из", "за", "леса"), Words.recognized("из-за леса"))
    }
}
