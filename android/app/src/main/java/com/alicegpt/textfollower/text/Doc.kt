package com.alicegpt.textfollower.text

/**
 * Участок текста, который показывается на экране целиком. Длинные главы режутся на части,
 * чтобы TextView не строил разметку на сотни тысяч символов.
 *
 * @param inToc попадает ли секция в оглавление (продолжения длинных глав — нет).
 * @param firstWord/endWord диапазон индексов слов [firstWord, endWord).
 */
class Section(
    val title: String,
    val inToc: Boolean,
    val start: Int,
    val end: Int,
    val firstWord: Int,
    val endWord: Int,
)

/**
 * Разобранный текст: слова для сопоставления с речью и разметка для показа.
 *
 * Номера строк вроде «[5]» и заголовки глав («ПЕСНЬ ПЕРВАЯ») остаются в [text], но слов
 * из них в [norm] нет, поэтому на сопоставление они не влияют.
 */
class Doc(
    val text: String,
    val norm: Array<String>,
    val wordStart: IntArray,
    val wordEnd: IntArray,
    val sections: List<Section>,
    /** Пары (начало, конец) номеров строк в [text]. */
    val markerRanges: IntArray,
    /** Пары (начало, конец) заголовков в [text]. */
    val headingRanges: IntArray,
) {
    val size: Int get() = norm.size

    /** Секция, в которой лежит слово [word] (для позиции в конце текста — последняя). */
    fun sectionOfWord(word: Int): Int {
        if (sections.isEmpty()) return 0
        val w = word.coerceIn(0, maxOf(0, size - 1))
        var lo = 0
        var hi = sections.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (sections[mid].firstWord <= w) lo = mid else hi = mid - 1
        }
        // Пустые секции (одни заголовки) пропускаем.
        var s = lo
        while (s < sections.size - 1 && sections[s].endWord <= w) s++
        return s
    }

    /** Слово, содержащее символ [offset], либо ближайшее следующее. */
    fun wordAtOffset(offset: Int): Int {
        if (size == 0) return 0
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (wordEnd[mid] > offset) hi = mid else lo = mid + 1
        }
        return lo
    }

    companion object {
        const val DEFAULT_SECTION_CHARS = 30_000

        /** Метка порядка байтов в начале файла. */
        private val BOM = Char(0xFEFF).toString()

        private val HEADING_KEYWORDS = listOf(
            "песнь", "глава", "часть", "книга", "акт", "действие", "сцена", "пролог", "эпилог",
            "предисловие", "вступление", "примечания", "комментарии", "оглавление", "содержание",
        )
        // «Глава первая», «Глава 12», «Глава 12.»; но не «Песнь XX.» — так в оглавлениях и примечаниях нумеруют пункты.
        private val MIXED_CASE_HEADING =
            Regex("^(песнь|глава|часть|книга|акт|действие)\\s+([ivxlcdm]+|\\d+\\.?|[а-яё]+)$", RegexOption.IGNORE_CASE)

        fun parse(raw: String, maxSectionChars: Int = DEFAULT_SECTION_CHARS): Doc {
            val text = raw.removePrefix(BOM).replace("\r\n", "\n").replace('\r', '\n')

            val norm = ArrayList<String>()
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            val markers = ArrayList<Int>()
            val headings = ArrayList<Int>()
            val headingTitles = ArrayList<String>()

            var lineStart = 0
            while (lineStart <= text.length) {
                var lineEnd = text.indexOf('\n', lineStart)
                if (lineEnd < 0) lineEnd = text.length
                val line = text.substring(lineStart, lineEnd)
                if (isHeading(line)) {
                    val trimmedStart = lineStart + (line.length - line.trimStart().length)
                    val trimmedEnd = lineEnd - (line.length - line.trimEnd().length)
                    headings.add(trimmedStart)
                    headings.add(trimmedEnd)
                    headingTitles.add(prettyTitle(line))
                } else {
                    tokenizeLine(text, lineStart, lineEnd, norm, starts, ends, markers)
                }
                if (lineEnd >= text.length) break
                lineStart = lineEnd + 1
            }

            val wordStart = starts.toIntArray()
            val wordEnd = ends.toIntArray()
            val sections = buildSections(text, headings, headingTitles, wordStart, maxSectionChars)
            return Doc(text, norm.toTypedArray(), wordStart, wordEnd, sections, markers.toIntArray(), headings.toIntArray())
        }

        fun isHeading(line: String): Boolean {
            val t = line.trim()
            if (t.isEmpty() || t.length > 60) return false
            val lower = t.lowercase()
            val keyword = HEADING_KEYWORDS.firstOrNull { lower.startsWith(it) } ?: return false
            val rest = lower.substring(keyword.length)
            if (rest.isNotEmpty() && !rest[0].isWhitespace() && rest[0] != '.') return false
            val letters = t.filter { it.isLetter() }
            if (letters.isNotEmpty() && letters.all { it.isUpperCase() }) return true
            return t.length <= 30 && MIXED_CASE_HEADING.matches(t)
        }

        private fun prettyTitle(line: String): String {
            val t = line.trim().replace(Regex("\\s+"), " ").trimEnd('.')
            val letters = t.filter { it.isLetter() }
            val shout = letters.isNotEmpty() && letters.all { it.isUpperCase() }
            val base = if (shout) t.lowercase() else t
            return base.replaceFirstChar { it.uppercase() }
        }

        /** Слова строки; номера строк «[5]» пропускаются и запоминаются. */
        private fun tokenizeLine(
            text: String,
            from: Int,
            to: Int,
            norm: ArrayList<String>,
            starts: ArrayList<Int>,
            ends: ArrayList<Int>,
            markers: ArrayList<Int>,
        ) {
            var i = from
            while (i < to) {
                val c = text[i]
                if (c == '[') {
                    val close = markerEnd(text, i, to)
                    if (close > 0) {
                        markers.add(i)
                        markers.add(close)
                        i = close
                        continue
                    }
                }
                if (Words.isWordStart(c)) {
                    val s = i
                    i++
                    while (i < to && Words.isWordPart(text[i])) i++
                    val w = Words.normalize(text.substring(s, i))
                    if (w.isNotEmpty()) {
                        norm.add(w)
                        starts.add(s)
                        ends.add(i)
                    }
                } else {
                    i++
                }
            }
        }

        /** Конец «[123]», начинающегося в [i]; 0, если это не номер строки. */
        private fun markerEnd(text: String, i: Int, to: Int): Int {
            var j = i + 1
            val digitsFrom = j
            while (j < to && text[j] in '0'..'9') j++
            val digits = j - digitsFrom
            return if (digits in 1..6 && j < to && text[j] == ']') j + 1 else 0
        }

        private fun buildSections(
            text: String,
            headings: List<Int>,
            titles: List<String>,
            wordStart: IntArray,
            maxChars: Int,
        ): List<Section> {
            class Bound(val title: String, val start: Int)

            val bounds = ArrayList<Bound>()
            if (titles.isEmpty()) {
                bounds.add(Bound("Весь текст", 0))
            } else {
                val firstHeading = headings[0]
                if (firstWordIndex(wordStart, 0) < firstWordIndex(wordStart, firstHeading)) {
                    bounds.add(Bound("Начало", 0))
                }
                for (k in titles.indices) bounds.add(Bound(titles[k], headings[2 * k]))
            }

            val sections = ArrayList<Section>()
            for (b in bounds.indices) {
                val bStart = bounds[b].start
                val bEnd = if (b + 1 < bounds.size) bounds[b + 1].start else text.length
                var pieceStart = bStart
                var first = true
                while (pieceStart < bEnd) {
                    val pieceEnd = pieceEnd(text, pieceStart, bEnd, maxChars)
                    sections.add(
                        Section(
                            title = if (first) bounds[b].title else bounds[b].title + " (продолжение)",
                            inToc = first,
                            start = pieceStart,
                            end = pieceEnd,
                            firstWord = firstWordIndex(wordStart, pieceStart),
                            endWord = firstWordIndex(wordStart, pieceEnd),
                        ),
                    )
                    first = false
                    pieceStart = pieceEnd
                }
                if (first) { // пустая секция из одного заголовка
                    sections.add(
                        Section(bounds[b].title, true, bStart, bEnd, firstWordIndex(wordStart, bStart), firstWordIndex(wordStart, bStart)),
                    )
                }
            }
            return sections
        }

        /** Конец куска не длиннее [maxChars]: по возможности на границе строки. */
        private fun pieceEnd(text: String, start: Int, limit: Int, maxChars: Int): Int {
            if (limit - start <= maxChars) return limit
            val hard = start + maxChars
            val nl = text.lastIndexOf('\n', hard - 1)
            return if (nl > start + maxChars / 2) nl + 1 else hard
        }

        /** Индекс первого слова, начинающегося не раньше [offset]. */
        private fun firstWordIndex(wordStart: IntArray, offset: Int): Int {
            var lo = 0
            var hi = wordStart.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (wordStart[mid] >= offset) hi = mid else lo = mid + 1
            }
            return lo
        }
    }
}
