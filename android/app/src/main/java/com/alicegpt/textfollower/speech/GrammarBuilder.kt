package com.alicegpt.textfollower.speech

import com.alicegpt.textfollower.text.Doc
import org.json.JSONArray

/**
 * Ограничение словаря распознавателя словами из окна текста вокруг позиции.
 *
 * В словаре модели слова пишутся с «ё» («её», «войдёт»), а в книгах чаще «е», поэтому для каждого
 * слова добавляются и варианты с «ё»: те, которых в модели нет, Vosk молча пропускает.
 */
object GrammarBuilder {
    const val DEFAULT_WINDOW = 300
    private const val UNKNOWN = "[unk]"

    /** Слов в окне и доля окна позади позиции. */
    private const val BACK_SHARE = 6

    /** JSON-массив для Vosk: слова окна [window] слов вокруг [center] и «[unk]». */
    fun forWindow(doc: Doc, center: Int, window: Int = DEFAULT_WINDOW): String {
        val back = window / BACK_SHARE
        return fromWords(ContextWords.window(doc, center, back, window - back))
    }

    /** JSON-массив для Vosk из готового списка слов (добавляются варианты через «ё» и «[unk]»). */
    fun fromWords(words: List<String>): String {
        val all = LinkedHashSet<String>()
        for (w in words) for (v in yoVariants(w)) all.add(v)
        val arr = JSONArray()
        all.forEach { arr.put(it) }
        arr.put(UNKNOWN)
        return arr.toString()
    }

    /** Центр, при отклонении от которого больше чем на [REBUILD_DISTANCE] слов окно пересобирается. */
    const val REBUILD_DISTANCE = 100

    internal fun yoVariants(word: String): List<String> {
        val idx = word.indices.filter { word[it] == 'е' }
        if (idx.isEmpty()) return listOf(word)
        val out = LinkedHashSet<String>()
        out.add(word)
        if (idx.size <= 3) {
            for (mask in 1 until (1 shl idx.size)) {
                val sb = StringBuilder(word)
                for (b in idx.indices) if (mask shr b and 1 == 1) sb.setCharAt(idx[b], 'ё')
                out.add(sb.toString())
            }
        } else {
            for (i in idx) out.add(StringBuilder(word).also { it.setCharAt(i, 'ё') }.toString())
        }
        return out.toList()
    }
}
