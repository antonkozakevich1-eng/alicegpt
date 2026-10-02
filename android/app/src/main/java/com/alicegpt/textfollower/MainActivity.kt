package com.alicegpt.textfollower

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.alicegpt.textfollower.speech.ContextWords
import com.alicegpt.textfollower.speech.EngineKind
import com.alicegpt.textfollower.speech.ModelStore
import com.alicegpt.textfollower.speech.NeuralEngine
import com.alicegpt.textfollower.speech.SpeechEngine
import com.alicegpt.textfollower.speech.VoskEngine
import com.alicegpt.textfollower.text.Doc
import com.alicegpt.textfollower.text.TextLoader
import com.alicegpt.textfollower.tracking.TextTracker
import com.alicegpt.textfollower.tracking.TrackerConfig
import com.alicegpt.textfollower.ui.Dialogs
import com.alicegpt.textfollower.ui.MicButton
import com.alicegpt.textfollower.ui.ReaderView
import com.alicegpt.textfollower.ui.SectionText
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max

class MainActivity : Activity(), SpeechEngine.Listener {

    private lateinit var settings: Settings
    private val stats = SessionStats()
    private val ui = Handler(Looper.getMainLooper())

    // views
    private lateinit var titleText: TextView
    private lateinit var chapterText: TextView
    private lateinit var reader: ReaderView
    private lateinit var banner: TextView
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var heardText: TextView
    private lateinit var bookProgress: ProgressBar
    private lateinit var mic: MicButton

    // документ и движок
    private var doc: Doc? = null
    private var tracker: TextTracker? = null
    private var docKey = Settings.DOC_ASSET
    private var currentSection = -1
    private lateinit var engine: SpeechEngine
    private var engineKind = EngineKind.NEURAL

    // состояние
    private var modelReady = false
    private var modelProgress = -1f           // -1 — не загружается
    private var modelDownloading = false
    private var wantListening = false         // пользователь нажал «слушать» и не нажимал «пауза»
    private var resumeAfterPause = false
    private var starting = false
    private var lastError: String? = null     // показывается, пока не удастся запустить прослушивание снова
    private var lastSaveMs = 0L
    private var docTitle = ""
    private var lastHeard: String? = null
    private var searchSince = 0L              // когда включился поиск места (uptime), 0 — не идёт

    // подсказка распознавателю словами вокруг позиции
    private var contextCenter = -1
    private var contextActive = false

    private val statsTick = object : Runnable {
        override fun run() {
            updateStats()
            if (searchSince != 0L) refreshStatus() // плашка «потерял место» появляется с задержкой
            ui.postDelayed(this, 1000)
        }
    }

    // ---------- жизненный цикл ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = Settings(this)
        setTheme(themeRes())
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()

        engineKind = settings.engine
        engine = createEngine(engineKind)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateStats()
        refreshStatus()

        prepareModel()
        loadDoc(settings.currentDoc)
        if (!settings.seenIntro) Dialogs.showIntro(this) { settings.seenIntro = true }
    }

    /** Находит элементы экрана, настраивает их и вешает обработчики: при создании и при повороте экрана. */
    private fun bindViews() {
        titleText = findViewById(R.id.titleText)
        chapterText = findViewById(R.id.chapterText)
        reader = findViewById(R.id.reader)
        banner = findViewById(R.id.banner)
        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        heardText = findViewById(R.id.heardText)
        bookProgress = findViewById(R.id.bookProgress)
        mic = findViewById(R.id.micButton)

        reader.setColors(readerColors())
        reader.setTypography(settings.fontSp, settings.serif, settings.lineSpacing)
        reader.autoScroll = settings.autoScroll
        mic.setColors(
            attrColor(R.attr.accentColor), attrColor(R.attr.goodColor), attrColor(R.attr.warnColor),
            attrColor(R.attr.badColor), attrColor(R.attr.onAccent), attrColor(R.attr.strokeColor),
        )

        findViewById<ImageButton>(R.id.tocButton).setOnClickListener { showToc() }
        findViewById<ImageButton>(R.id.searchButton).setOnClickListener { showSearch() }
        findViewById<ImageButton>(R.id.fileButton).setOnClickListener { showFileMenu() }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.fontSmaller).setOnClickListener { changeFont(-2f) }
        findViewById<Button>(R.id.fontBigger).setOnClickListener { changeFont(+2f) }
        mic.setOnClickListener { toggleListening() }
        statsText.setOnLongClickListener {
            stats.reset()
            updateStats()
            true
        }
        reader.onLongPress = { offset -> seekToOffset(offset) }
    }

    /**
     * Поворот экрана: разметки вертикального и горизонтального положения разные, поэтому экран собирается заново,
     * а прослушивание, текст и позиция остаются как были.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setContentView(R.layout.activity_main)
        bindViews()
        titleText.text = docTitle
        lastHeard?.let { heardText.text = getString(R.string.heard_format, heardTail(it)) }
        currentSection = -1
        renderCursor(animate = false, forceScroll = true)
        updateStats()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(statsTick)
        ui.post(statsTick)
        if (resumeAfterPause) {
            resumeAfterPause = false
            startListening()
        }
    }

    override fun onPause() {
        super.onPause()
        savePosition(force = true)
        ui.removeCallbacks(statsTick)
        // Микрофон в фоне не работает, а держать его включённым незачем.
        if (engine.isRunning) {
            resumeAfterPause = wantListening
            engine.stop()
            stats.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        savePosition(force = true)
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---------- тема ----------

    private fun themeRes(): Int = when (settings.theme) {
        Settings.THEME_LIGHT -> R.style.Theme_App_Light
        Settings.THEME_SEPIA -> R.style.Theme_App_Sepia
        Settings.THEME_DARK -> R.style.Theme_App_Dark
        Settings.THEME_BLACK -> R.style.Theme_App_Black
        else -> if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
            R.style.Theme_App_Dark
        } else {
            R.style.Theme_App_Light
        }
    }

    private fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun readerColors() = ReaderView.Colors(
        text = attrColor(R.attr.textMain), dim = attrColor(R.attr.textDim), marker = attrColor(R.attr.markerColor),
        heading = attrColor(R.attr.headingColor), pill = attrColor(R.attr.hlBg), pillText = attrColor(R.attr.hlText),
    )

    // ---------- движок и модель распознавания ----------

    private fun createEngine(kind: EngineKind): SpeechEngine {
        val e = engineFactory(kind, this)
        e.gain.sensitivity = settings.sensitivity
        return e
    }

    private fun trackerConfig(kind: EngineKind) = if (kind == EngineKind.NEURAL) TrackerConfig.NEURAL else TrackerConfig.VOSK

    private fun newTracker(d: Doc, at: Int): TextTracker {
        val tr = TextTracker(d, trackerConfig(engineKind))
        tr.seek(at)
        Thread { tr.warmUp() }.start()
        return tr
    }

    private fun prepareModel() {
        val kind = engineKind
        val store = ModelStore.of(kind)
        if (store.isInstalled(this)) {
            modelReady = true
            modelProgress = -1f
            refreshStatus()
            return
        }
        modelReady = false
        modelProgress = 0f
        modelDownloading = !store.isBundled(this)
        lastError = null
        refreshStatus()
        Thread {
            try {
                store.install(this) { p ->
                    ui.post {
                        if (kind == engineKind) {
                            modelProgress = p
                            refreshStatus()
                        }
                    }
                }
                ui.post {
                    if (kind != engineKind) return@post
                    modelReady = true
                    modelProgress = -1f
                    refreshStatus()
                    if (wantListening) startListening()
                }
            } catch (e: Throwable) {
                ui.post {
                    if (kind != engineKind) return@post
                    modelProgress = -1f
                    lastError = e.message ?: e.javaClass.simpleName
                    refreshStatus()
                }
            }
        }.start()
    }

    private fun changeEngine(kind: EngineKind) {
        if (kind == engineKind) return
        val wasListening = wantListening
        engine.release()
        stats.pause()
        settings.engine = kind
        engineKind = kind
        engine = createEngine(kind)
        contextActive = false
        starting = false
        lastError = null
        wantListening = wasListening
        doc?.let { d -> tracker = newTracker(d, tracker?.position ?: 0) }
        prepareModel()
        if (modelReady && wantListening) startListening()
        refreshStatus()
    }

    // ---------- загрузка текста ----------

    private fun loadDoc(key: String) {
        statusText.setText(R.string.status_loading_text)
        Thread {
            try {
                val text = if (key == Settings.DOC_ASSET) {
                    assets.open(ASSET_TEXT).use { TextLoader.load(it.readBytes()) }
                } else {
                    File(filesDir, CUSTOM_FILE).readText()
                }
                val d = Doc.parse(text)
                ui.post { onDocLoaded(key, d) }
            } catch (e: Throwable) {
                ui.post {
                    if (key != Settings.DOC_ASSET) {
                        settings.currentDoc = Settings.DOC_ASSET
                        loadDoc(Settings.DOC_ASSET)
                    } else {
                        lastError = e.message ?: e.javaClass.simpleName
                        refreshStatus()
                    }
                }
            }
        }.start()
    }

    private fun onDocLoaded(key: String, d: Doc) {
        val wasListening = engine.isRunning
        if (wasListening) engine.stop()
        docKey = key
        settings.currentDoc = key
        doc = d
        tracker = newTracker(d, settings.position(key).coerceIn(0, d.size))
        currentSection = -1
        contextActive = false
        docTitle = if (key == Settings.DOC_ASSET) getString(R.string.default_title) else settings.customName
        titleText.text = docTitle
        renderCursor(animate = false, forceScroll = true)
        refreshStatus()
        if (wasListening) startListening()
    }

    // ---------- отображение ----------

    private fun showSection(index: Int) {
        val d = doc ?: return
        currentSection = index
        reader.setSection(SectionText.build(d, index, reader.gutter, attrColor(R.attr.headingColor)))
    }

    /** Ставит подсветку по текущей позиции трекера. */
    private fun renderCursor(animate: Boolean, forceScroll: Boolean = false) {
        val d = doc ?: return
        val t = tracker ?: return
        if (d.size == 0) return
        val pos = t.position
        val sectionIndex = d.sectionOfWord(if (pos >= d.size) d.size - 1 else pos)
        val switched = sectionIndex != currentSection
        if (switched) showSection(sectionIndex)
        val sec = d.sections[sectionIndex]
        if (pos < d.size) {
            val start = d.wordStart[pos] - sec.start
            val end = d.wordEnd[pos] - sec.start
            reader.setCursor(start, end, animate = animate && !switched, scroll = false)
            reader.scrollToCursor(force = forceScroll || switched)
        } else {
            reader.markAllRead()
        }
        bookProgress.progress = (pos * 1000L / d.size).toInt()
        chapterText.text = getString(R.string.chapter_progress, sec.title, (pos * 100L / d.size).toInt())
        reader.setLocked(t.locked)
    }

    private fun changeFont(delta: Float) {
        settings.fontSp = settings.fontSp + delta
        applyTypography()
    }

    private fun applyTypography() {
        reader.setTypography(settings.fontSp, settings.serif, settings.lineSpacing)
        ui.post { reader.scrollToCursor(force = true) }
    }

    // ---------- долгое нажатие и поиск: «читаем отсюда» ----------

    private fun seekToOffset(offset: Int) {
        val d = doc ?: return
        val sec = d.sections.getOrNull(currentSection) ?: return
        if (d.size == 0) return
        val word = d.wordAtOffset(sec.start + offset).coerceIn(0, d.size - 1)
        reader.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        seekTo(word)
        val sample = d.text.substring(d.wordStart[word], d.wordEnd[word])
        Toast.makeText(this, getString(R.string.seek_here, sample), Toast.LENGTH_SHORT).show()
    }

    private fun seekTo(word: Int) {
        val t = tracker ?: return
        t.seek(word) // заодно выключает поиск места
        searchSince = 0L
        renderCursor(animate = false, forceScroll = true)
        savePosition(force = true)
        applyContext(force = true)
        refreshStatus()
    }

    // ---------- оглавление, поиск, файлы, настройки ----------

    private fun showToc() {
        val d = doc ?: return
        val toc = d.sections.filter { it.inToc }
        if (toc.isEmpty()) return
        val pos = tracker?.position ?: 0
        val current = toc.indexOfLast { it.firstWord <= pos }.coerceAtLeast(0)
        val titles = toc.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.toc_title)
            .setSingleChoiceItems(titles, current) { dialog, which ->
                dialog.dismiss()
                seekTo(toc[which].firstWord.coerceAtMost(max(0, d.size - 1)))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showSearch() {
        val d = doc ?: return
        Dialogs.showSearch(this, d) { word -> seekTo(word) }
    }

    private fun showFileMenu() {
        val items = arrayOf(getString(R.string.open_file), getString(R.string.restore_default))
        AlertDialog.Builder(this)
            .setTitle(R.string.file_title)
            .setItems(items) { _, which ->
                if (which == 0) {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQ_OPEN_FILE)
                } else if (docKey != Settings.DOC_ASSET) {
                    savePosition(force = true)
                    loadDoc(Settings.DOC_ASSET)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OPEN_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        openFile(uri)
    }

    private fun openFile(uri: Uri) {
        statusText.setText(R.string.status_loading_text)
        Thread {
            try {
                val name = displayName(uri)
                val bytes = readLimited(uri)
                val text = TextLoader.load(bytes)
                if (Doc.parse(text).size == 0) {
                    ui.post {
                        Toast.makeText(this, R.string.file_empty, Toast.LENGTH_LONG).show()
                        refreshStatus()
                    }
                    return@Thread
                }
                File(filesDir, CUSTOM_FILE).writeText(text)
                val key = "file:" + text.length + ":" + text.hashCode()
                ui.post {
                    savePosition(force = true)
                    settings.customName = name
                    settings.currentDoc = key
                    loadDoc(key)
                    Toast.makeText(this, getString(R.string.file_opened, name), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                ui.post {
                    Toast.makeText(this, getString(R.string.file_error, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                    refreshStatus()
                }
            }
        }.start()
    }

    private fun readLimited(uri: Uri): ByteArray {
        val out = ByteArrayOutputStream()
        contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_FILE_BYTES) throw IllegalStateException("Файл слишком большой")
            }
        } ?: throw IllegalStateException("Файл недоступен")
        return out.toByteArray()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "файл"
        }
        return uri.lastPathSegment ?: "файл"
    }

    private fun showSettings() {
        Dialogs.showSettings(this, settings, object : Dialogs.SettingsCallbacks {
            override fun onEngineChanged(kind: EngineKind) = changeEngine(kind)

            override fun onThemeChanged() {
                savePosition(force = true)
                recreate()
            }

            override fun onTypographyChanged() = applyTypography()

            override fun onSensitivityChanged(value: Int) {
                engine.gain.sensitivity = value
            }

            override fun onContextChanged(enabled: Boolean) {
                if (enabled) applyContext(force = true) else if (engine.isRunning) {
                    engine.setContext(null)
                    contextActive = false
                }
            }

            override fun onAutoScrollChanged(enabled: Boolean) {
                reader.autoScroll = enabled
            }

            override fun onResetStats() {
                stats.reset()
                updateStats()
            }
        })
    }

    // ---------- прослушивание ----------

    private fun toggleListening() {
        if (!modelReady) {
            if (modelProgress < 0f) prepareModel() // загрузка модели не удалась — пробуем снова
            return
        }
        if (wantListening) {
            wantListening = false
            engine.stop()
            stats.pause()
            tracker?.stopSearching()
            searchSince = 0L
            refreshStatus()
        } else {
            wantListening = true
            startListening()
        }
    }

    private fun startListening() {
        if (!modelReady || tracker == null || engine.isRunning || starting) {
            refreshStatus()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        starting = true
        lastError = null
        tracker?.stopSearching()
        searchSince = 0L
        val d = doc
        val pos = tracker?.position ?: 0
        val context = if (settings.useContext && d != null) ContextWords.window(d, pos, engine.contextBack, engine.contextAhead) else null
        contextCenter = pos
        contextActive = context != null
        engine.start(ModelStore.of(engineKind).modelDir(this), context)
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (wantListening) startListening()
        } else {
            wantListening = false
            lastError = getString(R.string.permission_needed)
            refreshStatus()
        }
    }

    // ---------- события распознавателя ----------

    override fun onPartial(text: String) = handleSpeech(text, isFinal = false)

    override fun onFinal(text: String) = handleSpeech(text, isFinal = true)

    private fun handleSpeech(text: String, isFinal: Boolean) {
        val t = tracker ?: return
        lastHeard = text
        heardText.text = getString(R.string.heard_format, heardTail(text))
        val wasSearching = t.searchMode
        val move = if (isFinal) t.onFinal(text) else t.onPartial(text)
        if (move != null) {
            if (move.delta > 0 && !move.far) stats.onForwardMove(move.delta)
            renderCursor(animate = !move.far)
            updateStats()
            savePosition(force = false)
        }
        when {
            t.searchMode != wasSearching -> onSearchModeChanged(t.searchMode)
            move != null -> applyContext(force = false)
        }
        reader.setLocked(t.locked)
        refreshStatus()
    }

    /**
     * Читатель ушёл (поиск включился) или место найдено (выключился). У Vosk подсказка ограничивает словарь, поэтому на
     * время поиска она снимается — новое место может быть где угодно, — и возвращается уже вокруг нового места. Нейросеть
     * подсказкой словарь не ограничивает, ей подсказка не мешает и не меняется, пока место не сменилось надолго.
     */
    private fun onSearchModeChanged(searching: Boolean) {
        searchSince = if (searching) SystemClock.uptimeMillis() else 0L
        val restricts = engineKind == EngineKind.VOSK
        if (searching) {
            if (restricts && settings.useContext && engine.isRunning) {
                engine.setContext(null)
                contextActive = false
            }
        } else {
            applyContext(force = restricts)
        }
    }

    /** Хвост услышанного для показа: по границе слова, чтобы первое слово не обрезалось посередине. */
    private fun heardTail(text: String): String =
        if (text.length <= HEARD_CHARS) text else "…" + text.takeLast(HEARD_CHARS).substringAfter(' ')

    /** Окно подсказки следует за позицией чтения. */
    private fun applyContext(force: Boolean) {
        val d = doc ?: return
        val t = tracker ?: return
        if (!engine.isRunning) return
        if (!settings.useContext || t.searchMode) {
            if (contextActive) {
                engine.setContext(null)
                contextActive = false
            }
            return
        }
        val limit = (engine.contextAhead * 0.4).toInt()
        if (!force && contextActive && abs(t.position - contextCenter) <= limit) return
        contextCenter = t.position
        contextActive = true
        engine.setContext(ContextWords.window(d, t.position, engine.contextBack, engine.contextAhead))
    }

    override fun onLevel(level: Float) {
        mic.setLevel(level)
    }

    override fun onListeningChanged(listening: Boolean) {
        starting = false
        if (listening) {
            stats.start()
        } else {
            stats.pause()
            mic.setLevel(0f)
        }
        refreshStatus()
    }

    override fun onError(message: String) {
        starting = false
        wantListening = false
        lastError = message
        stats.pause()
        refreshStatus()
    }

    override fun onEngineFailure(message: String) {
        starting = false
        stats.pause()
        if (engineKind == EngineKind.NEURAL) {
            // Нейросеть не поднялась (нет нужной библиотеки, не хватило памяти…) — переходим на запасной Vosk.
            Toast.makeText(this, getString(R.string.engine_fallback, message), Toast.LENGTH_LONG).show()
            changeEngine(EngineKind.VOSK)
        } else {
            wantListening = false
            lastError = message
            refreshStatus()
        }
    }

    // ---------- состояние интерфейса ----------

    private fun refreshStatus() {
        val d = doc
        val t = tracker
        val running = engine.isRunning
        // «Ищу место» показывается, только если поиск затянулся: быстрый успешный поиск незаметен
        val searching = running && t != null && t.searchMode && SystemClock.uptimeMillis() - searchSince >= SEARCH_SHOWN_AFTER_MS
        val finished = d != null && t != null && d.size > 0 && t.position >= d.size
        var micState = MicButton.State.IDLE
        var progress = -1f
        val status: String = when {
            !modelReady && modelProgress >= 0f -> {
                micState = MicButton.State.BUSY
                progress = modelProgress
                val percent = (modelProgress * 100).toInt()
                if (modelDownloading) {
                    getString(R.string.status_downloading_model, ModelStore.of(engineKind).downloadMb, percent)
                } else {
                    getString(R.string.status_preparing_model, percent)
                }
            }
            lastError != null && !running -> {
                micState = MicButton.State.ERROR
                getString(R.string.status_error, lastError)
            }
            !modelReady -> {
                micState = MicButton.State.ERROR
                getString(R.string.retry)
            }
            d == null || t == null -> {
                micState = MicButton.State.BUSY
                getString(R.string.status_loading_text)
            }
            finished -> getString(R.string.status_finished)
            starting || (wantListening && !running) -> {
                micState = MicButton.State.BUSY
                getString(R.string.status_starting)
            }
            searching -> {
                micState = MicButton.State.SEARCHING
                getString(R.string.status_searching)
            }
            running -> {
                micState = MicButton.State.LISTENING
                getString(R.string.status_listening)
            }
            stats.wordsRead > 0 || stats.activeMillis > 0 -> getString(R.string.status_paused)
            else -> getString(R.string.status_ready)
        }
        statusText.text = status
        mic.progress = progress
        mic.state = micState
        banner.visibility = if (searching) View.VISIBLE else View.GONE
    }

    private fun updateStats() {
        statsText.text = getString(R.string.stats_format, stats.wordsRead, stats.wordsPerMinute(), formatTime(stats.activeMillis))
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    private fun savePosition(force: Boolean) {
        val t = tracker ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveMs < 2000) return
        lastSaveMs = now
        settings.savePosition(docKey, t.position)
    }

    companion object {
        /** Создаёт движок распознавания. Тесты подменяют: нативные библиотеки на JVM не работают. */
        @JvmStatic
        var engineFactory: (EngineKind, SpeechEngine.Listener) -> SpeechEngine = { kind, listener ->
            if (kind == EngineKind.NEURAL) NeuralEngine(listener) else VoskEngine(listener)
        }

        private const val ASSET_TEXT = "odyssey_zhukovsky.txt"
        private const val CUSTOM_FILE = "custom.txt"
        private const val REQ_AUDIO = 1
        private const val REQ_OPEN_FILE = 2
        private const val MAX_FILE_BYTES = 40 * 1024 * 1024

        /** Сколько символов услышанного показывается под текстом. */
        private const val HEARD_CHARS = 80

        /** Через сколько после начала поиска места на экране появляется «потерял место», мс. */
        private const val SEARCH_SHOWN_AFTER_MS = 1500L
    }
}
