package com.alicegpt.textfollower.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Непрерывное офлайн-распознавание речи.
 *
 * Микрофон читается без пауз в отдельном потоке (VOICE_RECOGNITION, 16 кГц, моно), звук проходит
 * через [AutoGain] и подаётся в Vosk. Паузы в речи не останавливают поток: Vosk сам выдаёт
 * законченные фразы, а промежуточные результаты (partial) приходят по ходу слова.
 *
 * Все вызовы Vosk идут из одного потока. Все коллбэки приходят в главный поток.
 */
class VoskEngine(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** Промежуточный результат: вся фраза с её начала, уточняется по ходу речи. */
        fun onPartial(text: String)

        /** Законченная фраза. */
        fun onFinal(text: String)

        fun onLevel(level: Float)

        /** Микрофон начал/закончил работу. */
        fun onListeningChanged(listening: Boolean)

        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    val gain = AutoGain()

    /** Один запуск прослушивания: у каждого свой флаг, чтобы остановленный поток не ожил при новом старте. */
    private class Session {
        @Volatile
        var active = true
    }

    private var session: Session? = null
    private var thread: Thread? = null

    @Volatile
    private var model: Model? = null
    private val pendingGrammar = AtomicReference<GrammarRequest?>(null)

    @Volatile
    private var initialGrammar: String? = null

    /** Обёртка, чтобы отличать «нет запроса» от «сбросить грамматику». */
    private class GrammarRequest(val json: String?)

    val isRunning: Boolean get() = session?.active == true

    /** Запускает прослушивание. [grammar] — JSON-список слов (ограничение словаря) либо null. */
    fun start(modelDir: File, grammar: String?) {
        if (isRunning) return
        val s = Session()
        session = s
        initialGrammar = grammar
        pendingGrammar.set(null)
        val t = Thread({ loop(s, modelDir) }, "vosk-audio")
        thread = t
        t.start()
    }

    /** Меняет ограничение словаря; применяется на границе фразы (Vosk не позволяет менять посреди неё). */
    fun setGrammar(json: String?) {
        pendingGrammar.set(GrammarRequest(json))
    }

    fun stop() {
        session?.active = false
        thread?.let { t ->
            if (Thread.currentThread() !== t) {
                try {
                    t.join(2000)
                } catch (_: InterruptedException) {
                }
            }
        }
        thread = null
        session = null
    }

    /** Освобождает модель (около 100 МБ памяти). */
    fun release() {
        stop()
        model?.close()
        model = null
    }

    // ---------- поток записи и распознавания ----------

    // Разрешение проверяет и запрашивает MainActivity; здесь любая ошибка (в том числе SecurityException) уходит в onError.
    @SuppressLint("MissingPermission")
    private fun loop(s: Session, modelDir: File) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var record: AudioRecord? = null
        var recognizer: Recognizer? = null
        val effects = ArrayList<android.media.audiofx.AudioEffect>()
        try {
            val m = synchronized(this) {
                model ?: run {
                    LibVosk.setLogLevel(LogLevel.WARNINGS)
                    Model(modelDir.absolutePath).also { model = it }
                }
            }
            if (!s.active) return
            var rec = newRecognizer(m, initialGrammar)
            recognizer = rec

            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) throw IllegalStateException("Микрофон недоступен")
            // Запас в секунду звука: пока Vosk занят (например, пересборкой грамматики), запись не теряется.
            val bufBytes = maxOf(minBuf * 4, SAMPLE_RATE * 2)
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Не удалось открыть микрофон")

            val session = record.audioSessionId
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.let { it.enabled = true; effects += it }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { it.enabled = true; effects += it }
            // Эхоподавитель при чтении вслух не нужен и может «съедать» речь.
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.let { it.enabled = false; effects += it }

            gain.reset()
            record.startRecording()
            post(s) { listener.onListeningChanged(true) }

            val buf = ShortArray(CHUNK_SAMPLES)
            var lastPartial = ""
            var lastLevelAt = 0L
            while (s.active) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("Ошибка чтения микрофона ($n)")
                if (n == 0) continue

                val level = gain.process(buf, n)
                val now = System.currentTimeMillis()
                if (now - lastLevelAt >= 60) {
                    lastLevelAt = now
                    post(s) { listener.onLevel(level) }
                }

                if (rec.acceptWaveForm(buf, n)) {
                    val text = JSONObject(rec.result).optString("text", "")
                    lastPartial = ""
                    if (text.isNotBlank()) post(s) { listener.onFinal(text) }
                    // Фраза закончилась — самое время сменить словарь.
                    pendingGrammar.getAndSet(null)?.let { req ->
                        rec.close()
                        recognizer = null
                        rec = newRecognizer(m, req.json)
                        recognizer = rec
                    }
                } else {
                    val partial = JSONObject(rec.partialResult).optString("partial", "")
                    if (partial != lastPartial) {
                        lastPartial = partial
                        if (partial.isNotBlank()) post(s) { listener.onPartial(partial) }
                    }
                }
            }
        } catch (e: Throwable) {
            val msg = e.message ?: e.javaClass.simpleName
            post(s) { listener.onError(msg) }
        } finally {
            s.active = false
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            effects.forEach { try { it.release() } catch (_: Exception) {} }
            record?.release()
            try {
                recognizer?.close()
            } catch (_: Exception) {
            }
            post(s) { listener.onListeningChanged(false) }
        }
    }

    private fun newRecognizer(m: Model, grammar: String?): Recognizer {
        val r = if (grammar != null) Recognizer(m, SAMPLE_RATE.toFloat(), grammar) else Recognizer(m, SAMPLE_RATE.toFloat())
        r.setWords(false)
        r.setPartialWords(false)
        return r
    }

    /** Доставляет событие в главный поток, если запуск не вытеснен более новым. */
    private fun post(s: Session, block: () -> Unit) {
        main.post {
            val current = session
            if (current == null || current === s) block()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        /** 100 мс звука. */
        const val CHUNK_SAMPLES = 1600
    }
}
