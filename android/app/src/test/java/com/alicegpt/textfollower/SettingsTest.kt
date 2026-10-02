package com.alicegpt.textfollower

import android.content.Context
import com.alicegpt.textfollower.speech.EngineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clear() {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsAreSensible() {
        val s = Settings(app)
        assertEquals(EngineKind.NEURAL, s.engine)
        assertEquals(Settings.THEME_DARK, s.theme)
        assertEquals(22f, s.fontSp, 0f)
        assertTrue(s.serif && s.useContext && s.autoScroll)
        assertFalse(s.seenIntro)
        assertEquals(Settings.DOC_ASSET, s.currentDoc)
        assertEquals(0, s.position(Settings.DOC_ASSET))
    }

    @Test
    fun valuesAreRememberedBetweenInstances() {
        val a = Settings(app)
        a.engine = EngineKind.VOSK
        a.theme = Settings.THEME_SEPIA
        a.fontSp = 30f
        a.sensitivity = 80
        a.useContext = false
        a.customName = "книга.txt"
        a.savePosition("file:1", 4321)
        val b = Settings(app)
        assertEquals(EngineKind.VOSK, b.engine)
        assertEquals(Settings.THEME_SEPIA, b.theme)
        assertEquals(30f, b.fontSp, 0f)
        assertEquals(80, b.sensitivity)
        assertFalse(b.useContext)
        assertEquals("книга.txt", b.customName)
        assertEquals(4321, b.position("file:1"))
        assertEquals(0, b.position(Settings.DOC_ASSET))
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val s = Settings(app)
        s.fontSp = 500f
        assertEquals(Settings.MAX_FONT, s.fontSp, 0f)
        s.fontSp = 1f
        assertEquals(Settings.MIN_FONT, s.fontSp, 0f)
        s.sensitivity = 1000
        assertEquals(100, s.sensitivity)
        s.sensitivity = -5
        assertEquals(0, s.sensitivity)
        s.lineSpacing = 9f
        assertEquals(1.9f, s.lineSpacing, 0f)
    }

    @Test
    fun unknownEngineIdFallsBackToTheNeuralOne() {
        assertEquals(EngineKind.NEURAL, EngineKind.of(77))
        assertEquals(EngineKind.VOSK, EngineKind.of(EngineKind.VOSK.id))
    }
}
