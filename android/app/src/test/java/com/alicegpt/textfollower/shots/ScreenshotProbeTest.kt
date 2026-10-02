package com.alicegpt.textfollower.shots

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.EditText
import com.alicegpt.textfollower.MainActivity
import com.alicegpt.textfollower.R
import com.alicegpt.textfollower.Settings
import com.alicegpt.textfollower.speech.EngineKind
import com.alicegpt.textfollower.speech.ModelStore
import com.alicegpt.textfollower.testutil.FakeEngine
import com.alicegpt.textfollower.text.Doc
import com.alicegpt.textfollower.text.TextLoader
import com.alicegpt.textfollower.ui.ReaderView
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

/** Разовая проверка внешнего вида (не часть набора): снимки экрана в каталог SHOT_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h760dp-xxhdpi")
class ScreenshotProbeTest {
    private val book = File("src/main/assets/odyssey_zhukovsky.txt")
    private val doc: Doc by lazy { Doc.parse(TextLoader.load(book.readBytes())) }
    private val engines = ArrayList<FakeEngine>()
    private val app get() = RuntimeEnvironment.getApplication()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(theme: Int): MainActivity {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        for (kind in EngineKind.values()) {
            val dir = ModelStore.of(kind).modelDir(app); dir.mkdirs(); File(dir, ".installed").writeText("ok")
        }
        val s = Settings(app)
        s.seenIntro = true
        s.theme = theme
        MainActivity.engineFactory = { kind, l -> FakeEngine(l, kind).also { engines += it } }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        val reader = activity.findViewById<ReaderView>(R.id.reader)
        var w = 0
        while (reader.textView.length() < 1000 && w < 400) { idle(); Thread.sleep(50); w++ }
        idle()
        return activity
    }

    private fun words(from: Int, n: Int) = (from until from + n).joinToString(" ") { doc.norm[it] }

    private fun shot(view: View, name: String) {
        val dm = app.resources.displayMetrics
        view.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bmp = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(System.getenv("SHOT_DIR"), "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT $name ${bmp.width}x${bmp.height}")
    }

    private fun screen(a: MainActivity, name: String) {
        val dm = a.resources.displayMetrics
        val root = a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        val bmp = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(System.getenv("SHOT_DIR"), "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT $name ${bmp.width}x${bmp.height}")
    }

    @Test
    fun probe() {
        assumeTrue("SHOT_DIR не задан", System.getenv("SHOT_DIR") != null)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        // 1. тёмная тема, идёт чтение
        var a = launch(Settings.THEME_DARK)
        a.findViewById<View>(R.id.micButton).performClick(); idle()
        var e = engines.last()
        e.partial(words(0, 14)); e.level(0.6f); idle()
        screen(a, "1_dark_reading")
        a.finish()

        // 2. сепия, ожидание
        a = launch(Settings.THEME_SEPIA)
        screen(a, "2_sepia_idle")
        a.finish()

        // 3. бумага, потеряли место (баннер, оранжевая кнопка)
        a = launch(Settings.THEME_LIGHT)
        a.findViewById<View>(R.id.micButton).performClick(); idle()
        e = engines.last()
        e.partial(words(100, 12)); e.final(words(100, 12))
        e.final("кхм ну это самое да кхм ага да так"); idle()
        screen(a, "3_light_lost")
        a.finish()

        // 4. чёрная, ошибка микрофона
        a = launch(Settings.THEME_BLACK)
        a.findViewById<View>(R.id.micButton).performClick(); idle()
        engines.last().error("Не удалось открыть микрофон"); idle()
        screen(a, "4_black_error")
        a.finish()

        // 5. настройки (тёмная)
        a = launch(Settings.THEME_DARK)
        a.findViewById<View>(R.id.settingsButton).performClick(); idle()
        shot((ShadowDialog.getLatestDialog() as AlertDialog).window!!.decorView, "5_settings")
        (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()

        // 6. поиск с результатами
        a.findViewById<View>(R.id.searchButton).performClick(); idle()
        val d = ShadowDialog.getLatestDialog() as AlertDialog
        val input = allViews(d.window!!.decorView).filterIsInstance<EditText>().first()
        input.setText("сын одиссеев"); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        shot(d.window!!.decorView, "6_search")
        d.dismiss()

        // 7. оглавление
        a.findViewById<View>(R.id.tocButton).performClick(); idle()
        shot((ShadowDialog.getLatestDialog() as AlertDialog).window!!.decorView, "7_toc")
        (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
        a.finish()

        // 8. первое знакомство
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        Settings(app).theme = Settings.THEME_SEPIA
        for (kind in EngineKind.values()) { val dir = ModelStore.of(kind).modelDir(app); dir.mkdirs(); File(dir, ".installed").writeText("ok") }
        MainActivity.engineFactory = { kind, l -> FakeEngine(l, kind).also { engines += it } }
        val b = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        idle()
        shot((ShadowDialog.getLatestDialog() as AlertDialog).window!!.decorView, "8_intro")
        b.finish()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w760dp-h360dp-land-xxhdpi")
    fun landscape() {
        assumeTrue("SHOT_DIR не задан", System.getenv("SHOT_DIR") != null)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val a = launch(Settings.THEME_DARK)
        a.findViewById<View>(R.id.micButton).performClick(); idle()
        val e = engines.last()
        e.partial(words(0, 14)); e.level(0.5f); idle()
        screen(a, "9_land_dark")
        a.finish()
    }

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) { out += v; if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(root)
        return out
    }
}
