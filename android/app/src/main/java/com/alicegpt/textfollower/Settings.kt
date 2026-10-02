package com.alicegpt.textfollower

import android.content.Context
import android.content.SharedPreferences
import com.alicegpt.textfollower.speech.EngineKind

/** Настройки и сохранённые позиции чтения. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 0 — как в системе, 1 — бумага (светлая), 2 — сепия, 3 — тёмная, 4 — чёрная (для AMOLED). */
    var theme: Int
        get() = prefs.getInt("theme", THEME_DARK)
        set(v) = prefs.edit().putInt("theme", v).apply()

    var fontSp: Float
        get() = prefs.getFloat("fontSp", 22f)
        set(v) = prefs.edit().putFloat("fontSp", v.coerceIn(MIN_FONT, MAX_FONT)).apply()

    /** Шрифт с засечками (для книг приятнее). */
    var serif: Boolean
        get() = prefs.getBoolean("serif", true)
        set(v) = prefs.edit().putBoolean("serif", v).apply()

    /** Межстрочный интервал, множитель. */
    var lineSpacing: Float
        get() = prefs.getFloat("lineSpacing", 1.35f)
        set(v) = prefs.edit().putFloat("lineSpacing", v.coerceIn(1.1f, 1.9f)).apply()

    /** Чувствительность микрофона 0..100. */
    var sensitivity: Int
        get() = prefs.getInt("sensitivity", 60)
        set(v) = prefs.edit().putInt("sensitivity", v.coerceIn(0, 100)).apply()

    /** Какой движок распознавания использовать. */
    var engine: EngineKind
        get() = EngineKind.of(prefs.getInt("engine", EngineKind.NEURAL.id))
        set(v) = prefs.edit().putInt("engine", v.id).apply()

    /** Подсказывать распознавателю слова из текста вокруг позиции. */
    var useContext: Boolean
        get() = prefs.getBoolean("useContext", true)
        set(v) = prefs.edit().putBoolean("useContext", v).apply()

    /** Прокручивать текст за подсветкой. */
    var autoScroll: Boolean
        get() = prefs.getBoolean("autoScroll", true)
        set(v) = prefs.edit().putBoolean("autoScroll", v).apply()

    var seenIntro: Boolean
        get() = prefs.getBoolean("seenIntro", false)
        set(v) = prefs.edit().putBoolean("seenIntro", v).apply()

    /** Ключ документа, который открыт сейчас («asset» или «file:…»). */
    var currentDoc: String
        get() = prefs.getString("currentDoc", DOC_ASSET) ?: DOC_ASSET
        set(v) = prefs.edit().putString("currentDoc", v).apply()

    var customName: String
        get() = prefs.getString("customName", "") ?: ""
        set(v) = prefs.edit().putString("customName", v).apply()

    fun position(docKey: String): Int = prefs.getInt("pos:$docKey", 0)

    fun savePosition(docKey: String, position: Int) = prefs.edit().putInt("pos:$docKey", position).apply()

    companion object {
        const val DOC_ASSET = "asset"
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_SEPIA = 2
        const val THEME_DARK = 3
        const val THEME_BLACK = 4
        const val MIN_FONT = 14f
        const val MAX_FONT = 48f
    }
}
