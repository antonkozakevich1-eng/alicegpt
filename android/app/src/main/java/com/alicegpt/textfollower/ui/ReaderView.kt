package com.alicegpt.textfollower.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Читалка: прокручиваемый текст, плавно «едущая» плашка на текущем слове, приглушённое прочитанное и
 * автопрокрутка. Текст остаётся обычным TextView (шрифты, переносы), плашка рисуется под ним.
 */
class ReaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    class Colors(val text: Int, val dim: Int, val marker: Int, val heading: Int, val pill: Int, val pillText: Int)

    /** Плашка под текущим словом. */
    private class PillView(context: Context) : View(context) {
        val rect = RectF()
        var shown = false
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var radius = 0f

        override fun onDraw(canvas: Canvas) {
            if (shown) canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private val density = resources.displayMetrics.density
    private val scroll = ScrollView(context)
    private val content = FrameLayout(context)
    private val pill = PillView(context)
    val textView = TextView(context)

    /** Номера строк на полях. */
    val gutter = GutterStyle(widthPx = (36 * density).toInt(), padRightPx = (8 * density).toInt())

    private var colors = Colors(0xFF000000.toInt(), 0xFF888888.toInt(), 0xFF888888.toInt(), 0xFF884400.toInt(), 0xFFFFD54F.toInt(), 0xFF000000.toInt())
    private var spannable: Spannable? = null
    private var readSpan: ForegroundColorSpan? = null
    private var pillTextSpan: ForegroundColorSpan? = null

    private var cursorStart = -1
    private var cursorEnd = -1
    private var animator: ValueAnimator? = null
    private var lastUserTouch = 0L
    private var downX = 0f
    private var downY = 0f
    private var locked = true

    /** Слово, на котором стоит подсветка: [start, end) в тексте секции, либо null. */
    val cursor: IntRange? get() = if (cursorStart >= 0) cursorStart until cursorEnd else null

    /** Прокручивать за подсветкой. */
    var autoScroll = true

    /** Долгое нажатие на слово: смещение символа в тексте секции. */
    var onLongPress: ((offset: Int) -> Unit)? = null

    init {
        pill.radius = 8 * density
        content.addView(pill, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroll.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroll.overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        scroll.isVerticalScrollBarEnabled = true
        scroll.isFillViewport = true
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val sidePad = (12 * density).toInt()
        textView.setPadding(sidePad, (16 * density).toInt(), (20 * density).toInt(), (220 * density).toInt())
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        textView.setLineSpacing(0f, 1.35f)
        textView.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        setSimpleLineBreaks()
        textView.setTextIsSelectable(false)
        trackTouches()
        textView.setOnLongClickListener {
            val off = textView.getOffsetForPosition(downX, downY)
            if (off >= 0) onLongPress?.invoke(off)
            true
        }
        // Размер шрифта, ширина или поворот экрана меняют разметку — плашку нужно пересчитать.
        textView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) updatePill(animate = false)
        }
        applyColors()
    }

    /** Строки переносятся «просто»: так слова не прыгают между строками при смене размера шрифта. */
    @SuppressLint("WrongConstant") // lint требует константы LineBreaker (API 29), а TextView принимает те же значения Layout
    private fun setSimpleLineBreaks() {
        textView.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
    }

    /** Слушатели только наблюдают за касаниями (когда пользователь листает сам, автопрокрутка уступает), клики не перехватывают. */
    @SuppressLint("ClickableViewAccessibility")
    private fun trackTouches() {
        scroll.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) lastUserTouch = System.currentTimeMillis()
            false
        }
        textView.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                downX = ev.x
                downY = ev.y
            }
            false
        }
    }

    // ---------- оформление ----------

    /** Цвета темы; вызывать до [setSection] (спаны секции создаются с этими цветами). */
    fun setColors(c: Colors) {
        colors = c
        applyColors()
    }

    private fun applyColors() {
        textView.setTextColor(colors.text)
        gutter.color = colors.marker
        pill.paint.color = colors.pill
        pill.paint.alpha = if (locked) 255 else 120
        textView.invalidate()
        pill.invalidate()
    }

    fun setTypography(sizeSp: Float, serif: Boolean, lineSpacing: Float) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        textView.typeface = if (serif) Typeface.SERIF else Typeface.SANS_SERIF
        textView.setLineSpacing(0f, lineSpacing)
        gutter.textSizePx = sizeSp * 0.62f * resources.displayMetrics.scaledDensity
        textView.invalidate()
    }

    /** Уверенное положение подсветки (плотная плашка) или поиск места (бледная). */
    fun setLocked(value: Boolean) {
        if (locked == value) return
        locked = value
        pill.paint.alpha = if (value) 255 else 120
        pill.invalidate()
    }

    // ---------- содержимое ----------

    /** Показывает новую секцию; подсветка убирается до первого [setCursor]. */
    fun setSection(text: Spannable) {
        animator?.cancel()
        textView.setText(text, TextView.BufferType.SPANNABLE)
        spannable = textView.text as Spannable
        readSpan = ForegroundColorSpan(colors.dim)
        pillTextSpan = ForegroundColorSpan(colors.pillText)
        cursorStart = -1
        cursorEnd = -1
        pill.shown = false
        pill.invalidate()
        scroll.scrollTo(0, 0)
    }

    /** Ставит подсветку на слово [start, end) секции: всё до него приглушается, плашка плавно переезжает. */
    fun setCursor(start: Int, end: Int, animate: Boolean, scroll: Boolean) {
        val sp = spannable ?: return
        val read = readSpan ?: return
        val pillText = pillTextSpan ?: return
        cursorStart = start
        cursorEnd = end
        val s = start.coerceIn(0, sp.length)
        val e = end.coerceIn(s, sp.length)
        if (s > 0) sp.setSpan(read, 0, s, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) else sp.removeSpan(read)
        sp.setSpan(pillText, s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        updatePill(animate)
        if (scroll) scrollToCursor(force = false)
    }

    /** Подсветки нет и ничего не приглушено. */
    fun clearCursor() {
        val sp = spannable ?: return
        cursorStart = -1
        cursorEnd = -1
        readSpan?.let { sp.removeSpan(it) }
        pillTextSpan?.let { sp.removeSpan(it) }
        pill.shown = false
        pill.invalidate()
    }

    /** Подсветки нет, весь текст приглушён (дочитали до конца). */
    fun markAllRead() {
        val sp = spannable ?: return
        clearCursor()
        readSpan?.let { sp.setSpan(it, 0, sp.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    // ---------- плашка ----------

    private val target = RectF()

    private fun wordRect(start: Int, end: Int, out: RectF): Boolean {
        val layout = textView.layout ?: return false
        val len = textView.length()
        val s = start.coerceIn(0, len)
        val line = layout.getLineForOffset(s)
        val padL = textView.totalPaddingLeft.toFloat()
        val padT = textView.totalPaddingTop.toFloat()
        val left = layout.getPrimaryHorizontal(s) + padL
        val right = if (end > layout.getLineEnd(line)) layout.getLineRight(line) + padL else layout.getPrimaryHorizontal(end.coerceIn(s, len)) + padL
        val baseline = layout.getLineBaseline(line) + padT
        val paint = textView.paint
        val padX = 4 * density
        val padY = 2 * density
        out.set(left - padX, baseline + paint.ascent() - padY, right + padX, baseline + paint.descent() + padY)
        return true
    }

    private fun updatePill(animate: Boolean) {
        if (cursorStart < 0) return
        if (!wordRect(cursorStart, cursorEnd, target)) {
            // разметка ещё не готова — повторим после неё
            textView.post { if (cursorStart >= 0 && textView.layout != null) updatePill(animate) }
            return
        }
        animator?.cancel()
        val from = RectF(pill.rect)
        val canAnimate = animate && pill.shown && abs(from.top - target.top) < textView.lineHeight * 3
        pill.shown = true
        if (!canAnimate) {
            pill.rect.set(target)
            pill.invalidate()
            return
        }
        val to = RectF(target)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 130
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                pill.rect.set(
                    from.left + (to.left - from.left) * f, from.top + (to.top - from.top) * f,
                    from.right + (to.right - from.right) * f, from.bottom + (to.bottom - from.bottom) * f,
                )
                pill.invalidate()
            }
            start()
        }
    }

    private fun abs(v: Float) = if (v < 0) -v else v

    // ---------- прокрутка ----------

    /** Держит текущее слово в верхней части экрана, не дёргая текст при каждом слове. */
    fun scrollToCursor(force: Boolean) {
        if (cursorStart < 0) return
        if (!force && (!autoScroll || System.currentTimeMillis() - lastUserTouch < USER_SCROLL_PAUSE_MS)) return
        if (!wordRect(cursorStart, cursorEnd, target)) {
            textView.post { if (textView.layout != null) scrollToCursor(force) }
            return
        }
        val h = scroll.height
        if (h <= 0) {
            post { scrollToCursor(force) }
            return
        }
        val y = scroll.scrollY
        if (force || target.top < y + h * 0.12f || target.bottom > y + h * 0.62f) {
            val dest = (target.top - h * 0.30f).toInt().coerceAtLeast(0)
            if (force) scroll.scrollTo(0, dest) else scroll.smoothScrollTo(0, dest)
        }
    }

    private companion object {
        const val USER_SCROLL_PAUSE_MS = 4000L
    }
}
