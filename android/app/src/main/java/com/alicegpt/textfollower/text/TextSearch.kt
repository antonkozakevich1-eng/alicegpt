package com.alicegpt.textfollower.text

import com.alicegpt.textfollower.tracking.WordMatcher

/** Поиск фразы в тексте: слова ищутся нечётко (прощаются окончания), последнее слово можно набрать не до конца. */
object TextSearch {

    class Hit(
        /** Индекс первого найденного слова. */
        val word: Int,
        /** Заголовок главы, в которой найдено. */
        val chapter: String,
        /** Фрагмент текста вокруг находки. */
        val snippet: String,
    )

    fun find(doc: Doc, query: String, limit: Int = 60): List<Hit> {
        val q = Words.recognized(query)
        if (q.isEmpty() || doc.size == 0) return emptyList()
        val matcher = WordMatcher()
        val hits = ArrayList<Hit>()
        val last = q.size - 1
        var i = 0
        while (i + q.size <= doc.size && hits.size < limit) {
            if (matches(matcher, q[0], doc.norm[i], isLast = last == 0)) {
                var ok = true
                for (k in 1..last) {
                    if (!matches(matcher, q[k], doc.norm[i + k], isLast = k == last)) {
                        ok = false
                        break
                    }
                }
                if (ok) hits.add(hit(doc, i, i + last))
            }
            i++
        }
        return hits
    }

    private fun matches(m: WordMatcher, q: String, w: String, isLast: Boolean): Boolean {
        if (q == w) return true
        if (isLast && q.length >= 2 && w.startsWith(q)) return true
        if (q.length < 4 || w.length < 4 || q[0] != w[0]) return false
        return m.similarity(q, w) >= 0.8
    }

    private fun hit(doc: Doc, firstWord: Int, lastWord: Int): Hit {
        val text = doc.text
        val from = (doc.wordStart[firstWord] - SNIPPET_BEFORE).coerceAtLeast(0)
        val to = (doc.wordEnd[lastWord] + SNIPPET_AFTER).coerceAtMost(text.length)
        val raw = text.substring(from, to)
        val clean = raw.replace(Regex("\\[\\d+]"), " ").replace(Regex("\\s*\\n\\s*"), " / ").replace(Regex("\\s{2,}"), " ").trim()
        val section = doc.sections[doc.sectionOfWord(firstWord)]
        return Hit(firstWord, section.title, (if (from > 0) "…" else "") + clean + (if (to < text.length) "…" else ""))
    }

    private const val SNIPPET_BEFORE = 40
    private const val SNIPPET_AFTER = 70
}
