package com.alicegpt.textfollower.tracking

import kotlin.math.max
import kotlin.math.min

/**
 * Нечёткое сравнение слов. Распознавание речи часто путает окончания и отдельные буквы,
 * поэтому «красивая» и «красивый», «читал» и «читала» считаются одним словом.
 *
 * Не потокобезопасен: держит рабочие буферы.
 */
class WordMatcher {

    private var rowA = IntArray(32)
    private var rowB = IntArray(32)

    /** 1.0 — слова совпали, 0.0 — не похожи (всё ниже [MATCH_MIN] сразу сводится к нулю). */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val minLen = min(a.length, b.length)
        val maxLen = max(a.length, b.length)
        // Короткие слова («и», «в», «он») при распознавании слишком часто ошибочны — только точное совпадение.
        if (minLen < 3) return 0.0
        if (maxLen - minLen > max(2, maxLen * 2 / 5)) return 0.0

        val prefix = commonPrefix(a, b)
        var sim = 1.0 - levenshtein(a, b).toDouble() / maxLen
        // Общий корень + разные окончания («тихая»/«тихой», «красивыми»/«красивый»).
        if (prefix >= 4 && prefix * 5 >= maxLen * 3) sim = max(sim, 0.8)
        else if (prefix >= 3 && minLen >= 4 && prefix * 5 >= maxLen * 3) sim = max(sim, 0.75)
        return if (sim >= MATCH_MIN) sim else 0.0
    }

    private fun commonPrefix(a: String, b: String): Int {
        val n = min(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = b.length
        if (rowA.size <= m) {
            rowA = IntArray(m + 1)
            rowB = IntArray(m + 1)
        }
        var prev = rowA
        var cur = rowB
        for (j in 0..m) prev[j] = j
        for (i in 1..a.length) {
            cur[0] = i
            val ca = a[i - 1]
            for (j in 1..m) {
                val cost = if (ca == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[m]
    }

    companion object {
        const val MATCH_MIN = 0.7
    }
}
