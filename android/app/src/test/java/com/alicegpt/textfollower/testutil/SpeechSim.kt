package com.alicegpt.textfollower.testutil

import com.alicegpt.textfollower.text.Doc
import com.alicegpt.textfollower.tracking.TextTracker
import java.util.Random

/** Шумы распознавания: доли от 0 до 1. */
class Noise(
    val drop: Double = 0.0,      // слово не распознано
    val ending: Double = 0.0,    // распознано с неверным окончанием
    val garbage: Double = 0.0,   // перед словом вставлено лишнее слово
    val stutter: Double = 0.0,   // слово повторено
)

/** Событие распознавателя: [truthEnd] — индекс последнего реально прочитанного слова к этому моменту. */
class Utt(val text: String, val isFinal: Boolean, val truthEnd: Int)

/** Имитирует поток partial/final результатов Vosk для читателя с шумом. */
class SpeechSim(private val doc: Doc, private val rnd: Random, private val noise: Noise = Noise()) {

    private val junk = listOf("а", "э", "ну", "да", "и", "вот", "так", "ага", "хм", "ой", "кхм", "ох")

    /** Читаем слова [from, to) фразами по 4–14 слов; ideal = true — без шума. */
    fun read(from: Int, to: Int, minUtterance: Int = 4, maxUtterance: Int = 14): List<Utt> {
        val events = ArrayList<Utt>()
        var i = from
        while (i < to) {
            val len = minOf(minUtterance + rnd.nextInt(maxUtterance - minUtterance + 1), to - i)
            val words = ArrayList<String>()
            for (k in 0 until len) {
                val idx = i + k
                if (rnd.nextDouble() < noise.garbage) words += junk(idx)
                if (rnd.nextDouble() >= noise.drop) words += withEnding(doc.norm[idx])
                if (words.isNotEmpty() && rnd.nextDouble() < noise.stutter) words += words.last()
                events += Utt(words.joinToString(" "), false, idx)
            }
            events += Utt(words.joinToString(" "), true, i + len - 1)
            i += len
        }
        return events
    }

    /** Паузы, кашель, чужая речь: слова не из текста либо случайные слова из словаря. */
    fun garbage(count: Int, truthEnd: Int, vocabulary: List<String>? = null): List<Utt> {
        val events = ArrayList<Utt>()
        repeat(count) {
            val n = 1 + rnd.nextInt(6)
            val words = (0 until n).map {
                if (vocabulary != null && rnd.nextBoolean()) vocabulary[rnd.nextInt(vocabulary.size)] else junk[rnd.nextInt(junk.size)]
            }
            val text = words.joinToString(" ")
            events += Utt(text, false, truthEnd)
            events += Utt(text, true, truthEnd)
        }
        return events
    }

    private fun junk(near: Int): String =
        if (rnd.nextInt(3) == 0) PseudoText.vocabulary(Random(near * 31L + rnd.nextInt(7)), 1)[0] else junk[rnd.nextInt(junk.size)]

    private fun withEnding(w: String): String {
        if (w.length < 4 || rnd.nextDouble() >= noise.ending) return w
        val cut = if (w.length <= 5) 1 else 1 + rnd.nextInt(2)
        val endings = "аеиуыояй"
        val sb = StringBuilder(w.substring(0, w.length - cut))
        repeat(cut) { sb.append(endings[rnd.nextInt(endings.length)]) }
        return sb.toString()
    }
}

/** Состояние трекера после очередного события распознавателя. */
class Step(val index: Int, val event: Utt, val position: Int, val move: TextTracker.Move?, val furthest: Int) {
    /** Идеальная позиция: следующее после последнего реально прочитанного слова. */
    val truth: Int get() = event.truthEnd + 1
    val ahead: Int get() = position - truth

    /** Насколько позиция ушла дальше самого дальнего из реально прочитанных слов (>0 — точно ложный сдвиг). */
    val beyondFurthest: Int get() = position - (furthest + 1)
}

object Runner {
    fun trace(tracker: TextTracker, events: List<Utt>): List<Step> {
        var furthest = -1
        return events.mapIndexed { i, e ->
            furthest = maxOf(furthest, e.truthEnd)
            val move = if (e.isFinal) tracker.onFinal(e.text) else tracker.onPartial(e.text)
            Step(i, e, tracker.position, move, furthest)
        }
    }

    /** Максимальное опережение позиции над истиной: положительное значение — ложный сдвиг вперёд. */
    fun maxAhead(steps: List<Step>): Int = steps.maxOfOrNull { it.ahead } ?: 0

    /** Максимальный выход позиции за самое дальнее прочитанное слово (при перечитывании «опережение» — это просто ожидание). */
    fun maxBeyondFurthest(steps: List<Step>): Int = steps.maxOfOrNull { it.beyondFurthest } ?: 0
}
