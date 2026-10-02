package com.alicegpt.textfollower.testutil

import com.alicegpt.textfollower.text.Doc
import java.util.Random
import kotlin.math.pow

/**
 * Придуманный «русскоподобный» текст для тестов: слова собираются из слогов, поэтому
 * совпадения с настоящими книгами исключены, а длины и повторы слов похожи на живые.
 */
object PseudoText {
    private const val CONSONANTS = "бвгдзклмнпрстхчш"
    private const val VOWELS = "аеиоуыя"
    private val FUNCTION_WORDS = listOf("и", "в", "на", "не", "он", "но", "как", "что", "она", "то", "там", "так")

    fun vocabulary(rnd: Random, size: Int): List<String> {
        val set = LinkedHashSet<String>()
        while (set.size < size) {
            val sb = StringBuilder()
            repeat(2 + rnd.nextInt(3)) {
                sb.append(CONSONANTS[rnd.nextInt(CONSONANTS.length)]).append(VOWELS[rnd.nextInt(VOWELS.length)])
            }
            if (rnd.nextInt(3) == 0) sb.append(CONSONANTS[rnd.nextInt(CONSONANTS.length)])
            set.add(sb.toString())
        }
        return set.toList()
    }

    /** Текст из строк по 5–8 слов, номер «[5]» у каждой пятой строки, заголовок каждые [linesPerChapter] строк. */
    fun raw(rnd: Random, vocabulary: List<String>, lines: Int, linesPerChapter: Int = 120): String {
        val sb = StringBuilder()
        var chapter = 0
        for (line in 1..lines) {
            if ((line - 1) % linesPerChapter == 0) {
                chapter++
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append("ПЕСНЬ №$chapter".replace("№", ROMAN[(chapter - 1) % ROMAN.size])).append("\n\n")
            }
            if (line % 5 == 0) sb.append('[').append(line).append("] ")
            val count = 5 + rnd.nextInt(4)
            val words = (0 until count).map {
                if (rnd.nextInt(4) == 0) FUNCTION_WORDS[rnd.nextInt(FUNCTION_WORDS.size)]
                else vocabulary[(vocabulary.size * rnd.nextDouble().pow(1.6)).toInt().coerceAtMost(vocabulary.size - 1)]
            }
            val text = words.joinToString(" ")
            sb.append(if (line % 7 == 0) text.replaceFirstChar { it.uppercase() } + "." else "$text,").append('\n')
        }
        return sb.toString()
    }

    fun doc(seed: Long = 1, lines: Int = 600): Doc {
        val rnd = Random(seed)
        return Doc.parse(raw(rnd, vocabulary(rnd, 1500), lines))
    }

    private val ROMAN = listOf("ПЕРВАЯ", "ВТОРАЯ", "ТРЕТЬЯ", "ЧЕТВЁРТАЯ", "ПЯТАЯ", "ШЕСТАЯ", "СЕДЬМАЯ", "ВОСЬМАЯ")
}
