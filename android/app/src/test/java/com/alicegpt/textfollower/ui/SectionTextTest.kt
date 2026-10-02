package com.alicegpt.textfollower.ui

import android.graphics.Typeface
import android.text.Layout
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import com.alicegpt.textfollower.text.Doc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SectionTextTest {

    private val doc = Doc.parse(
        """
        ПЕСНЬ ПЕРВАЯ

        Синяя лодка плывёт по тихой реке,
        [5] старый мастер чинит деревянный мост.

        ПЕСНЬ ВТОРАЯ

        Холодный ветер приносит запах мокрой травы.
        """.trimIndent(),
    )
    private val gutter = GutterStyle(widthPx = 40, padRightPx = 8)
    private val headingColor = 0xFF884400.toInt()

    @Test
    fun textOfASectionIsKeptCharacterForCharacter() {
        for ((i, sec) in doc.sections.withIndex()) {
            val sp = SectionText.build(doc, i, gutter, headingColor)
            // смещения слов не должны «поплыть»: показываемый текст — ровно кусок документа
            assertEquals(doc.text.substring(sec.start, sec.end), sp.toString())
        }
    }

    @Test
    fun headingIsBoldBiggerColoredAndCentered() {
        val sp = SectionText.build(doc, 0, gutter, headingColor)
        val end = "ПЕСНЬ ПЕРВАЯ".length
        val colors = sp.getSpans(0, end, ForegroundColorSpan::class.java)
        assertTrue(colors.any { it.foregroundColor == headingColor && sp.getSpanStart(it) == 0 && sp.getSpanEnd(it) == end })
        assertTrue(sp.getSpans(0, end, StyleSpan::class.java).any { it.style == Typeface.BOLD })
        assertTrue(sp.getSpans(0, end, RelativeSizeSpan::class.java).any { it.sizeChange > 1f })
        assertTrue(sp.getSpans(0, end, AlignmentSpan::class.java).any { it.alignment == Layout.Alignment.ALIGN_CENTER })
    }

    @Test
    fun lineNumberIsHiddenInlineButStaysInTheText() {
        val sp = SectionText.build(doc, 0, gutter, headingColor)
        val at = sp.indexOf("[5]")
        assertTrue(at > 0)
        // «[5] » закрыт спаном нулевой ширины вместе с пробелом после него
        val hidden = sp.getSpans(at, at + 3, android.text.style.ReplacementSpan::class.java)
        assertEquals(1, hidden.size)
        assertEquals(at, sp.getSpanStart(hidden[0]))
        assertEquals(at + 4, sp.getSpanEnd(hidden[0]))
        assertEquals(0, hidden[0].getSize(android.graphics.Paint(), sp, at, at + 4, null))
    }

    @Test
    fun everyParagraphGetsTheSameLeftMarginForTheGutter() {
        val sp = SectionText.build(doc, 0, gutter, headingColor)
        val margins = sp.getSpans(0, sp.length, android.text.style.LeadingMarginSpan::class.java)
        assertTrue(margins.size >= 3)
        assertTrue(margins.all { it.getLeadingMargin(true) == gutter.widthPx && it.getLeadingMargin(false) == gutter.widthPx })
        // абзацные спаны заканчиваются на переводе строки, как требует Android
        for (m in margins) {
            val end = sp.getSpanEnd(m)
            assertTrue(end == sp.length || sp[end - 1] == '\n')
        }
    }

    @Test
    fun secondSectionHasItsOwnHeadingAndNoForeignMarkers() {
        val sp = SectionText.build(doc, 1, gutter, headingColor)
        assertTrue(sp.startsWith("ПЕСНЬ ВТОРАЯ"))
        assertTrue(!sp.contains("[5]"))
    }
}
