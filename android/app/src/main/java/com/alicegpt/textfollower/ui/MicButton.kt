package com.alicegpt.textfollower.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import com.alicegpt.textfollower.R

/** Большая кнопка микрофона: пульсирующее кольцо по громкости голоса, цвет показывает состояние. */
class MicButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class State { IDLE, LISTENING, SEARCHING, BUSY, ERROR }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val arcBounds = RectF()
    private val micIcon: Drawable = context.getDrawable(R.drawable.ic_mic)!!.mutate()
    private val pauseIcon: Drawable = context.getDrawable(R.drawable.ic_pause)!!.mutate()

    private var accent = 0xFF3D5AFE.toInt()
    private var good = 0xFF2E7D32.toInt()
    private var warn = 0xFFB26A00.toInt()
    private var bad = 0xFFC62828.toInt()
    private var onAccent = 0xFFFFFFFF.toInt()
    private var idleBg = 0xFF888888.toInt()

    private var shownLevel = 0f

    init {
        contentDescription = context.getString(R.string.start)
    }

    var state: State = State.IDLE
        set(v) {
            if (field != v) {
                field = v
                contentDescription = when (v) {
                    State.IDLE -> context.getString(R.string.start)
                    State.LISTENING, State.SEARCHING -> context.getString(R.string.pause)
                    State.BUSY -> context.getString(R.string.mic_busy)
                    State.ERROR -> context.getString(R.string.retry)
                }
                invalidate()
            }
        }

    /** 0..1 — доля загрузки модели (кольцо вокруг кнопки); -1 — не показывать. */
    var progress: Float = -1f
        set(v) {
            field = v
            invalidate()
        }

    fun setColors(accent: Int, good: Int, warn: Int, bad: Int, onAccent: Int, idleBg: Int) {
        this.accent = accent
        this.good = good
        this.warn = warn
        this.bad = bad
        this.onAccent = onAccent
        this.idleBg = idleBg
        invalidate()
    }

    /** Громкость голоса 0..1: кольцо вокруг кнопки «дышит». */
    fun setLevel(level: Float) {
        shownLevel = maxOf(level.coerceIn(0f, 1f), shownLevel * 0.85f)
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (92 * density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = 33 * density
        val color = when (state) {
            State.IDLE -> accent
            State.LISTENING -> good
            State.SEARCHING -> warn
            State.BUSY -> idleBg
            State.ERROR -> bad
        }
        val listening = state == State.LISTENING || state == State.SEARCHING
        if (listening) {
            ring.color = color
            ring.alpha = (70 + 60 * shownLevel).toInt().coerceAtMost(255)
            canvas.drawCircle(cx, cy, base + 4 * density + shownLevel * 11 * density, ring)
            shownLevel *= 0.9f
            if (shownLevel > 0.01f) postInvalidateOnAnimation()
        }
        fill.color = color
        canvas.drawCircle(cx, cy, base, fill)

        if (state == State.BUSY && progress >= 0f) {
            arc.color = accent
            val r = base + 5 * density
            arcBounds.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(arcBounds, -90f, 360f * progress.coerceIn(0f, 1f), false, arc)
        }

        val icon = if (listening) pauseIcon else micIcon
        icon.setTint(if (state == State.BUSY) 0xB3FFFFFF.toInt() else onAccent)
        val half = (13 * density).toInt()
        icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        icon.draw(canvas)
    }
}
