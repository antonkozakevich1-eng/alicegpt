package com.alicegpt.textfollower.ui

import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import com.alicegpt.textfollower.text.Doc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReaderViewTest {

    private val doc = Doc.parse("Синяя лодка плывёт по тихой реке, старый мастер чинит деревянный мост.")
    private val dim = Color.rgb(100, 100, 100)
    private val pillText = Color.rgb(1, 2, 3)

    private lateinit var reader: ReaderView

    @Before
    fun setUp() {
        reader = ReaderView(RuntimeEnvironment.getApplication())
        reader.setColors(ReaderView.Colors(Color.BLACK, dim, Color.GRAY, Color.RED, Color.YELLOW, pillText))
        reader.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY))
        reader.layout(0, 0, 1000, 1500)
        reader.setSection(SectionText.build(doc, 0, reader.gutter, Color.RED))
    }

    private fun colorSpans(): List<Triple<Int, Int, Int>> {
        val sp = reader.textView.text as Spanned
        return sp.getSpans(0, sp.length, ForegroundColorSpan::class.java).map { Triple(sp.getSpanStart(it), sp.getSpanEnd(it), it.foregroundColor) }
    }

    @Test
    fun noCursorUntilOneIsSet() {
        assertNull(reader.cursor)
        assertTrue(colorSpans().none { it.third == dim })
    }

    @Test
    fun cursorDimsEverythingBeforeTheWordAndColorsTheWord() {
        val start = doc.wordStart[3]
        val end = doc.wordEnd[3]
        reader.setCursor(start, end, animate = false, scroll = false)
        assertEquals(start until end, reader.cursor)
        val spans = colorSpans()
        assertTrue(spans.contains(Triple(0, start, dim)))
        assertTrue(spans.contains(Triple(start, end, pillText)))
    }

    @Test
    fun movingTheCursorMovesTheSpansWithoutPilingUpNewOnes() {
        for (w in 0 until doc.size) reader.setCursor(doc.wordStart[w], doc.wordEnd[w], animate = false, scroll = false)
        val spans = colorSpans()
        assertEquals(1, spans.count { it.third == dim })
        assertEquals(1, spans.count { it.third == pillText })
        val last = doc.size - 1
        assertTrue(spans.contains(Triple(0, doc.wordStart[last], dim)))
    }

    @Test
    fun cursorAtTheFirstWordHasNothingToDim() {
        reader.setCursor(doc.wordStart[2], doc.wordEnd[2], animate = false, scroll = false)
        reader.setCursor(doc.wordStart[0], doc.wordEnd[0], animate = false, scroll = false)
        assertTrue(colorSpans().none { it.third == dim })
        assertEquals(doc.wordStart[0] until doc.wordEnd[0], reader.cursor)
    }

    @Test
    fun clearAndMarkAllRead() {
        reader.setCursor(doc.wordStart[4], doc.wordEnd[4], animate = false, scroll = false)
        reader.clearCursor()
        assertNull(reader.cursor)
        assertTrue(colorSpans().none { it.third == dim || it.third == pillText })
        reader.markAllRead()
        assertNull(reader.cursor)
        assertTrue(colorSpans().contains(Triple(0, reader.textView.length(), dim)))
    }

    @Test
    fun newSectionResetsTheCursor() {
        reader.setCursor(doc.wordStart[4], doc.wordEnd[4], animate = false, scroll = false)
        reader.setSection(SectionText.build(doc, 0, reader.gutter, Color.RED))
        assertNull(reader.cursor)
        assertTrue(colorSpans().none { it.third == dim || it.third == pillText })
    }

    @Test
    fun longPressReportsTheOffsetUnderTheFinger() {
        var got = -1
        reader.onLongPress = { got = it }
        val tv = reader.textView
        // касание в начале текста: смещение ≥ 0 и внутри текста
        tv.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 60f, 40f, 0))
        tv.performLongClick()
        assertTrue("смещение $got", got in 0..tv.length())
    }

    @Test
    fun lockedStateDoesNotBreakDrawing() {
        reader.setCursor(doc.wordStart[1], doc.wordEnd[1], animate = false, scroll = false)
        reader.setLocked(false)
        reader.setLocked(true)
        reader.setTypography(30f, serif = false, lineSpacing = 1.5f)
        reader.autoScroll = false
        reader.scrollToCursor(force = false)
        assertFalse(reader.autoScroll)
        assertEquals(doc.wordStart[1] until doc.wordEnd[1], reader.cursor)
    }
}
