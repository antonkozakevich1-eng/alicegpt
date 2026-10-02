package com.alicegpt.textfollower.text

import java.text.Normalizer
import java.util.Locale

/** Общие правила нормализации слов: и для текста книги, и для распознанной речи. */
object Words {

    private const val UNKNOWN = "[unk]"

    /** Нижний регистр, ё→е, без знаков ударения. */
    fun normalize(word: String): String {
        val composed = Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val sb = StringBuilder(composed.length)
        for (c in composed) {
            when {
                c == 'ё' -> sb.append('е')
                isMark(c) -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Слова распознанной речи; служебные «[unk]» от Vosk отбрасываются. */
    fun recognized(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val clean = if (text.contains(UNKNOWN)) text.replace(UNKNOWN, " ") else text
        val out = ArrayList<String>()
        var i = 0
        val n = clean.length
        while (i < n) {
            if (isWordStart(clean[i])) {
                val s = i
                i++
                while (i < n && isWordPart(clean[i])) i++
                val w = normalize(clean.substring(s, i))
                if (w.isNotEmpty()) out.add(w)
            } else {
                i++
            }
        }
        return out
    }

    fun isWordStart(c: Char) = Character.isLetterOrDigit(c)

    fun isWordPart(c: Char) = Character.isLetterOrDigit(c) || isMark(c)

    private fun isMark(c: Char) = Character.getType(c) == Character.NON_SPACING_MARK.toInt()
}
