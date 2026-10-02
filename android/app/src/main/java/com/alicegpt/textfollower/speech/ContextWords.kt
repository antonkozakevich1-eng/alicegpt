package com.alicegpt.textfollower.speech

import com.alicegpt.textfollower.text.Doc
import com.alicegpt.textfollower.text.Words

/** Слова текста вокруг позиции — подсказка для распознавателя. */
object ContextWords {

    /**
     * Уникальные слова окна: [back] слов назад и [ahead] вперёд от [center]. Слова с «ё» в тексте даются и в нормализованном
     * виде («все»), и как написаны («всё»): распознаватель произносит «ё», а в книгах его часто нет.
     */
    fun window(doc: Doc, center: Int, back: Int, ahead: Int): List<String> {
        val from = (center - back).coerceAtLeast(0)
        val to = (center + ahead).coerceAtMost(doc.size)
        val words = LinkedHashSet<String>()
        for (i in from until to) {
            words.add(doc.norm[i])
            val raw = doc.text.substring(doc.wordStart[i], doc.wordEnd[i])
            if (raw.indexOf('ё') >= 0 || raw.indexOf('Ё') >= 0) words.add(Words.spelled(raw))
        }
        return words.toList()
    }
}
