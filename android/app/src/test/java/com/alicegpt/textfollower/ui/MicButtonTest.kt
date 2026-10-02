package com.alicegpt.textfollower.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MicButtonTest {

    private fun button(): MicButton {
        val b = MicButton(RuntimeEnvironment.getApplication())
        b.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        b.layout(0, 0, b.measuredWidth, b.measuredHeight)
        return b
    }

    @Test
    fun descriptionFollowsTheState() {
        val b = button()
        val descriptions = MicButton.State.values().map { s ->
            b.state = s
            b.contentDescription.toString()
        }
        // у каждого состояния своё описание для озвучивания, кроме пары «слушаю / ищу», где кнопка одна и та же — «пауза»
        assertEquals(descriptions[MicButton.State.LISTENING.ordinal], descriptions[MicButton.State.SEARCHING.ordinal])
        assertNotEquals(descriptions[MicButton.State.IDLE.ordinal], descriptions[MicButton.State.LISTENING.ordinal])
        assertNotEquals(descriptions[MicButton.State.BUSY.ordinal], descriptions[MicButton.State.ERROR.ordinal])
    }

    @Test
    fun everyStateDrawsWithLevelAndProgress() {
        val b = button()
        val bmp = Bitmap.createBitmap(b.measuredWidth, b.measuredHeight, Bitmap.Config.ARGB_8888)
        for (s in MicButton.State.values()) {
            b.state = s
            b.progress = if (s == MicButton.State.BUSY) 0.4f else -1f
            b.setLevel(0.7f)
            b.draw(Canvas(bmp))
        }
        b.setLevel(5f)
        b.setLevel(-3f)
        b.draw(Canvas(bmp))
    }
}
