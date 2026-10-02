package com.alicegpt.textfollower

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import com.alicegpt.textfollower.speech.EngineKind
import com.alicegpt.textfollower.speech.ModelStore
import com.alicegpt.textfollower.testutil.FakeEngine
import com.alicegpt.textfollower.testutil.FakeStore
import com.alicegpt.textfollower.text.Doc
import com.alicegpt.textfollower.text.TextLoader
import com.alicegpt.textfollower.text.TextSearch
import com.alicegpt.textfollower.ui.MicButton
import com.alicegpt.textfollower.ui.ReaderView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Duration

/**
 * Экран приложения целиком, на JVM (Robolectric): запуск, показ книги, оглавление, поиск, подсветка по «услышанным»
 * словам, поиск места после потери, переключение движка и ошибки. Настоящие движки распознавания здесь не работают
 * (это нативные библиотеки под Android), поэтому подставляется [FakeEngine], а речь подаётся прямо в него.
 * Текст книги берётся из файла — в коде тестов его нет.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    private val book = File("src/main/assets/odyssey_zhukovsky.txt")
    private val doc: Doc by lazy { Doc.parse(TextLoader.load(book.readBytes())) }
    private val originalFactory = MainActivity.engineFactory
    private val originalStoreFactory = MainActivity.storeFactory
    private val originalRetryDelay = MainActivity.modelRetryDelayMs
    private val engines = ArrayList<FakeEngine>()

    /** Какие движки «не запускаются»: вид → текст ошибки. */
    private val failing = HashMap<EngineKind, String>()

    private val app: android.app.Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun prepare() {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        // «Модели уже на месте» — иначе экран пытался бы их скачивать
        for (kind in EngineKind.values()) {
            val dir = ModelStore.of(kind).modelDir(app)
            dir.mkdirs()
            File(dir, ".installed").writeText("ok")
        }
        Settings(app).seenIntro = true
        MainActivity.engineFactory = { kind, listener -> FakeEngine(listener, kind, failing[kind]).also { engines += it } }
    }

    @After
    fun restore() {
        MainActivity.engineFactory = originalFactory
        MainActivity.storeFactory = originalStoreFactory
        MainActivity.modelRetryDelayMs = originalRetryDelay
    }

    // ---------- помощники ----------

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): MainActivity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
        waitForText(activity)
        return activity
    }

    private fun waitForText(activity: MainActivity) {
        val reader = activity.findViewById<ReaderView>(R.id.reader)
        var waited = 0
        while (reader.textView.length() < 1000 && waited < 400) {
            idle()
            Thread.sleep(50)
            waited++
        }
        idle()
    }

    private fun reader(a: Activity) = a.findViewById<ReaderView>(R.id.reader)
    private fun status(a: Activity) = a.findViewById<TextView>(R.id.statusText).text.toString()
    private fun mic(a: Activity) = a.findViewById<MicButton>(R.id.micButton)
    private fun banner(a: Activity) = a.findViewById<View>(R.id.banner)

    private fun grantMic() = shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

    /** Включает прослушивание кнопкой микрофона и возвращает движок. */
    private fun startListening(a: MainActivity): FakeEngine {
        grantMic()
        mic(a).performClick()
        idle()
        return engines.last()
    }

    /** Слово текущей позиции, как его видит экран: смещение подсветки в тексте секции. */
    private fun cursorWord(a: Activity): Int {
        val range = reader(a).cursor ?: return -1
        val sectionStart = currentSectionStart(a)
        return doc.wordAtOffset(sectionStart + range.first)
    }

    private fun currentSectionStart(a: Activity): Int {
        val first = reader(a).textView.text.toString().take(40)
        val section = doc.sections.first { doc.text.startsWith(first, it.start) }
        return section.start
    }

    private fun words(from: Int, count: Int) = (from until from + count).joinToString(" ") { doc.norm[it] }

    /** Первое слово песни с номером [song] (1 — первая). */
    private fun songStart(song: Int) = doc.sections.filter { it.inToc }[song - 1].firstWord

    /** Сколько раз каждые четыре слова подряд встречаются в книге (в «Одиссее» много повторяющихся формул). */
    private val fourGrams: Map<String, Int> by lazy {
        val m = HashMap<String, Int>()
        for (i in 0 until doc.size - 3) m.merge(doc.norm.slice(i until i + 4).joinToString(" "), 1, Int::plus)
        m
    }

    /** Первое место не раньше [from], где несколько слов подряд встречаются в книге только один раз. */
    private fun uniquePlace(from: Int): Int {
        var i = from
        while (fourGrams[doc.norm.slice(i until i + 4).joinToString(" ")] != 1) i++
        return i
    }

    private fun clickDialogItem(dialog: AlertDialog, index: Int) {
        dialog.listView.performItemClick(null, index, index.toLong())
        idle()
    }

    // ---------- запуск и вид ----------

    @Test
    fun launchShowsTheBookWithTitleProgressAndReadyState() {
        val a = launch()
        assertTrue(reader(a).textView.text.startsWith("ПЕСНЬ ПЕРВАЯ"))
        assertEquals(a.getString(R.string.default_title), a.findViewById<TextView>(R.id.titleText).text.toString())
        assertTrue(a.findViewById<TextView>(R.id.chapterText).text.startsWith("Песнь первая · 0%"))
        assertEquals(a.getString(R.string.status_ready), status(a))
        assertEquals(MicButton.State.IDLE, mic(a).state)
        assertEquals(0, a.findViewById<android.widget.ProgressBar>(R.id.bookProgress).progress)
        // подсветка стоит на первом слове
        assertEquals(0, cursorWord(a))
        assertEquals(a.getString(R.string.heard_placeholder), a.findViewById<TextView>(R.id.heardText).text.toString())
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w760dp-h360dp-land")
    fun landscapeLayoutWorksTheSameWay() {
        val a = launch()
        val engine = startListening(a)
        engine.partial(words(0, 9))
        assertEquals(9, cursorWord(a))
        assertEquals(a.getString(R.string.status_listening), status(a))
        assertTrue(a.findViewById<TextView>(R.id.statsText).text.startsWith("9 сл"))
        a.findViewById<View>(R.id.fontBigger).performClick()
        assertEquals(24f, Settings(app).fontSp, 0.01f)
    }

    @Test
    fun lineNumbersAndHeadingsDoNotMatterForTheCursor() {
        val a = launch()
        // «[5]» не слово: пятая строка — это не пятое слово подсветки, а номера в тексте есть
        assertTrue(doc.markerRanges.isNotEmpty())
        assertTrue(reader(a).textView.text.contains("[5]"))
        val first = reader(a).cursor!!
        val sectionStart = currentSectionStart(a)
        assertEquals(doc.wordStart[0], sectionStart + first.first)
    }

    @Test
    fun everyThemeLaunches() {
        for (theme in listOf(Settings.THEME_SYSTEM, Settings.THEME_LIGHT, Settings.THEME_SEPIA, Settings.THEME_DARK, Settings.THEME_BLACK)) {
            Settings(app).theme = theme
            val a = launch()
            assertTrue("тема $theme", reader(a).textView.length() > 1000)
            a.finish()
        }
    }

    @Test
    fun introIsShownOnceAndRemembered() {
        Settings(app).seenIntro = false
        val a = launch()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(Settings(app).seenIntro)
        assertFalse(dialog.isShowing)
        a.finish()
    }

    // ---------- первый запуск: подготовка модели ----------

    /** Подставляет хранилище нейросети, которой «ещё нет»; остальные хранилища — обычные. */
    private fun missingNeuralModel(
        bundled: Boolean = true,
        failures: List<Throwable> = emptyList(),
        gate: java.util.concurrent.CountDownLatch? = null,
    ): FakeStore {
        val store = FakeStore(EngineKind.NEURAL, File(app.filesDir, "fake-model"), bundled = bundled, failures = failures.toMutableList(), gate = gate)
        MainActivity.storeFactory = { kind -> if (kind == EngineKind.NEURAL) store else ModelStore.of(kind) }
        MainActivity.modelRetryDelayMs = 5
        return store
    }

    private fun waitFor(timeoutMs: Int = 10_000, cond: () -> Boolean) {
        var waited = 0
        while (!cond() && waited < timeoutMs) {
            idle()
            Thread.sleep(20)
            waited += 20
        }
        idle()
        assertTrue("условие не выполнилось за $timeoutMs мс", cond())
    }

    @Test
    fun firstRunShowsModelPreparationAndThenListens() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val store = missingNeuralModel(bundled = true, gate = gate)
        val a = launch()
        waitFor { status(a).startsWith("Подготовка модели") }
        assertTrue(status(a), status(a).contains("40%"))
        assertEquals(MicButton.State.BUSY, mic(a).state)
        // пока модель готовится, нажатие на микрофон ничего не запускает
        grantMic()
        mic(a).performClick()
        idle()
        assertTrue(engines.none { it.starts > 0 })

        gate.countDown()
        waitFor { status(a) == a.getString(R.string.status_ready) }
        assertEquals(MicButton.State.IDLE, mic(a).state)
        mic(a).performClick()
        idle()
        assertEquals(1, engines.last().starts)
        assertEquals(store.modelDir(app), engines.last().modelDir)
    }

    @Test
    fun aDownloadedModelSaysHowBigItIs() {
        val gate = java.util.concurrent.CountDownLatch(1)
        missingNeuralModel(bundled = false, gate = gate)
        val a = launch()
        waitFor { status(a).startsWith("Загрузка модели") }
        assertTrue(status(a), status(a).contains("28 МБ"))
        gate.countDown()
        waitFor { status(a) == a.getString(R.string.status_ready) }
    }

    @Test
    fun aShakyConnectionIsRetried() {
        val store = missingNeuralModel(bundled = false, failures = listOf(java.io.IOException("обрыв"), java.io.IOException("обрыв")))
        val a = launch()
        waitFor { status(a) == a.getString(R.string.status_ready) }
        assertEquals(3, store.installCalls)
    }

    @Test
    fun noInternetIsExplainedAndTappingTheMicrophoneTriesAgain() {
        val store = missingNeuralModel(
            bundled = false,
            failures = listOf(java.net.UnknownHostException("x"), java.net.UnknownHostException("x"), java.net.UnknownHostException("x")),
        )
        val a = launch()
        waitFor { status(a).startsWith("Ошибка") }
        assertEquals("Ошибка: " + a.getString(R.string.error_no_internet), status(a))
        assertEquals(MicButton.State.ERROR, mic(a).state)
        assertEquals(3, store.installCalls)

        // связь появилась — нажатие повторяет загрузку
        mic(a).performClick()
        waitFor { status(a) == a.getString(R.string.status_ready) }
        assertEquals(4, store.installCalls)
    }

    @Test
    fun anUnexpectedInstallFailureShowsItsMessage() {
        missingNeuralModel(bundled = true, failures = listOf(IllegalStateException("нет места на диске")))
        val a = launch()
        waitFor { status(a).startsWith("Ошибка") }
        assertEquals("Ошибка: нет места на диске", status(a))
    }

    // ---------- чтение ----------

    @Test
    fun listeningStartsWithAHintFromTheTextAroundThePosition() {
        val a = launch()
        val engine = startListening(a)
        assertEquals(1, engine.starts)
        assertEquals(EngineKind.NEURAL, engine.kind)
        assertEquals(a.getString(R.string.status_listening), status(a))
        assertEquals(MicButton.State.LISTENING, mic(a).state)
        val hint = engine.contexts.single()
        assertNotNull("подсказка слов из текста", hint)
        assertTrue(hint!!.size > 100)
        assertTrue(doc.norm[0] in hint && doc.norm[300] in hint)
        assertEquals(ModelStore.of(EngineKind.NEURAL).modelDir(app), engine.modelDir)
    }

    @Test
    fun hintCanBeSwitchedOffInSettings() {
        Settings(app).useContext = false
        val a = launch()
        val engine = startListening(a)
        assertNull(engine.contexts.single())
    }

    @Test
    fun highlightFollowsRecognizedWordsAndStatsCountThem() {
        val a = launch()
        val engine = startListening(a)
        // один «услышанный» слово ничего не двигает
        engine.partial(words(0, 1))
        assertEquals(0, cursorWord(a))
        // дальше подсветка идёт за каждым словом
        for (n in 2..12) {
            engine.partial(words(0, n))
            assertEquals("после $n слов", n, cursorWord(a))
        }
        assertTrue(a.findViewById<TextView>(R.id.statsText).text.startsWith("12 сл"))
        assertTrue(a.findViewById<TextView>(R.id.heardText).text.startsWith("слышу: "))
        // прочитанное приглушено: серый спан от начала до текущего слова
        val sp = reader(a).textView.text as Spanned
        val dimEnd = doc.wordStart[12] - currentSectionStart(a)
        assertTrue(sp.getSpans(0, sp.length, ForegroundColorSpan::class.java).any { sp.getSpanStart(it) == 0 && sp.getSpanEnd(it) == dimEnd })
        assertTrue(a.findViewById<android.widget.ProgressBar>(R.id.bookProgress).progress >= 0)
    }

    @Test
    fun finalResultAfterPartialsDoesNotMoveItAgain() {
        val a = launch()
        val engine = startListening(a)
        engine.partial(words(0, 8))
        engine.final(words(0, 8))
        assertEquals(8, cursorWord(a))
        engine.partial(words(8, 3))
        assertEquals(11, cursorWord(a))
    }

    @Test
    fun garbageAndForeignSpeechDoNotMoveTheHighlight() {
        val a = launch()
        val engine = startListening(a)
        engine.partial(words(0, 10))
        for (junk in listOf("кхм", "ну да", "и в на не", "один два три четыре пять", "[unk] [unk]")) {
            engine.partial(junk)
            engine.final(junk)
        }
        assertEquals(10, cursorWord(a))
    }

    @Test
    fun longJumpToAnotherSongSwitchesTheSection() {
        val a = launch()
        val engine = startListening(a)
        engine.partial(words(0, 10))
        val target = uniquePlace(songStart(4) + 5)
        for (n in 1..10) engine.partial(words(target, n))
        val song = doc.sections.filter { it.inToc }[3]
        assertTrue("показана четвёртая песнь: ${reader(a).textView.text.take(20)}", reader(a).textView.text.startsWith(doc.text.substring(song.start, song.start + 14)))
        assertTrue(a.findViewById<TextView>(R.id.chapterText).text.startsWith(song.title))
        assertEquals(target + 10, cursorWord(a))
        // перескок не засчитан как прочитанные слова: в статистике только 10 слов в начале и несколько после перескока
        val counted = a.findViewById<TextView>(R.id.statsText).text.toString().substringBefore(" сл").toInt()
        assertTrue("засчитано $counted слов", counted in 10..20)
    }

    @Test
    fun lostReaderGetsTheSearchBannerAfterAShortDelayAndFindsThePlace() {
        val a = launch()
        val engine = startListening(a)
        engine.partial(words(100, 10))
        engine.final(words(100, 10))
        assertEquals(110, cursorWord(a))
        assertEquals(View.GONE, banner(a).visibility)
        val hints = engine.contexts.size

        engine.final("кхм ну это самое да кхм ага да так")
        assertEquals(110, cursorWord(a))
        // быстрый успешный поиск не мельтешит на экране: «потерял место» появляется, только если поиск затянулся
        assertEquals(View.GONE, banner(a).visibility)
        assertEquals(a.getString(R.string.status_listening), status(a))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(View.VISIBLE, banner(a).visibility)
        assertEquals(a.getString(R.string.status_searching), status(a))
        assertEquals(MicButton.State.SEARCHING, mic(a).state)
        // нейросети подсказка словарь не ограничивает — она остаётся на месте
        assertEquals(hints, engine.contexts.size)

        // читатель нашёлся в другом месте — достаточно прочитать там несколько слов
        val target = uniquePlace(songStart(9) + 20)
        for (n in 1..6) engine.partial(words(target, n))
        assertEquals(target + 6, cursorWord(a))
        assertEquals(View.GONE, banner(a).visibility)
        assertEquals(a.getString(R.string.status_listening), status(a))
        // далеко от прежнего окна: подсказка пересобрана вокруг нового места
        val hint = engine.contexts.last()
        assertNotNull("подсказка вернулась", hint)
        assertTrue(doc.norm[target + 3] in hint!!)
    }

    @Test
    fun withVoskTheHintIsDroppedWhileSearchingAndComesBackAroundTheNewPlace() {
        Settings(app).engine = EngineKind.VOSK
        val a = launch()
        val engine = startListening(a)
        assertEquals(EngineKind.VOSK, engine.kind)
        engine.partial(words(100, 10))
        engine.final(words(100, 10))
        engine.final("кхм ну это самое да кхм ага да так")
        // у Vosk подсказка — ограничение словаря, в поиске она мешала бы услышать новое место
        assertNull(engine.contexts.last())

        val target = uniquePlace(songStart(9) + 20)
        for (n in 1..6) engine.partial(words(target, n))
        assertEquals(target + 6, cursorWord(a))
        val hint = engine.contexts.last()
        assertNotNull("подсказка вернулась", hint)
        assertTrue(doc.norm[target + 3] in hint!!)
    }

    @Test
    fun shortDeadSpotDoesNotFlashTheBanner() {
        val a = launch()
        val engine = startListening(a)
        engine.partial(words(100, 10))
        engine.final(words(100, 10))
        engine.final("кхм ну это самое да кхм")
        // поиск мог включиться, но читатель тут же продолжил с того же места
        engine.partial(words(110, 4))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(114, cursorWord(a))
        assertEquals(View.GONE, banner(a).visibility)
        assertEquals(a.getString(R.string.status_listening), status(a))
    }

    @Test
    fun rotationRebuildsTheLayoutButKeepsListeningAndPosition() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val a = controller.get()
        waitForText(a)
        val engine = startListening(a)
        engine.partial(words(0, 9))
        assertEquals("port", a.findViewById<View>(R.id.rootLayout).tag)

        RuntimeEnvironment.setQualifiers("+w760dp-h360dp-land")
        controller.configurationChange()
        idle()
        assertEquals("land", a.findViewById<View>(R.id.rootLayout).tag)
        assertTrue("микрофон не остановился", engine.isRunning)
        assertEquals(1, engine.starts)
        assertEquals(9, cursorWord(a))
        assertEquals(a.getString(R.string.status_listening), status(a))
        assertEquals(a.getString(R.string.default_title), a.findViewById<TextView>(R.id.titleText).text.toString())
        assertTrue(a.findViewById<TextView>(R.id.statsText).text.startsWith("9 сл"))
        assertTrue(a.findViewById<TextView>(R.id.heardText).text.startsWith("слышу: "))
        // кнопки новой разметки работают
        a.findViewById<View>(R.id.fontBigger).performClick()
        assertEquals(24f, Settings(app).fontSp, 0.01f)
        engine.partial(words(0, 12))
        assertEquals(12, cursorWord(a))

        RuntimeEnvironment.setQualifiers("+port")
        controller.configurationChange()
        idle()
        assertEquals("port", a.findViewById<View>(R.id.rootLayout).tag)
        assertEquals(12, cursorWord(a))
    }

    @Test
    fun hintFollowsTheReaderOnlyAfterALongWay() {
        val a = launch()
        val engine = startListening(a)
        val before = engine.contexts.size
        // чтение вперёд на пару десятков слов подсказку не меняет
        var pos = 0
        while (pos < 40) {
            engine.partial(words(pos, 10))
            pos += 10
        }
        assertEquals(before, engine.contexts.size)
        // ушли далеко от центра окна — подсказка пересобирается вокруг нового места
        for (n in 1..10) engine.partial(words(1500, n))
        assertTrue(engine.contexts.size > before)
        assertTrue(doc.norm[1505] in engine.contexts.last()!!)
    }

    @Test
    fun longPressStartsReadingFromTheWordAndSavesIt() {
        val a = launch()
        val engine = startListening(a)
        val word = 60
        val offset = doc.wordStart[word] - currentSectionStart(a) + 1
        reader(a).onLongPress!!.invoke(offset)
        idle()
        assertEquals(word, cursorWord(a))
        assertEquals(word, Settings(app).position(Settings.DOC_ASSET))
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Читаем отсюда"))
        // подсказка пересобрана вокруг нового места
        assertTrue(doc.norm[word + 200] in engine.contexts.last()!!)
        // после этого чтение продолжается отсюда
        engine.partial(words(word, 5))
        assertEquals(word + 5, cursorWord(a))
    }

    // ---------- ошибки и жизненный цикл ----------

    @Test
    fun withoutMicrophonePermissionThePermissionIsRequestedThenExplained() {
        val a = launch()
        mic(a).performClick()
        idle()
        val request = shadowOf(a).lastRequestedPermission
        assertNotNull(request)
        assertEquals(Manifest.permission.RECORD_AUDIO, request.requestedPermissions.single())
        assertTrue(engines.last().starts == 0)

        a.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        idle()
        assertEquals(MicButton.State.ERROR, mic(a).state)
        assertTrue(status(a), status(a).contains("микрофон"))
    }

    @Test
    fun grantingThePermissionStartsListening() {
        val a = launch()
        mic(a).performClick()
        idle()
        val request = shadowOf(a).lastRequestedPermission
        grantMic()
        a.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED))
        idle()
        assertEquals(1, engines.last().starts)
        assertEquals(MicButton.State.LISTENING, mic(a).state)
    }

    @Test
    fun microphoneErrorIsShownAndTheNextTapRestarts() {
        val a = launch()
        val engine = startListening(a)
        engine.error("Не удалось открыть микрофон")
        idle()
        assertEquals(MicButton.State.ERROR, mic(a).state)
        assertEquals("Ошибка: Не удалось открыть микрофон", status(a))
        mic(a).performClick()
        idle()
        assertEquals(2, engine.starts)
        assertEquals(MicButton.State.LISTENING, mic(a).state)
    }

    @Test
    fun pauseButtonStopsListening() {
        val a = launch()
        val engine = startListening(a)
        engine.partial(words(0, 6))
        mic(a).performClick()
        idle()
        assertFalse(engine.isRunning)
        assertEquals(a.getString(R.string.status_paused), status(a))
        assertEquals(MicButton.State.IDLE, mic(a).state)
    }

    @Test
    fun leavingTheAppStopsTheMicrophoneAndComingBackResumes() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val a = controller.get()
        waitForText(a)
        val engine = startListening(a)
        engine.partial(words(0, 6))
        controller.pause()
        idle()
        assertFalse("в фоне микрофон выключен", engine.isRunning)
        assertEquals(6, Settings(app).position(Settings.DOC_ASSET))
        controller.resume()
        idle()
        assertTrue("после возвращения слушаем снова", engine.isRunning)
        assertEquals(2, engine.starts)
    }

    @Test
    fun brokenNeuralEngineFallsBackToVosk() {
        failing[EngineKind.NEURAL] = "нет библиотеки"
        val a = launch()
        grantMic()
        mic(a).performClick()
        idle()
        assertEquals(2, engines.size)
        assertEquals(EngineKind.VOSK, engines[1].kind)
        assertEquals(EngineKind.VOSK, Settings(app).engine)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("нет библиотеки"))
        // слушание продолжилось уже на запасном движке
        assertEquals(1, engines[1].starts)
        assertEquals(MicButton.State.LISTENING, mic(a).state)
        assertTrue(engines[0].released)
    }

    @Test
    fun brokenVoskEngineIsReportedAsAnError() {
        Settings(app).engine = EngineKind.VOSK
        failing[EngineKind.VOSK] = "модель повреждена"
        val a = launch()
        grantMic()
        mic(a).performClick()
        idle()
        assertEquals(1, engines.size)
        assertEquals("Ошибка: модель повреждена", status(a))
        assertEquals(MicButton.State.ERROR, mic(a).state)
    }

    // ---------- диалоги ----------

    @Test
    fun tableOfContentsListsAllSongsAndJumpsToTheChosenOne() {
        val a = launch()
        a.findViewById<View>(R.id.tocButton).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(25, dialog.listView.adapter.count) // 24 песни и примечания
        clickDialogItem(dialog, 1)
        assertTrue(reader(a).textView.text.startsWith("ПЕСНЬ ВТОРАЯ"))
        assertEquals(songStart(2), cursorWord(a))
        assertEquals(songStart(2), Settings(app).position(Settings.DOC_ASSET))
        assertTrue(a.findViewById<TextView>(R.id.chapterText).text.startsWith("Песнь вторая"))
    }

    @Test
    fun searchFindsAPhraseAndMovesTheHighlightThere() {
        val a = launch()
        val phrase = words(5000, 3)
        val expected = TextSearch.find(doc, phrase).first().word
        a.findViewById<View>(R.id.searchButton).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val input = findView<EditText>(dialog.window!!.decorView) { true }!!
        input.setText(phrase)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        val list = findView<ListView>(dialog.window!!.decorView) { true }!!
        assertTrue(list.adapter.count >= 1)
        list.performItemClick(null, 0, 0L)
        idle()
        assertEquals(expected, cursorWord(a))
        assertFalse(dialog.isShowing)
    }

    @Test
    fun searchSaysWhenNothingIsFound() {
        val a = launch()
        a.findViewById<View>(R.id.searchButton).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val input = findView<EditText>(dialog.window!!.decorView) { true }!!
        input.setText("ъъъъъъъ")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(0, findView<ListView>(dialog.window!!.decorView) { true }!!.adapter.count)
        assertNotNull(findView<TextView>(dialog.window!!.decorView) { it.text.toString() == a.getString(R.string.search_nothing) })
    }

    @Test
    fun settingsChangeTheEngineAndRestartTheMicrophone() {
        val a = launch()
        val first = startListening(a)
        a.findViewById<View>(R.id.settingsButton).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val vosk = findView<RadioButton>(dialog.window!!.decorView) { it.id == 2000 + EngineKind.VOSK.id }!!
        vosk.performClick()
        idle()
        assertTrue(first.released)
        assertEquals(EngineKind.VOSK, Settings(app).engine)
        val second = engines.last()
        assertEquals(EngineKind.VOSK, second.kind)
        // слушание продолжилось на новом движке с подсказкой из текста
        assertEquals(1, second.starts)
        assertNotNull(second.contexts.single())
        // трекер получил настройки для Vosk, но положение сохранилось
        assertEquals(0, cursorWord(a))
    }

    @Test
    fun settingsSwitchOffTheHintWhileListening() {
        val a = launch()
        val engine = startListening(a)
        a.findViewById<View>(R.id.settingsButton).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val sw = findView<android.widget.Switch>(dialog.window!!.decorView) { it.text == a.getString(R.string.settings_context) }!!
        sw.performClick()
        idle()
        assertFalse(Settings(app).useContext)
        assertNull(engine.contexts.last())
        sw.performClick()
        idle()
        assertNotNull(engine.contexts.last())
    }

    @Test
    fun settingsButtonsClosesAndResetsStats() {
        val a = launch()
        val engine = startListening(a)
        engine.partial(words(0, 9))
        assertTrue(a.findViewById<TextView>(R.id.statsText).text.startsWith("9 сл"))
        a.findViewById<View>(R.id.settingsButton).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val reset = findView<Button>(dialog.window!!.decorView) { it.text == a.getString(R.string.settings_reset_stats) }!!
        reset.performClick()
        idle()
        assertTrue(a.findViewById<TextView>(R.id.statsText).text.startsWith("0 сл"))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun fontButtonsChangeAndRememberTheSize() {
        val a = launch()
        val before = reader(a).textView.textSize
        a.findViewById<View>(R.id.fontBigger).performClick()
        assertTrue(reader(a).textView.textSize > before)
        assertEquals(24f, Settings(app).fontSp, 0.01f)
        a.findViewById<View>(R.id.fontSmaller).performClick()
        a.findViewById<View>(R.id.fontSmaller).performClick()
        assertEquals(20f, Settings(app).fontSp, 0.01f)
        // слово по-прежнему подсвечено
        assertEquals(0, cursorWord(a))
    }

    // ---------- свой текст ----------

    @Test
    fun openingAWindows1251FileShowsItAndRestoringBringsTheBookBack() {
        val a = launch()
        val text = "Первая глава\nСиняя лодка плывёт по тихой реке, старый мастер чинит деревянный мост. " +
            "Холодный ветер приносит запах мокрой травы, маленькая птица прячется под широким листом."
        val bytes = text.toByteArray(charset("windows-1251"))
        val uri = Uri.parse("content://test/docs/story.txt")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))

        a.findViewById<View>(R.id.fileButton).performClick()
        idle()
        val menu = ShadowDialog.getLatestDialog() as AlertDialog
        clickDialogItem(menu, 0)
        val started = shadowOf(a).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
        shadowOf(a).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(uri))
        var waited = 0
        while (!reader(a).textView.text.startsWith("Первая глава") && waited < 200) {
            idle()
            Thread.sleep(50)
            waited++
        }
        idle()
        assertTrue(reader(a).textView.text.startsWith("Первая глава"))
        assertEquals("story.txt", a.findViewById<TextView>(R.id.titleText).text.toString())
        assertTrue(Settings(app).currentDoc.startsWith("file:"))

        // «Вернуть Одиссею»
        a.findViewById<View>(R.id.fileButton).performClick()
        idle()
        clickDialogItem(ShadowDialog.getLatestDialog() as AlertDialog, 1)
        waitForText(a)
        assertEquals(Settings.DOC_ASSET, Settings(app).currentDoc)
        assertTrue(reader(a).textView.text.startsWith("ПЕСНЬ ПЕРВАЯ"))
    }

    @Test
    fun anEmptyFileIsRejected() {
        val a = launch()
        val uri = Uri.parse("content://test/docs/empty.txt")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream("   \n\n  ".toByteArray()))
        a.findViewById<View>(R.id.fileButton).performClick()
        idle()
        clickDialogItem(ShadowDialog.getLatestDialog() as AlertDialog, 0)
        val started = shadowOf(a).nextStartedActivityForResult
        shadowOf(a).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(uri))
        var waited = 0
        while (ShadowToast.getTextOfLatestToast() == null && waited < 200) {
            idle()
            Thread.sleep(50)
            waited++
        }
        idle()
        assertEquals(a.getString(R.string.file_empty), ShadowToast.getTextOfLatestToast())
        assertTrue(reader(a).textView.text.startsWith("ПЕСНЬ ПЕРВАЯ"))
    }

    @Test
    fun positionIsRestoredBetweenLaunches() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val a = controller.get()
        waitForText(a)
        val engine = startListening(a)
        // место ставится долгим нажатием, дальше подсветка идёт за словами
        reader(a).onLongPress!!.invoke(doc.wordStart[200] - currentSectionStart(a))
        engine.partial(words(200, 8))
        idle()
        assertEquals(208, cursorWord(a))
        // уход из приложения сохраняет позицию (в том числе если с последнего сохранения не прошло двух секунд)
        controller.pause().stop().destroy()
        assertTrue(engine.released)
        val again = launch()
        assertEquals(208, cursorWord(again))
        assertTrue(again.findViewById<TextView>(R.id.chapterText).text.startsWith("Песнь первая"))
    }

    // ---------- вспомогательное: поиск view по дереву ----------

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            out += v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private inline fun <reified T : View> findView(root: View, predicate: (T) -> Boolean): T? =
        allViews(root).filterIsInstance<T>().firstOrNull(predicate)
}
