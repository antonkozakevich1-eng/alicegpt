package com.alicegpt.textfollower

import android.Manifest
import android.app.Activity
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.alicegpt.textfollower.speech.GrammarBuilder
import com.alicegpt.textfollower.speech.ModelInstaller
import com.alicegpt.textfollower.speech.VoskEngine
import com.alicegpt.textfollower.text.Doc
import com.alicegpt.textfollower.text.TextLoader
import com.alicegpt.textfollower.tracking.TextTracker
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

class MainActivity : Activity(), VoskEngine.Listener {

    private lateinit var settings: Settings
    private lateinit var engine: VoskEngine
    private val stats = SessionStats()
    private val ui = Handler(Looper.getMainLooper())

    // views
    private lateinit var titleText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var readerText: TextView
    private lateinit var statusText: TextView
    private lateinit var heardText: TextView
    private lateinit var statsText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var bookProgress: ProgressBar
    private lateinit var startButton: Button

    // документ
    private var doc: Doc? = null
    private var tracker: TextTracker? = null
    private var docKey = Settings.DOC_ASSET
    private var currentSection = -1
    private var spannable: Spannable? = null
    private var readSpan: ForegroundColorSpan? = null
    private var curBg: BackgroundColorSpan? = null
    private var curFg: ForegroundColorSpan? = null

    // состояние
    private var modelReady = false
    private var modelProgress = -1
    private var wantListening = false     // пользователь нажал «Старт» и не нажимал «Пауза»
    private var resumeAfterPause = false
    private var starting = false
    private var lastError: String? = null   // показывается, пока не удастся запустить прослушивание снова
    private var lastTouchMs = 0L
    private var lastDownX = 0f
    private var lastDownY = 0f
    private var lastSaveMs = 0L

    // ограничение словаря окном вокруг позиции
    private var grammarCenter = -1
    private var grammarActive = false
    private var wordsSinceMove = 0        // слов в завершённых фразах без подтверждения позиции
    private var partialWords = 0          // слов в текущей (ещё не законченной) фразе
    private var partialAtMove = 0         // сколько из них было к моменту последнего подтверждения

    private val statsTick = object : Runnable {
        override fun run() {
            updateStats()
            ui.postDelayed(this, 1000)
        }
    }

    // ---------- жизненный цикл ----------

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        settings = Settings(this)
        setTheme(if (isDark()) R.style.Theme_App_Dark else R.style.Theme_App_Light)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        titleText = findViewById(R.id.titleText)
        scroll = findViewById(R.id.scroll)
        readerText = findViewById(R.id.readerText)
        statusText = findViewById(R.id.statusText)
        heardText = findViewById(R.id.heardText)
        statsText = findViewById(R.id.statsText)
        levelBar = findViewById(R.id.levelBar)
        bookProgress = findViewById(R.id.bookProgress)
        startButton = findViewById(R.id.startButton)

        engine = VoskEngine(this, this)
        engine.gain.sensitivity = settings.sensitivity
        readerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSp)

        findViewById<Button>(R.id.tocButton).setOnClickListener { showToc() }
        findViewById<Button>(R.id.fileButton).setOnClickListener { showFileMenu() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.fontSmaller).setOnClickListener { changeFont(-2f) }
        findViewById<Button>(R.id.fontBigger).setOnClickListener { changeFont(+2f) }
        startButton.setOnClickListener { toggleListening() }

        scroll.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) lastTouchMs = System.currentTimeMillis()
            false
        }
        readerText.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                lastDownX = ev.x
                lastDownY = ev.y
            }
            false
        }
        readerText.setOnLongClickListener {
            seekToTouch()
            true
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateStartButton()
        updateStats()

        prepareModel()
        loadDoc(settings.currentDoc)
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
        // Закрытие не должно ломать позицию: сохраняем и выходим.
        savePosition(force = true)
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---------- тема ----------

    private fun isDark(): Boolean = when (settings.theme) {
        Settings.THEME_LIGHT -> false
        Settings.THEME_DARK -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    // ---------- модель распознавания ----------

    private fun prepareModel() {
        if (ModelInstaller.isInstalled(this)) {
            modelReady = true
            refreshStatus()
            return
        }
        modelProgress = 0
        refreshStatus()
        Thread {
            try {
                ModelInstaller.install(this) { p ->
                    ui.post {
                        modelProgress = (p * 100).toInt()
                        refreshStatus()
                    }
                }
                ui.post {
                    modelReady = true
                    modelProgress = -1
                    refreshStatus()
                    updateStartButton()
                }
            } catch (e: Throwable) {
                ui.post {
                    modelProgress = -1
                    statusText.text = getString(R.string.status_error, e.message ?: e.javaClass.simpleName)
                }
            }
        }.start()
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
                val tr = TextTracker(d)
                tr.warmUp()
                ui.post { onDocLoaded(key, d, tr) }
            } catch (e: Throwable) {
                ui.post {
                    if (key != Settings.DOC_ASSET) {
                        settings.currentDoc = Settings.DOC_ASSET
                        loadDoc(Settings.DOC_ASSET)
                    } else {
                        statusText.text = getString(R.string.status_error, e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }.start()
    }

    private fun onDocLoaded(key: String, d: Doc, tr: TextTracker) {
        val wasListening = engine.isRunning
        if (wasListening) engine.stop()
        docKey = key
        settings.currentDoc = key
        doc = d
        tracker = tr
        tr.seek(settings.position(key).coerceIn(0, d.size))
        currentSection = -1
        grammarCenter = -1
        grammarActive = false
        wordsSinceMove = 0
        titleText.text = if (key == Settings.DOC_ASSET) "Гомер. Одиссея (пер. Жуковского)" else settings.customName
        render(forceScroll = true)
        refreshStatus()
        updateStartButton()
        if (wasListening) startListening()
    }

    // ---------- отображение ----------

    private fun showSection(index: Int) {
        val d = doc ?: return
        val sec = d.sections[index]
        currentSection = index
        val sp = SpannableString(d.text.substring(sec.start, sec.end))
        val marker = attrColor(R.attr.markerColor)
        val heading = attrColor(R.attr.headingColor)

        var i = firstRangeAtOrAfter(d.markerRanges, sec.start)
        while (i < d.markerRanges.size / 2 && d.markerRanges[2 * i] < sec.end) {
            sp.setSpan(ForegroundColorSpan(marker), d.markerRanges[2 * i] - sec.start, d.markerRanges[2 * i + 1] - sec.start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            i++
        }
        i = firstRangeAtOrAfter(d.headingRanges, sec.start)
        while (i < d.headingRanges.size / 2 && d.headingRanges[2 * i] < sec.end) {
            val a = d.headingRanges[2 * i] - sec.start
            val b = d.headingRanges[2 * i + 1] - sec.start
            sp.setSpan(ForegroundColorSpan(heading), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(StyleSpan(Typeface.BOLD), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(RelativeSizeSpan(1.2f), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            i++
        }
        readerText.setText(sp, TextView.BufferType.SPANNABLE)
        spannable = readerText.text as Spannable
        readSpan = ForegroundColorSpan(attrColor(R.attr.textDim))
        curBg = BackgroundColorSpan(attrColor(R.attr.hlBg))
        curFg = ForegroundColorSpan(attrColor(R.attr.hlText))
        scroll.scrollTo(0, 0)
    }

    private fun firstRangeAtOrAfter(ranges: IntArray, offset: Int): Int {
        var lo = 0
        var hi = ranges.size / 2
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ranges[2 * mid + 1] > offset) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** Перерисовывает подсветку по текущей позиции трекера. */
    private fun render(forceScroll: Boolean = false) {
        val d = doc ?: return
        val t = tracker ?: return
        if (d.size == 0) {
            readerText.text = getString(R.string.no_text)
            return
        }
        val pos = t.position
        val sectionIndex = d.sectionOfWord(if (pos >= d.size) d.size - 1 else pos)
        val switched = sectionIndex != currentSection
        if (switched) showSection(sectionIndex)
        val sec = d.sections[sectionIndex]
        val sp = spannable ?: return
        val read = readSpan ?: return
        val bg = curBg ?: return
        val fg = curFg ?: return

        if (pos < d.size) {
            val start = (d.wordStart[pos] - sec.start).coerceIn(0, sp.length)
            val end = (d.wordEnd[pos] - sec.start).coerceIn(start, sp.length)
            if (start > 0) sp.setSpan(read, 0, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) else sp.removeSpan(read)
            sp.setSpan(bg, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(fg, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            scrollToOffset(start, forceScroll || switched)
        } else {
            sp.setSpan(read, 0, sp.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.removeSpan(bg)
            sp.removeSpan(fg)
        }
        bookProgress.progress = if (d.size == 0) 0 else (pos * 1000L / d.size).toInt()
    }

    /** Держит текущее слово в верхней части экрана, не дёргая текст при каждом слове. */
    private fun scrollToOffset(offset: Int, force: Boolean) {
        val layout = readerText.layout
        if (layout == null) {
            readerText.post { scrollToOffset(offset, force) }
            return
        }
        if (!force && System.currentTimeMillis() - lastTouchMs < 4000) return // человек сам листает
        val line = layout.getLineForOffset(offset.coerceIn(0, readerText.length()))
        val top = layout.getLineTop(line) + readerText.totalPaddingTop
        val bottom = layout.getLineBottom(line) + readerText.totalPaddingTop
        val h = scroll.height
        val y = scroll.scrollY
        if (force || top < y + h * 0.12f || bottom > y + h * 0.60f) {
            val target = (top - h * 0.28f).toInt().coerceAtLeast(0)
            if (force) scroll.scrollTo(0, target) else scroll.smoothScrollTo(0, target)
        }
    }

    private fun changeFont(delta: Float) {
        settings.fontSp = settings.fontSp + delta
        readerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSp)
        val start = tracker?.let { t -> doc?.let { d -> if (t.position < d.size) d.wordStart[t.position] - d.sections[currentSection.coerceAtLeast(0)].start else 0 } } ?: 0
        readerText.post { scrollToOffset(start, true) }
    }

    // ---------- долгое нажатие: «начать отсюда» ----------

    private fun seekToTouch() {
        val d = doc ?: return
        val sec = d.sections.getOrNull(currentSection) ?: return
        val off = readerText.getOffsetForPosition(lastDownX, lastDownY)
        if (off < 0 || d.size == 0) return
        var word = d.wordAtOffset(sec.start + off)
        // Тап по строке с заголовком/номером попадёт на ближайшее следующее слово — это нормально.
        word = word.coerceIn(0, d.size - 1)
        readerText.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        seekTo(word)
        val sample = d.text.substring(d.wordStart[word], d.wordEnd[word])
        Toast.makeText(this, getString(R.string.seek_here, sample), Toast.LENGTH_SHORT).show()
    }

    private fun seekTo(word: Int) {
        val t = tracker ?: return
        t.seek(word)
        wordsSinceMove = 0
        partialAtMove = partialWords
        render(forceScroll = true)
        savePosition(force = true)
        refreshGrammar(force = true)
    }

    // ---------- оглавление, файлы, настройки ----------

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
                seekTo(toc[which].firstWord.coerceAtMost(maxOf(0, d.size - 1)))
            }
            .setNegativeButton(R.string.close, null)
            .show()
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
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(attrColor(R.attr.textMain))
            textSize = 15f
            setPadding(0, pad / 2, 0, pad / 4)
        }

        root.addView(label(getString(R.string.settings_theme)))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val names = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark)
        names.forEachIndexed { i, res ->
            group.addView(RadioButton(this).apply {
                id = 1000 + i
                setText(res)
                setTextColor(attrColor(R.attr.textMain))
                isChecked = settings.theme == i
            })
        }
        root.addView(group)

        val sensLabel = label(getString(R.string.settings_sensitivity, settings.sensitivity))
        root.addView(sensLabel)
        val seek = SeekBar(this).apply {
            max = 100
            progress = settings.sensitivity
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    sensLabel.text = getString(R.string.settings_sensitivity, value)
                    settings.sensitivity = value
                    engine.gain.sensitivity = value
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        root.addView(seek)
        root.addView(TextView(this).apply {
            setText(R.string.settings_sensitivity_hint)
            setTextColor(attrColor(R.attr.textSubtle))
            textSize = 12f
        })

        val restrict = CheckBox(this).apply {
            setText(R.string.settings_restrict)
            setTextColor(attrColor(R.attr.textMain))
            isChecked = settings.restrictVocabulary
        }
        root.addView(restrict)

        val reset = Button(this, null, 0, R.style.Btn).apply {
            setText(R.string.settings_reset_stats)
            setOnClickListener {
                stats.reset()
                updateStats()
            }
        }
        root.addView(reset)

        val oldTheme = settings.theme
        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton(R.string.close) { _, _ ->
                val chosen = group.checkedRadioButtonId - 1000
                if (restrict.isChecked != settings.restrictVocabulary) {
                    settings.restrictVocabulary = restrict.isChecked
                    if (restrict.isChecked) refreshGrammar(force = true) else {
                        grammarActive = false
                        if (engine.isRunning) engine.setGrammar(null)
                    }
                }
                if (chosen in 0..2 && chosen != oldTheme) {
                    settings.theme = chosen
                    savePosition(force = true)
                    recreate()
                }
            }
            .show()
    }

    // ---------- прослушивание ----------

    private fun toggleListening() {
        if (wantListening) {
            wantListening = false
            engine.stop()
            stats.pause()
            updateStartButton()
            refreshStatus()
        } else {
            wantListening = true
            startListening()
        }
    }

    private fun startListening() {
        if (!modelReady || tracker == null || engine.isRunning || starting) {
            updateStartButton()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        starting = true
        lastError = null
        wordsSinceMove = 0
        partialWords = 0
        partialAtMove = 0
        val d = doc
        val pos = tracker?.position ?: 0
        val grammar = if (settings.restrictVocabulary && d != null) {
            grammarCenter = pos
            grammarActive = true
            GrammarBuilder.forWindow(d, pos)
        } else {
            grammarActive = false
            null
        }
        statusText.setText(R.string.status_starting)
        engine.start(ModelInstaller.modelDir(this), grammar)
        updateStartButton()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (wantListening) startListening()
        } else {
            wantListening = false
            statusText.setText(R.string.permission_needed)
            updateStartButton()
        }
    }

    // ---------- события распознавателя ----------

    override fun onPartial(text: String) = handleSpeech(text, isFinal = false)

    override fun onFinal(text: String) = handleSpeech(text, isFinal = true)

    private fun handleSpeech(text: String, isFinal: Boolean) {
        val t = tracker ?: return
        heardText.text = getString(R.string.heard_format, text.takeLast(80))
        val move = if (isFinal) t.onFinal(text) else t.onPartial(text)
        val count = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        if (isFinal) {
            if (move == null) wordsSinceMove += maxOf(0, count - partialAtMove)
            partialWords = 0
            partialAtMove = 0
        } else {
            partialWords = count
        }
        if (move != null) {
            wordsSinceMove = 0
            partialAtMove = partialWords
            if (move.delta > 0 && !move.far) stats.onForwardMove(move.delta)
            render()
            updateStats()
            savePosition(force = false)
            if (!grammarActive && settings.restrictVocabulary) refreshGrammar(force = true)
            else refreshGrammar(force = false)
            refreshStatus()
        } else if (settings.restrictVocabulary && grammarActive && unconfirmedWords() >= LOST_WORDS) {
            // Подсветка давно не подтверждается: возможно, читатель ушёл за пределы окна словаря.
            // Снимаем ограничение — тогда можно найти новое место по длинному совпадению.
            grammarActive = false
            engine.setGrammar(null)
            refreshStatus()
        }
    }

    private fun unconfirmedWords() = wordsSinceMove + maxOf(0, partialWords - partialAtMove)

    /** Окно словаря следует за позицией чтения. */
    private fun refreshGrammar(force: Boolean) {
        val d = doc ?: return
        val t = tracker ?: return
        if (!settings.restrictVocabulary || !engine.isRunning) return
        if (!force && grammarActive && abs(t.position - grammarCenter) <= GrammarBuilder.REBUILD_DISTANCE) return
        grammarCenter = t.position
        grammarActive = true
        engine.setGrammar(GrammarBuilder.forWindow(d, t.position))
    }

    override fun onLevel(level: Float) {
        levelBar.progress = (level * 100).toInt()
    }

    override fun onListeningChanged(listening: Boolean) {
        starting = false
        if (listening) {
            stats.start()
        } else {
            stats.pause()
            levelBar.progress = 0
        }
        updateStartButton()
        refreshStatus()
    }

    override fun onError(message: String) {
        starting = false
        wantListening = false
        lastError = message
        stats.pause()
        updateStartButton()
        refreshStatus()
    }

    // ---------- состояние интерфейса ----------

    private fun updateStartButton() {
        startButton.isEnabled = modelReady && tracker != null
        startButton.alpha = if (startButton.isEnabled) 1f else 0.5f
        startButton.setText(if (wantListening) R.string.pause else R.string.start)
    }

    private fun refreshStatus() {
        val d = doc
        val t = tracker
        statusText.text = when {
            !modelReady && modelProgress >= 0 -> getString(R.string.status_preparing_model, modelProgress)
            lastError != null && !engine.isRunning -> getString(R.string.status_error, lastError)
            d == null || t == null -> getString(R.string.status_loading_text)
            d.size > 0 && t.position >= d.size -> getString(R.string.status_finished)
            starting -> getString(R.string.status_starting)
            engine.isRunning && modelReady && !grammarActive && settings.restrictVocabulary && unconfirmedWords() >= LOST_WORDS -> getString(R.string.status_searching)
            engine.isRunning -> getString(R.string.status_listening)
            wantListening -> getString(R.string.status_starting)
            stats.wordsRead > 0 || stats.activeMillis > 0 -> getString(R.string.status_paused)
            else -> getString(R.string.status_ready)
        }
    }

    private fun updateStats() {
        val ms = stats.activeMillis
        statsText.text = getString(R.string.stats_format, stats.wordsRead, stats.wordsPerMinute(), formatTime(ms))
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

    private companion object {
        const val ASSET_TEXT = "odyssey_zhukovsky.txt"
        const val CUSTOM_FILE = "custom.txt"
        const val REQ_AUDIO = 1
        const val REQ_OPEN_FILE = 2
        const val MAX_FILE_BYTES = 40 * 1024 * 1024
        /** Сколько распознанных слов без подтверждения позиции считаем «потерялись». */
        const val LOST_WORDS = 14
    }
}
