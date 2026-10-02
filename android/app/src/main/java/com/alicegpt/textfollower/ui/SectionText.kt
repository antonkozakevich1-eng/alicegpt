package com.alicegpt.textfollower.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import com.alicegpt.textfollower.text.Doc

/** Оформление номеров строк на полях: меняется вместе с размером шрифта без пересборки текста. */
class GutterStyle(val widthPx: Int, val padRightPx: Int) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    var color: Int = 0x80808080.toInt()
    var textSizePx: Float = 24f
}

/** Номер строки на левом поле абзаца (в самом тексте он невидим). Все строки абзаца получают одинаковый отступ. */
private class GutterSpan(private val label: String?, private val style: GutterStyle) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean) = style.widthPx

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
    ) {
        if (!first || label == null) return
        val paint = style.paint
        paint.textSize = style.textSizePx
        paint.color = style.color
        val w = paint.measureText(label)
        val tx = if (dir > 0) x + style.widthPx - style.padRightPx - w else x - style.widthPx + style.padRightPx.toFloat()
        c.drawText(label, tx, baseline.toFloat(), paint)
    }
}

/** Нулевая ширина: «[5] » остаётся в тексте (смещения слов не меняются), но не занимает места. */
private class HiddenSpan : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?) = 0

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) = Unit
}

/** Собирает оформленный текст одной секции: заголовки по центру, номера строк на полях. */
object SectionText {

    fun build(doc: Doc, sectionIndex: Int, gutter: GutterStyle, headingColor: Int): SpannableString {
        val sec = doc.sections[sectionIndex]
        val text = doc.text.substring(sec.start, sec.end)
        val sp = SpannableString(text)
        val flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE

        // номера строк, попавшие в секцию: (начало, конец, число)
        class Marker(val from: Int, val to: Int, val label: String)
        val markers = ArrayList<Marker>()
        var mi = firstRangeAtOrAfter(doc.markerRanges, sec.start)
        while (mi < doc.markerRanges.size / 2 && doc.markerRanges[2 * mi] < sec.end) {
            val a = doc.markerRanges[2 * mi] - sec.start
            val b = doc.markerRanges[2 * mi + 1] - sec.start
            var e = b
            while (e < text.length && text[e] == ' ') e++
            markers.add(Marker(a, e, text.substring(a + 1, b - 1)))
            mi++
        }
        for (m in markers) sp.setSpan(HiddenSpan(), m.from, m.to, flags)

        // заголовки
        var hi = firstRangeAtOrAfter(doc.headingRanges, sec.start)
        val headingParagraphs = HashSet<Int>()
        while (hi < doc.headingRanges.size / 2 && doc.headingRanges[2 * hi] < sec.end) {
            val a = doc.headingRanges[2 * hi] - sec.start
            val b = doc.headingRanges[2 * hi + 1] - sec.start
            sp.setSpan(ForegroundColorSpan(headingColor), a, b, flags)
            sp.setSpan(StyleSpan(Typeface.BOLD), a, b, flags)
            sp.setSpan(RelativeSizeSpan(1.25f), a, b, flags)
            headingParagraphs.add(a)
            hi++
        }

        // абзацы: поле под номер, заголовки по центру
        var p0 = 0
        var mk = 0
        while (p0 <= text.length) {
            var p1 = text.indexOf('\n', p0)
            if (p1 < 0) p1 = text.length
            if (p1 > p0) {
                var label: String? = null
                while (mk < markers.size && markers[mk].from < p1) {
                    if (markers[mk].from >= p0 && label == null) label = markers[mk].label
                    mk++
                }
                val end = if (p1 < text.length) p1 + 1 else p1 // абзацный спан должен включать перевод строки
                sp.setSpan(GutterSpan(label, gutter), p0, end, flags)
                if (headingParagraphs.any { it in p0 until p1 }) {
                    sp.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), p0, end, flags)
                }
            }
            if (p1 >= text.length) break
            p0 = p1 + 1
        }
        return sp
    }

    private fun firstRangeAtOrAfter(ranges: IntArray, offset: Int): Int {
        var lo = 0
        var hi = ranges.size / 2
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ranges[2 * mid + 1] > offset) hi = mid else lo = mid + 1
        }
        return lo
    }
}
