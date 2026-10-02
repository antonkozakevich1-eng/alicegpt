package com.alicegpt.textfollower.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordMatcherTest {
    private val m = WordMatcher()

    @Test fun equalWords() = assertEquals(1.0, m.similarity("лодка", "лодка"), 0.0)

    @Test fun wrongEndingsAreForgiven() {
        assertTrue(m.similarity("красивая", "красивый") >= 0.7)
        assertTrue(m.similarity("плыл", "плыла") >= 0.7)
        assertTrue(m.similarity("богиня", "богини") >= 0.8)
        assertTrue(m.similarity("прекрасных", "прекрасный") >= 0.7)
    }

    @Test fun differentWordsDoNotMatch() {
        assertEquals(0.0, m.similarity("лодка", "ветер"), 0.0)
        assertEquals(0.0, m.similarity("кот", "кто"), 0.0)
        assertEquals(0.0, m.similarity("три", "тридцать"), 0.0)
    }

    @Test fun shortWordsNeedExactMatch() {
        assertEquals(0.0, m.similarity("он", "она"), 0.0)
        assertEquals(0.0, m.similarity("на", "но"), 0.0)
        assertEquals(1.0, m.similarity("на", "на"), 0.0)
    }
}
