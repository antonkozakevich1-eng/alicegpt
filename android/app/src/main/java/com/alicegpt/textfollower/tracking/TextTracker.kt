package com.alicegpt.textfollower.tracking

import com.alicegpt.textfollower.text.Doc
import com.alicegpt.textfollower.text.Words
import kotlin.math.abs
import kotlin.math.max

/** Параметры трекера. Подобраны по записям распознавания (см. `tools/vosk-experiment`). */
@Suppress("ArrayInDataObject")
data class TrackerConfig(
    /** Вероятность, что распознанное слово — это (почти) то, что читают: средняя точность распознавателя. */
    val rho: Double = 0.6,
    /** Читатель за одно распознанное слово сдвигается на 0 (повтор), 1, 2, 3 слова. */
    val advance: DoubleArray = doubleArrayOf(0.0, 0.80, 0.10, 0.03),
    /** Читает → пауза/мусор/чужая речь. */
    val pStop: Double = 0.04,
    /** Пауза → снова читает. */
    val pStart: Double = 0.05,
    /** Откат на 1..8 слов назад (повтор слов). */
    val pBack: Double = 0.01,
    /** Пропуск вперёд на 4..40 слов. */
    val pSkip: Double = 0.01,
    /** Доля возвращения из паузы, уходящая на перечитывание назад (до 40 слов). */
    val pReread: Double = 0.05,
    /** Прыжок в любое место текста. */
    val pJump: Double = 3e-4,
    /** Нижняя граница «фоновой» вероятности слова: ограничивает силу очень редких слов. */
    val backgroundFloor: Double = 0.0,
    /** Состояния с вероятностью ниже порога отбрасываются; в пучке не больше [beam] состояний. */
    val prune: Double = 1e-7,
    val beam: Int = 150,
    /** Слова, у которых больше столько вхождений в текст, не порождают пропуски/перескоки. */
    val wideCap: Int = 600,
    /** Вероятность «читает» в точке, куда читателя поставили вручную. */
    val seekOn: Double = 0.5,

    // Шлюз перед сдвигом подсветки: сколько из последних 8 распознанных слов подтвердили новое место
    // и какая доля вероятности (вокруг лучшей позиции) должна быть на нём.
    val needNear: Int = 2,
    val needMid: Int = 2,
    val needFar: Int = 3,
    val needJump: Int = 4,
    val needHuge: Int = 6,
    val tauMove: Double = 0.5,
    val tauMid: Double = 0.8,
    val tauFar: Double = 0.97,
    /** Откаты не дальше стольких слов считаются «передумал распознаватель» и игнорируются. */
    val jitter: Int = 2,
    /** Границы дальности сдвига, слов. */
    val farAfter: Int = 60,
    val hugeAfter: Int = 200,

    // Режим поиска места: далёкий перескок принимается по меньшему числу совпавших слов и при меньшей уверенности.
    /** Подсветка «потеряна», когда вероятность чтения рядом с ней ниже [lockThreshold]… */
    val lockThreshold: Double = 0.25,
    /** …и накопилось столько распознанных слов без подтверждения места: тогда включается поиск. */
    val lostWords: Int = 4,
    val searchNeedFar: Int = 3,
    val searchNeedJump: Int = 3,
    val searchNeedHuge: Int = 4,
    val searchTauFar: Double = 0.97,
    val searchJump: Double = 3e-3,
) {
    /**
     * Режим поиска места: читатель ушёл (перескочил или потерялся), нужно быстро найти, где он. Далёкий перескок
     * принимается по меньшему числу совпавших слов; на записях это находит место за ≈2 с без ложных срабатываний
     * на чужой речи и шуме.
     */
    fun searching() = copy(
        needFar = minOf(needFar, searchNeedFar), needJump = minOf(needJump, searchNeedJump),
        needHuge = minOf(needHuge, searchNeedHuge), tauFar = minOf(tauFar, searchTauFar), pJump = maxOf(pJump, searchJump),
    )

    companion object {
        /** Для нейросетевого движка. */
        val NEURAL = TrackerConfig()

        /**
         * Для Vosk с ограничением словаря: распознаватель вынужден подбирать слова из окна, поэтому чужая речь
         * и чтение другого места чаще случайно «складываются» в слова текста — шлюз строже.
         */
        val VOSK = TrackerConfig(needNear = 3, pJump = 1e-5, backgroundFloor = 0.003, needHuge = 8)
    }
}

/**
 * Определяет по потоку распознанной речи, где в тексте читают, и двигает подсветку **только при уверенности**.
 *
 * Это байесовский фильтр по позиции (скрытая марковская модель): храним распределение вероятностей «читатель
 * прочитал столько-то слов» и режим «читает / пауза». Каждое распознанное слово обновляет распределение:
 *  - совпало со словом текста (нечётко, с прощением окончаний) — вероятность этой позиции растёт тем сильнее,
 *    чем реже слово встречается в тексте: редкое слово — почти доказательство, «и» — почти ничего;
 *  - не совпало — вероятность смещается в состояние «пауза/мусор», подсветка стоит на месте.
 * Поэтому подсветка идёт с каждым распознанным словом, а не ждёт нескольких подряд, но одно слово по-прежнему
 * ничего не значит: сдвиг разрешается только после нескольких подтверждений ([TrackerConfig.needNear] и т. д.),
 * чем дальше сдвиг, тем больше нужно подтверждающих слов и тем увереннее должно быть распределение.
 *
 * Слова новой фразы дописываются к тому, что было до паузы, поэтому после паузы подсветка продолжается сразу.
 *
 * Не потокобезопасен.
 */
class TextTracker(val doc: Doc, private val baseConfig: TrackerConfig = TrackerConfig.NEURAL) {

    /** Результат: подсветка сдвинулась с [from] на [to]; [matched] — сколько из последних 8 слов подтвердили место. */
    class Move(val from: Int, val to: Int, val matched: Int) {
        val delta: Int get() = to - from

        /** Перескок в другое место, а не обычное чтение. */
        val far: Boolean get() = abs(delta) > FAR_DELTA
    }

    private val n = doc.size
    private val norm: Array<String> = doc.norm
    private val matcher = WordMatcher()
    private val searchConfig = baseConfig.searching()
    private var cfg = baseConfig

    /**
     * Режим поиска места (см. [TrackerConfig.searching]). Включается сам, когда подсветка потеряла уверенность и
     * распознано [TrackerConfig.lostWords] слов без подтверждения места (читатель ушёл), и выключается, как только место найдено.
     */
    var searchMode: Boolean = false
        private set(value) {
            field = value
            cfg = if (value) searchConfig else baseConfig
        }

    /** Сбросить режим поиска (например, когда читатель начал заново или поставил место вручную). */
    fun stopSearching() {
        searchMode = false
        resetLostCounters()
    }

    // «Потерялись»: распознанные слова без подтверждения места.
    private var wordsSinceMove = 0   // слов в завершённых фразах
    private var phraseWords = 0      // слов в текущей фразе
    private var phraseAtMove = 0     // сколько из них было к моменту последнего подтверждения

    private fun resetLostCounters() {
        wordsSinceMove = 0
        phraseWords = 0
        phraseAtMove = 0
    }

    private fun unconfirmedWords() = wordsSinceMove + max(0, phraseWords - phraseAtMove)

    /**
     * Вероятность того, что читатель действительно читает рядом с подсветкой (±3 слова), 0..1.
     * Падает, когда идёт мусор, кашель или чужая речь, и растёт, когда слова совпадают с текстом.
     */
    var confidence: Double = baseConfig.seekOn
        private set

    /** Подсветка стоит уверенно (а не «потерялась»). */
    val locked: Boolean get() = confidence >= baseConfig.lockThreshold

    /** Индекс слова, которое читатель должен произнести следующим (0..doc.size). */
    var position: Int = 0
        private set

    // ---------- индексы текста ----------

    private val posOf: HashMap<String, IntArray> by lazy {
        val tmp = HashMap<String, ArrayList<Int>>()
        for (i in 0 until n) tmp.getOrPut(norm[i]) { ArrayList() }.add(i)
        val out = HashMap<String, IntArray>(tmp.size * 2)
        for ((w, l) in tmp) out[w] = l.toIntArray()
        out
    }
    private val vocab: Array<String> by lazy { posOf.keys.sorted().toTypedArray() }
    private val bucket: HashMap<String, ArrayList<String>> by lazy {
        val b = HashMap<String, ArrayList<String>>()
        for (w in vocab) b.getOrPut(w.take(3)) { ArrayList() }.add(w)
        b
    }

    /** Слова словаря, похожие на распознанное слово, с похожестью, и сколько всего таких мест в тексте. */
    private class Similar(val sims: HashMap<String, Double>, val count: Int)

    private val similarCache = HashMap<String, Similar>()

    private fun similar(r: String, prefix: Boolean): Similar {
        val key = if (prefix) "$r\u0001" else r
        similarCache[key]?.let { return it }
        val sims = HashMap<String, Double>()
        bucket[r.take(3)]?.let { list ->
            for (w in list) {
                val s = matcher.similarity(r, w)
                if (s > 0.0) sims[w] = s
            }
        }
        if (posOf.containsKey(r)) sims[r] = 1.0
        if (prefix && r.length >= 3) {
            // последнее слово незаконченной фразы может быть ещё не дослушано: «верн» → «верное»
            var k = lowerBound(r)
            while (k < vocab.size && vocab[k].startsWith(r)) {
                sims[vocab[k]] = max(sims[vocab[k]] ?: 0.0, PREFIX_SIM)
                k++
            }
        }
        var cnt = 0
        for (w in sims.keys) cnt += posOf[w]!!.size
        if (similarCache.size > SIMILAR_CACHE_MAX) similarCache.clear()
        return Similar(sims, cnt).also { similarCache[key] = it }
    }

    private fun lowerBound(prefix: String): Int {
        var lo = 0
        var hi = vocab.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (vocab[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Подготовить индексы заранее (на длинных текстах это заметно). */
    fun warmUp() {
        posOf.size
        bucket.size
    }

    // ---------- распределение вероятностей ----------

    /** Состояния, отсортированные по позиции: [pos] — сколько слов прочитано; вероятности «читает» и «пауза». */
    private class Beam(
        val pos: IntArray,
        val on: DoubleArray,
        val off: DoubleArray,
        /** История совпадений лучшего пути «читает» в этом состоянии: бит 0 — последнее слово, до 8 слов. */
        val hist: IntArray,
    ) {
        val size: Int get() = pos.size

        fun indexOf(p: Int): Int {
            var lo = 0
            var hi = pos.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    pos[mid] < p -> lo = mid + 1
                    pos[mid] > p -> hi = mid - 1
                    else -> return mid
                }
            }
            return -1
        }

        fun mass(i: Int) = on[i] + off[i]
    }

    private class Cell(var on: Double, var off: Double, var bestOn: Double, var hist: Int)

    private var committed: Beam = single(0)
    private var cacheWords: List<String> = emptyList()
    private val cacheBeams = ArrayList<Beam>().also { it.add(committed) }

    private fun single(at: Int) = Beam(
        intArrayOf(at), doubleArrayOf(cfg.seekOn), doubleArrayOf(1 - cfg.seekOn), intArrayOf(0),
    )

    /** Поставить позицию вручную (долгое нажатие, оглавление, восстановление). */
    fun seek(index: Int) {
        stopSearching()
        position = index.coerceIn(0, n)
        committed = single(position)
        confidence = cfg.seekOn
        cacheWords = emptyList()
        cacheBeams.clear()
        cacheBeams.add(committed)
    }

    fun onPartial(text: String): Move? = process(text, isFinal = false)

    fun onFinal(text: String): Move? = process(text, isFinal = true)

    private fun process(text: String, isFinal: Boolean): Move? {
        if (n == 0) return null
        val words = Words.recognized(text)
        if (words.isEmpty()) return null
        val beam = statesFor(words, partial = !isFinal)
        val from = position
        val newPos = decide(beam)
        var move: Move? = null
        if (newPos != null) {
            val idx = beam.indexOf(newPos)
            move = Move(from, newPos, Integer.bitCount(beam.hist[idx]))
            position = newPos
        }
        confidence = readingMassNear(beam, position)
        trackLost(words.size, isFinal, moved = move != null)
        if (isFinal) {
            committed = beam
            cacheWords = emptyList()
            cacheBeams.clear()
            cacheBeams.add(beam)
        }
        return move
    }

    /** Следит, не потерял ли подсветку читатель: много слов подряд без подтверждения места при низкой уверенности. */
    private fun trackLost(count: Int, isFinal: Boolean, moved: Boolean) {
        if (isFinal) {
            if (!moved) wordsSinceMove += max(0, count - phraseAtMove)
            phraseWords = 0
            phraseAtMove = 0
        } else {
            phraseWords = count
        }
        when {
            moved -> {
                wordsSinceMove = 0
                phraseAtMove = phraseWords
                searchMode = false
            }
            !searchMode && !locked && unconfirmedWords() >= baseConfig.lostWords -> searchMode = true
            searchMode && locked -> searchMode = false // уверенность вернулась без сдвига: читатель продолжил с того же места
        }
    }

    /** Суммарная вероятность «читает» у состояний в ±3 словах от [pos]. */
    private fun readingMassNear(beam: Beam, pos: Int): Double {
        var m = 0.0
        for (i in 0 until beam.size) if (abs(beam.pos[i] - pos) <= 3) m += beam.on[i]
        return m
    }

    /** Распределение после слов фразы; общий префикс с прошлой гипотезой берётся из кэша. */
    private fun statesFor(words: List<String>, partial: Boolean): Beam {
        var k = 0
        while (k < words.size && k < cacheWords.size && cacheWords[k] == words[k]) k++
        k = minOf(k, cacheBeams.size - 1)
        if (partial && k >= words.size) k = max(0, words.size - 1)
        while (cacheBeams.size > k + 1) cacheBeams.removeAt(cacheBeams.size - 1)
        var beam = cacheBeams[k]
        for (idx in k until words.size) {
            beam = step(beam, words[idx], lastPartial = partial && idx == words.size - 1)
            cacheBeams.add(beam)
        }
        // Недостроенное последнее слово незаконченной фразы в кэш не кладём.
        if (partial) {
            cacheWords = words.subList(0, words.size - 1).toList()
            while (cacheBeams.size > words.size) cacheBeams.removeAt(cacheBeams.size - 1)
        } else {
            cacheWords = words.toList()
        }
        return beam
    }

    // ---------- один шаг фильтра ----------

    private fun step(beam: Beam, r: String, lastPartial: Boolean): Beam {
        val sim = similar(r, lastPartial)
        val acc = HashMap<Int, Cell>(beam.size * 8)

        fun add(s: Int, on: Double, off: Double, hist: Int) {
            if (s < 0 || s > n) return
            val c = acc[s]
            if (c == null) {
                acc[s] = Cell(on, off, on, hist)
            } else {
                c.on += on
                c.off += off
                if (on > c.bestOn) {
                    c.bestOn = on
                    c.hist = hist
                }
            }
        }

        val adv = cfg.advance
        val keep = 1 - cfg.pReread
        for (i in 0 until beam.size) {
            val j = beam.pos[i]
            val on = beam.on[i]
            val off = beam.off[i]
            val h = beam.hist[i]
            if (on > 0) {
                add(j, on * adv[0], 0.0, h)
                add(j + 1, on * adv[1], 0.0, h)
                add(j + 2, on * adv[2], 0.0, h)
                add(j + 3, on * adv[3], 0.0, h)
                add(j, 0.0, on * cfg.pStop, 0)
            }
            if (off > 0) {
                add(j, 0.0, off * (1 - cfg.pStart), 0)
                val st = off * cfg.pStart * keep
                add(j + 1, st * 0.78, 0.0, 0)
                add(j + 2, st * 0.15, 0.0, 0)
                add(j + 3, st * 0.07, 0.0, 0)
            }
        }

        // Широкие переходы (пропуск вперёд, откат, перечитывание, прыжок) — только туда, где совпало наблюдаемое слово:
        // в остальных местах они получили бы лишь «мусорный» множитель и сгинули бы. История совпадений там с нуля.
        if (sim.sims.isNotEmpty() && sim.count <= cfg.wideCap && beam.size > 0) {
            val cumOn = DoubleArray(beam.size + 1)
            val cumOff = DoubleArray(beam.size + 1)
            for (i in 0 until beam.size) {
                cumOn[i + 1] = cumOn[i] + beam.on[i]
                cumOff[i + 1] = cumOff[i] + beam.off[i]
            }
            val jump = cfg.pJump / n
            val skipRate = cfg.pSkip / 37
            val backRate = cfg.pBack / 8
            val rereadRate = cfg.pStart * cfg.pReread / 40
            for (w in sim.sims.keys) {
                for (wordIdx in posOf[w]!!) {
                    val s = wordIdx + 1
                    var m = jump
                    m += skipRate * rangeSum(beam, cumOn, s - 40, s - 4)
                    m += backRate * rangeSum(beam, cumOn, s + 1, s + 8)
                    m += rereadRate * rangeSum(beam, cumOff, s + 1, s + 40)
                    add(s, m, 0.0, 0)
                }
            }
        }

        // Наблюдение: «читает» — слово совпадает с текстом с вероятностью rho (чем реже слово, тем весомее),
        // «пауза» — слово случайное.
        val rho = cfg.rho
        val bg = max(max(sim.count, 1).toDouble() / n, cfg.backgroundFloor)
        val size = acc.size
        val ps = IntArray(size)
        val ons = DoubleArray(size)
        val offs = DoubleArray(size)
        val hs = IntArray(size)
        var total = 0.0
        var k = 0
        for ((s, c) in acc) {
            val sm = if (s >= 1) sim.sims[norm[s - 1]] ?: 0.0 else 0.0
            val eOn = (1 - rho) + rho * sm / bg
            val on2 = c.on * eOn
            val t = on2 + c.off
            if (t > 0) {
                ps[k] = s
                ons[k] = on2
                offs[k] = c.off
                hs[k] = ((c.hist shl 1) or (if (sm > 0) 1 else 0)) and 0xFF
                total += t
                k++
            }
        }
        if (total <= 0 || k == 0) return beam

        // Нормировка, отсечение слабых состояний и ограничение размера пучка.
        val inv = 1.0 / total
        var keepCount = 0
        val idx = IntArray(k)
        for (q in 0 until k) {
            ons[q] *= inv
            offs[q] *= inv
            if (ons[q] + offs[q] >= cfg.prune) idx[keepCount++] = q
        }
        if (keepCount == 0) return beam
        var chosen = idx.copyOf(keepCount)
        if (keepCount > cfg.beam) {
            chosen = chosen.sortedByDescending { ons[it] + offs[it] }.take(cfg.beam).toIntArray()
        }
        chosen = chosen.sortedBy { ps[it] }.toIntArray()
        var z = 0.0
        if (keepCount > cfg.beam) for (q in chosen) z += ons[q] + offs[q]
        val scale = if (z > 0) 1.0 / z else 1.0
        return Beam(
            IntArray(chosen.size) { ps[chosen[it]] },
            DoubleArray(chosen.size) { ons[chosen[it]] * scale },
            DoubleArray(chosen.size) { offs[chosen[it]] * scale },
            IntArray(chosen.size) { hs[chosen[it]] },
        )
    }

    /** Сумма по состояниям с позицией в [lo, hi]. */
    private fun rangeSum(beam: Beam, cum: DoubleArray, lo: Int, hi: Int): Double {
        if (hi < lo) return 0.0
        var a = 0
        var b = beam.size
        while (a < b) {
            val mid = (a + b) ushr 1
            if (beam.pos[mid] < lo) a = mid + 1 else b = mid
        }
        val left = a
        a = left
        b = beam.size
        while (a < b) {
            val mid = (a + b) ushr 1
            if (beam.pos[mid] <= hi) a = mid + 1 else b = mid
        }
        return cum[a] - cum[left]
    }

    // ---------- решение: двигать ли подсветку ----------

    private fun bestState(beam: Beam): Int {
        var best = 0
        var bestMass = -1.0
        for (i in 0 until beam.size) {
            val m = beam.mass(i)
            if (m > bestMass) {
                bestMass = m
                best = i
            }
        }
        return beam.pos[best]
    }

    /** Новая позиция подсветки или null, если уверенности нет. */
    private fun decide(beam: Beam): Int? {
        if (beam.size == 0) return null
        val s = bestState(beam)
        val i = beam.indexOf(s)
        var local = beam.mass(i)
        beam.indexOf(s - 1).let { if (it >= 0) local += beam.mass(it) }
        beam.indexOf(s + 1).let { if (it >= 0) local += beam.mass(it) }
        val d = s - position
        if (d == 0) return null
        val hist = beam.hist[i]
        if (hist and 1 == 0) return null // последнее слово должно совпасть именно здесь
        val matched = Integer.bitCount(hist)
        val ad = abs(d)

        var needMatched: Int
        val needMass: Double
        when {
            d in 1..2 -> { needMatched = cfg.needNear; needMass = cfg.tauMove }
            d in 3..10 -> { needMatched = cfg.needMid; needMass = cfg.tauMid }
            ad <= cfg.farAfter -> { needMatched = cfg.needFar; needMass = cfg.tauMid }
            ad <= cfg.hugeAfter -> { needMatched = cfg.needJump; needMass = cfg.tauFar }
            else -> { needMatched = cfg.needHuge; needMass = cfg.tauFar }
        }
        if (d < 0) {
            if (ad <= cfg.jitter) return null
            needMatched = max(needMatched, cfg.needFar)
        }
        if (matched < needMatched || local < needMass) return null
        return s
    }

    private companion object {
        const val PREFIX_SIM = 0.6
        const val SIMILAR_CACHE_MAX = 20_000
        const val FAR_DELTA = 40
    }
}
