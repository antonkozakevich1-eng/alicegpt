package com.alicegpt.textfollower.tracking

import com.alicegpt.textfollower.text.Doc
import com.alicegpt.textfollower.text.Words
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ПРЕЖНИЙ трекер (версия 1: подсветка двигается, только когда подряд совпали 4–6 слов). Оставлен в тестах лишь для
 * сравнения со старым алгоритмом в `VoskExperimentTest` (`EXP_TRACKER=legacy`); приложение его не использует.
 *
 * Определяет по потоку распознанной речи, где в тексте читают, и двигает позицию
 * **только при уверенности**.
 *
 * Правила:
 *  - одно слово ничего не значит: позиция подтверждается, когда подряд совпали не меньше
 *    [MIN_MATCHED] последних распознанных слов с текстом (нечётко: прощаем неверные окончания),
 *    средняя похожесть совпавших слов не ниже [MIN_AVG_SIM], допускается один пропуск или одно лишнее слово;
 *  - сначала ищем рядом с текущей позицией ([AHEAD] слов вперёд и [BACK] назад);
 *  - далёкий прыжок (в другое место текста) — только при совпадении не менее [FAR_MIN_MATCHED] слов подряд;
 *  - мусор, кашель, паузы и чужая речь не дают подряд совпавших слов, и подсветка стоит на месте.
 *
 * Распознанные слова текущей фразы дописываются к хвосту предыдущих (после паузы позиция
 * подтверждается сразу, не дожидаясь четырёх новых слов).
 *
 * Не потокобезопасен.
 */
class LegacyTextTracker(val doc: Doc) {

    /** Результат: позиция сдвинулась с [from] на [to] ([far] — далёкий прыжок). */
    class Move(val from: Int, val to: Int, val far: Boolean, val matched: Int) {
        val delta: Int get() = to - from
    }

    private val norm: Array<String> = doc.norm
    private val n = norm.size
    private val matcher = WordMatcher()

    /** Индекс слова, которое читатель должен произнести следующим (0..doc.size). */
    var position: Int = 0
        private set

    /** Слова законченных фраз; нужны, чтобы после паузы не ждать четыре новых слова. */
    private val committed = ArrayList<String>()

    private val stemIndex: HashMap<String, IntArray> by lazy { buildStemIndex() }

    /** Поставить позицию вручную (долгое нажатие, оглавление, восстановление). */
    fun seek(index: Int) {
        position = index.coerceIn(0, n)
        committed.clear()
    }

    /** Подготовить индекс для далёких прыжков заранее (дорогая операция на длинных текстах). */
    fun warmUp() {
        stemIndex.size
    }

    fun onPartial(text: String): Move? = process(text, isFinal = false)

    fun onFinal(text: String): Move? = process(text, isFinal = true)

    private fun process(text: String, isFinal: Boolean): Move? {
        if (n == 0) return null
        val words = Words.recognized(text)
        val buffer: List<String> = if (committed.isEmpty()) words else committed + words
        val move = evaluate(buffer)
        if (isFinal && words.isNotEmpty()) {
            committed.addAll(words)
            while (committed.size > MAX_COMMITTED) committed.removeAt(0)
        }
        return move
    }

    private fun evaluate(buffer: List<String>): Move? {
        if (buffer.size < MIN_MATCHED) return null

        val near = searchNear(buffer)
        var far: Candidate? = null
        if (buffer.size >= FAR_MIN_MATCHED && (near == null || near.matched < NEAR_STRONG)) {
            far = searchFar(buffer)
        }
        val chosen = when {
            far != null && (near == null || far.matched >= near.matched + 3) -> far
            else -> near
        } ?: return null

        val newPos = chosen.end + 1
        if (newPos == position) return null
        val move = Move(position, newPos, chosen.far, chosen.matched)
        position = newPos
        return move
    }

    // ---------- поиск рядом ----------

    private class Candidate(val end: Int, val matched: Int, val avgSim: Double, val far: Boolean)

    private fun searchNear(buffer: List<String>): Candidate? {
        val lo = max(0, position - 1 - BACK)
        val hi = min(n - 1, position - 1 + AHEAD)
        val aligner = Aligner(buffer, MIN_MATCHED, MIN_AVG_SIM, lo = 0)
        var best: Candidate? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (e in lo..hi) {
            if (!aligner.run(e)) continue
            val trimmed = trimShortTail(e, aligner.matched, MIN_MATCHED) ?: continue
            val end = trimmed.first
            val matched = trimmed.second
            val newPos = end + 1
            if (newPos < position) {
                // Откат: либо пересмотр гипотезы распознавателем (игнорируем), либо перечитывание (нужно больше слов).
                if (position - newPos <= JITTER) continue
                if (matched < BACKWARD_MIN_MATCHED) continue
            }
            val distance = if (newPos >= position) newPos - position else 2 * (position - newPos)
            val score = min(matched, MAX_DEPTH) * 10.0 + aligner.avgSim * 5.0 - 3.0 * min(distance, AHEAD) / AHEAD
            if (score > bestScore) {
                bestScore = score
                best = Candidate(end, matched, aligner.avgSim, far = false)
            }
        }
        return best
    }

    // ---------- далёкий прыжок ----------

    private fun searchFar(buffer: List<String>): Candidate? {
        val m = buffer.size
        val first = max(0, m - FAR_WINDOW)
        // Голосуем за «диагонали»: слово i распознанного хвоста, найденное в тексте на позиции p, голосует за конец p + (m-1-i).
        val votes = HashMap<Int, Int>()
        for (i in first until m) {
            val w = buffer[i]
            if (w.length < 4) continue
            val positions = stemIndex[stem(w)] ?: continue
            if (positions.size > MAX_STEM_POSITIONS) continue
            val shift = m - 1 - i
            for (p in positions) {
                val end = p + shift
                if (end in 0 until n) votes[end] = (votes[end] ?: 0) + 1
            }
        }
        if (votes.isEmpty()) return null

        val ends = votes.entries
            .map { it.key to (it.value + (votes[it.key - 1] ?: 0) + (votes[it.key + 1] ?: 0)) }
            .filter { it.second >= FAR_MIN_VOTES }
            .sortedByDescending { it.second }
            .take(MAX_FAR_CANDIDATES)
        if (ends.isEmpty()) return null

        val aligner = Aligner(buffer, FAR_MIN_MATCHED, FAR_MIN_AVG_SIM, lo = 0)
        var best: Candidate? = null
        var bestScore = Double.NEGATIVE_INFINITY
        val tried = HashSet<Int>()
        for ((center, _) in ends) {
            for (e in center - 1..center + 1) {
                if (e !in 0 until n || !tried.add(e)) continue
                if (!aligner.run(e)) continue
                val trimmed = trimShortTail(e, aligner.matched, FAR_MIN_MATCHED) ?: continue
                val score = trimmed.second * 10.0 + aligner.avgSim * 5.0 - min(abs(trimmed.first + 1 - position), n) * 1e-6
                if (score > bestScore) {
                    bestScore = score
                    best = Candidate(trimmed.first, trimmed.second, aligner.avgSim, far = true)
                }
            }
        }
        return best
    }

    /**
     * Короткое слово в хвосте («и», «так», «вот») — слабое доказательство: его легко принять за мусорное слово
     * или заикание, совпавшее с текстом. Позицию на нём не ставим и в счёт совпавших слов его не берём:
     * кандидат должен набрать [min] слов и без хвостовых коротких. Возвращает (конец, число слов) или null.
     */
    private fun trimShortTail(end: Int, matched: Int, min: Int): Pair<Int, Int>? {
        var e = end
        var m = matched
        var trimmed = 0
        while (e > 0 && norm[e].length <= SHORT_WORD) {
            if (trimmed >= MAX_TRIM || m - 1 < min) return null
            e--
            m--
            trimmed++
        }
        return e to m
    }

    private fun buildStemIndex(): HashMap<String, IntArray> {
        val lists = HashMap<String, ArrayList<Int>>()
        for (i in 0 until n) {
            val w = norm[i]
            if (w.length < 4) continue
            lists.getOrPut(stem(w)) { ArrayList() }.add(i)
        }
        val index = HashMap<String, IntArray>(lists.size * 2)
        for ((k, v) in lists) index[k] = v.toIntArray()
        return index
    }

    private fun stem(w: String) = if (w.length <= STEM_LEN) w else w.substring(0, STEM_LEN)

    // ---------- выравнивание хвоста речи с текстом ----------

    /**
     * Ищет, насколько хвост распознанных слов (с конца) совпадает с текстом, заканчиваясь на слове [run].end.
     * Допускается один «пропуск»: лишнее распознанное слово или пропущенное слово текста.
     */
    private inner class Aligner(
        private val buffer: List<String>,
        private val minMatched: Int,
        private val minAvg: Double,
        private val lo: Int,
    ) {
        var matched = 0
            private set
        var avgSim = 0.0
            private set
        private var bestSim = 0.0
        private var iMin = 0

        fun run(end: Int): Boolean {
            matched = 0
            bestSim = 0.0
            iMin = max(0, buffer.size - MAX_DEPTH)
            dfs(buffer.size - 1, end, 0, 0, 0.0, 0)
            if (matched == 0) return false
            avgSim = bestSim / matched
            return true
        }

        private fun dfs(i: Int, j: Int, gaps: Int, count: Int, sim: Double, letters: Int) {
            if (count >= minMatched && sim / count >= minAvg && (letters >= MIN_LETTERS || count >= 6)) {
                if (count > matched || (count == matched && sim > bestSim)) {
                    matched = count
                    bestSim = sim
                }
            }
            if (i < iMin || j < lo) return

            val s = matcher.similarity(buffer[i], norm[j])
            if (s > 0.0) {
                dfs(i - 1, j - 1, gaps, count + 1, sim + s, letters + norm[j].length)
            }
            if (gaps < MAX_GAPS) {
                dfs(i - 1, j, gaps + 1, count, sim, letters) // лишнее распознанное слово
                if (count > 0) dfs(i, j - 1, gaps + 1, count, sim, letters) // пропущенное слово текста
            }
        }
    }

    companion object {
        /** Сколько подряд совпавших слов подтверждают позицию рядом. */
        const val MIN_MATCHED = 4
        const val MIN_AVG_SIM = 0.90
        /** Хватает ли букв в совпавших словах (чтобы «и в на не» ничего не подтверждали). */
        const val MIN_LETTERS = 14
        const val MAX_GAPS = 1

        /** Окно локального поиска, в словах: вперёд и назад от текущей позиции. */
        const val AHEAD = 200
        const val BACK = 50
        const val BACKWARD_MIN_MATCHED = 5
        const val JITTER = 2

        /** Далёкий прыжок: длинное и точное совпадение. */
        const val FAR_MIN_MATCHED = 8
        const val FAR_MIN_AVG_SIM = 0.92
        private const val NEAR_STRONG = 6
        private const val FAR_WINDOW = 12
        private const val FAR_MIN_VOTES = 5
        private const val MAX_FAR_CANDIDATES = 40
        private const val MAX_STEM_POSITIONS = 150
        private const val STEM_LEN = 5

        /** Слова не длиннее этого не служат опорой для позиции в хвосте. */
        private const val SHORT_WORD = 3
        private const val MAX_TRIM = 2

        private const val MAX_COMMITTED = 12
        private const val MAX_DEPTH = 14
    }
}
