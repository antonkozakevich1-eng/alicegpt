package com.alicegpt.textfollower

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.TextView
import com.alicegpt.textfollower.speech.ModelInstaller
import com.alicegpt.textfollower.text.Doc
import com.alicegpt.textfollower.text.TextLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * Экран приложения целиком, на JVM (Robolectric): запуск, отображение книги, оглавление,
 * подсветка по «услышанным» словам, диалоги, обработка ошибки микрофона. Настоящий Vosk здесь не работает
 * (это нативная библиотека под Android), поэтому речь подаётся напрямую в обработчики.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    private val book = File("src/main/assets/odyssey_zhukovsky.txt")

    @Before
    fun prepare() {
        val app = RuntimeEnvironment.getApplication()
        // «Модель уже распакована» — иначе тест распаковывал бы 90 МБ
        val dir = ModelInstaller.modelDir(app)
        dir.mkdirs()
        File(dir, ".installed").writeText("ok")
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun launch(): MainActivity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val reader = activity.findViewById<TextView>(R.id.readerText)
        var waited = 0
        while (reader.length() < 1000 && waited < 400) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            waited++
        }
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun idleUntil(timeoutMs: Int = 15_000, cond: () -> Boolean) {
        var waited = 0
        while (!cond() && waited < timeoutMs) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            waited += 50
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun highlightStart(reader: TextView): Int {
        val spans = (reader.text as Spanned).getSpans(0, reader.length(), BackgroundColorSpan::class.java)
        assertEquals("ровно одно слово подсвечено", 1, spans.size)
        return (reader.text as Spanned).getSpanStart(spans[0])
    }

    @Test
    fun launchShowsTheBookWithGrayLineNumbersAndStyledHeading() {
        val activity = launch()
        val reader = activity.findViewById<TextView>(R.id.readerText)
        assertTrue(reader.text.startsWith("ПЕСНЬ ПЕРВАЯ"))
        assertTrue(activity.findViewById<TextView>(R.id.titleText).text.isNotEmpty())
        assertEquals(activity.getString(R.string.status_ready), activity.findViewById<TextView>(R.id.statusText).text.toString())
        assertTrue(activity.findViewById<Button>(R.id.startButton).isEnabled)
        // у номеров строк и у заголовка есть цветовые спаны; подсветка стоит на первом слове
        val colors = (reader.text as Spanned).getSpans(0, reader.length(), ForegroundColorSpan::class.java)
        assertTrue("спанов цвета: ${colors.size}", colors.size > 50)
        assertTrue(highlightStart(reader) > 0)
    }

    @Test
    fun lightThemeAlsoLaunches() {
        val app = RuntimeEnvironment.getApplication()
        Settings(app).theme = Settings.THEME_LIGHT
        val activity = launch()
        assertTrue(activity.findViewById<TextView>(R.id.readerText).length() > 1000)
    }

    @Test
    fun tableOfContentsListsAllSongsAndJumpsToTheChosenOne() {
        val activity = launch()
        activity.findViewById<Button>(R.id.tocButton).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(25, dialog.listView.adapter.count) // 24 песни и примечания
        dialog.listView.performItemClick(null, 1, 1L)
        shadowOf(Looper.getMainLooper()).idle()
        val reader = activity.findViewById<TextView>(R.id.readerText)
        assertTrue("показана вторая песнь", reader.text.startsWith("ПЕСНЬ ВТОРАЯ"))
        assertTrue(highlightStart(reader) > 0)
    }

    @Test
    fun highlightFollowsRecognizedWords() {
        val activity = launch()
        val reader = activity.findViewById<TextView>(R.id.readerText)
        val doc = Doc.parse(TextLoader.load(book.readBytes()))
        val before = highlightStart(reader)

        // три слова — ещё не подтверждение
        activity.onPartial(doc.norm.take(3).joinToString(" "))
        assertEquals(before, highlightStart(reader))

        // шесть слов подряд двигают подсветку на следующее слово
        activity.onPartial(doc.norm.take(6).joinToString(" "))
        assertEquals(doc.wordStart[6], highlightStart(reader) + doc.sections[0].start)

        // мусор подсветку не двигает
        val at = highlightStart(reader)
        activity.onPartial("кхм ну да а")
        activity.onFinal("совсем другая речь про погоду и автобусы")
        assertEquals(at, highlightStart(reader))

        // прочитанное приглушено, статистика посчитала слова
        assertTrue(activity.findViewById<TextView>(R.id.statsText).text.contains("Прочитано"))
    }

    @Test
    fun longTextSectionsSwitchWhenReadingCrossesASongBorder() {
        val activity = launch()
        val reader = activity.findViewById<TextView>(R.id.readerText)
        val doc = Doc.parse(TextLoader.load(book.readBytes()))
        val second = doc.sections.filter { it.inToc }[1]
        // последние слова первой песни, затем первые слова второй
        val tail = (second.firstWord - 6 until second.firstWord + 6).map { doc.norm[it] }
        activity.findViewById<Button>(R.id.tocButton).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.listView.performItemClick(null, 0, 0L)
        // подводим позицию к концу первой песни «длинным прыжком» (8+ слов подряд) и читаем дальше
        activity.onPartial(tail.take(10).joinToString(" "))
        activity.onPartial(tail.joinToString(" "))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("показана вторая песнь", reader.text.startsWith("ПЕСНЬ ВТОРАЯ"))
    }

    @Test
    fun dialogsOpenWithoutCrashing() {
        val activity = launch()
        activity.findViewById<Button>(R.id.fileButton).performClick()
        val fileDialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue("меню файла показано", fileDialog.isShowing)
        assertEquals(2, fileDialog.listView.adapter.count)
        fileDialog.dismiss()

        activity.findViewById<Button>(R.id.settingsButton).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue("настройки показаны", dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("настройки закрыты", dialog.isShowing)
    }

    @Test
    fun fontButtonsChangeAndRememberTheSize() {
        val activity = launch()
        val reader = activity.findViewById<TextView>(R.id.readerText)
        val before = reader.textSize
        activity.findViewById<Button>(R.id.fontBigger).performClick()
        assertTrue(reader.textSize > before)
        assertEquals(24f, Settings(RuntimeEnvironment.getApplication()).fontSp, 0.01f)
    }

    @Test
    fun missingNativeVoskLibraryIsReportedNotCrashed() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val activity = launch()
        assertEquals(
            "разрешение микрофона выдано", android.content.pm.PackageManager.PERMISSION_GRANTED,
            activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO),
        )
        activity.findViewById<Button>(R.id.startButton).performClick()
        val status = activity.findViewById<TextView>(R.id.statusText)
        idleUntil { status.text.startsWith("Ошибка") }
        assertTrue("статус: ${status.text}", status.text.startsWith("Ошибка"))
        assertEquals(activity.getString(R.string.start), activity.findViewById<Button>(R.id.startButton).text.toString())
    }

    @Test
    fun positionIsRestoredBetweenLaunches() {
        val activity = launch()
        val doc = Doc.parse(TextLoader.load(book.readBytes()))
        // подводим к 200-му слову ближним прыжком и закрываем приложение
        activity.onPartial(doc.norm.slice(200 until 206).joinToString(" "))
        shadowOf(Looper.getMainLooper()).idle()
        val saved = highlightStart(activity.findViewById(R.id.readerText))
        activity.finish()
        val again = launch()
        assertEquals(saved, highlightStart(again.findViewById(R.id.readerText)))
    }
}
