package com.alicegpt.textfollower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatsTest {
    private var now = 1_000_000L
    private val stats = SessionStats { now }

    @Test
    fun timeCountsOnlyWhileListening() {
        stats.start()
        now += 10_000
        stats.pause()
        now += 50_000 // пауза
        assertEquals(10_000, stats.activeMillis)
        stats.start()
        now += 5_000
        assertEquals(15_000, stats.activeMillis)
    }

    @Test
    fun wordsPerMinuteOverTheWholeSessionAtFirst() {
        stats.start()
        repeat(10) {
            now += 3_000
            stats.onForwardMove(5)
        }
        // 50 слов за 30 секунд
        assertEquals(50, stats.wordsRead)
        assertEquals(100, stats.wordsPerMinute())
    }

    @Test
    fun wordsPerMinuteUsesTheLastMinuteLater() {
        stats.start()
        repeat(60) { now += 1_000; stats.onForwardMove(1) }   // первая минута: 60 слов/мин
        repeat(60) { now += 1_000; if (it % 2 == 0) stats.onForwardMove(1) } // вторая: 30 слов/мин
        assertTrue("слов/мин ${stats.wordsPerMinute()}", stats.wordsPerMinute() in 29..32)
    }

    @Test
    fun jumpsAndBackwardMovesAreNotReading() {
        stats.start()
        now += 10_000
        stats.onForwardMove(500) // перескок
        stats.onForwardMove(-20) // перечитывание
        stats.onForwardMove(0)
        assertEquals(0, stats.wordsRead)
        assertEquals(0, stats.wordsPerMinute())
    }

    @Test
    fun resetClearsEverything() {
        stats.start()
        now += 10_000
        stats.onForwardMove(10)
        stats.reset()
        assertEquals(0, stats.wordsRead)
        assertEquals(0, stats.activeMillis)
    }
}
