package com.alicegpt.textfollower.speech

import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Распознаватель одного непрерывного прослушивания (создаётся заново при смене подсказки). */
interface SpeechDecoder : AutoCloseable {
    /** Подаёт блок звука; true — фраза закончилась (на паузе), текст — в [finalText]. */
    fun accept(buf: ShortArray, n: Int): Boolean

    /** Текст законченной фразы (после [accept], вернувшего true). */
    fun finalText(): String

    /** Текущая гипотеза незаконченной фразы. */
    fun partialText(): String
}

/**
 * Общий цикл для всех движков: микрофон читается без пауз в отдельном потоке, звук проходит через [AutoGain]
 * и подаётся распознавателю. Паузы в речи не останавливают поток. Все обращения к распознавателю идут из
 * одного потока, все коллбэки приходят в главный поток.
 */
abstract class StreamingEngine(
    protected val listener: SpeechEngine.Listener,
    private val audioFactory: () -> AudioSource = { AudioInput() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val nanoClock: () -> Long = System::nanoTime,
) : SpeechEngine {

    private val main by lazy { Handler(Looper.getMainLooper()) }
    override val gain = AutoGain()

    /** Один запуск прослушивания: у каждого свой флаг, чтобы остановленный поток не ожил при новом старте. */
    private class Session {
        @Volatile
        var active = true

        /** Поток дошёл до конца (под [lock]). */
        var finished = false

        /** Модель надо выгрузить, как только поток закончит (под [lock]). */
        var unloadRequested = false
    }

    /** Загрузка и выгрузка модели не пересекаются с работой потока. */
    private val lock = Any()

    /** Модель загружена (под [lock]): [loadModel] вызывается один раз до [unloadModel]. */
    private var modelLoaded = false

    private class ContextRequest(val words: List<String>?)

    private var session: Session? = null

    /** Последний запуск — даже после [stop], чтобы [release] знал, жив ли ещё его поток. */
    private var lastSession: Session? = null
    private var thread: Thread? = null
    private val pendingContext = AtomicReference<ContextRequest?>(null)

    @Volatile
    private var initialContext: List<String>? = null

    override val isRunning: Boolean get() = session?.active == true

    protected abstract val threadName: String

    /** Загружает модель; вызывается из потока записи, не повторно, пока модель не выгружена. */
    protected abstract fun loadModel(modelDir: File)

    protected abstract fun createDecoder(context: List<String>?): SpeechDecoder

    protected abstract fun unloadModel()

    override fun start(modelDir: File, context: List<String>?) {
        if (isRunning) return
        val s = Session()
        session = s
        lastSession = s
        initialContext = context
        pendingContext.set(null)
        val t = Thread({ loop(s, modelDir) }, threadName)
        thread = t
        t.start()
    }

    override fun setContext(words: List<String>?) {
        pendingContext.set(ContextRequest(words))
    }

    override fun stop() {
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

    override fun release() {
        stop()
        val s = lastSession
        // Поток обычно уже закончил; если он ещё грузит модель, выгрузит её сам, чтобы не выдернуть модель из-под него.
        synchronized(lock) {
            if (s == null || s.finished) unload() else s.unloadRequested = true
        }
    }

    private fun loop(s: Session, modelDir: File) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val input = audioFactory()
        var decoder: SpeechDecoder? = null
        try {
            try {
                synchronized(lock) {
                    if (!modelLoaded) {
                        loadModel(modelDir)
                        modelLoaded = true
                    }
                }
                if (!s.active) return
                decoder = createDecoder(initialContext)
            } catch (e: Throwable) {
                // Движок не поднялся — это не ошибка микрофона: приложение может переключиться на запасной.
                val msg = e.message ?: e.javaClass.simpleName
                post(s) { listener.onEngineFailure(msg) }
                return
            }
            input.open()
            gain.reset()
            post(s) { listener.onListeningChanged(true) }

            val buf = ShortArray(AudioInput.CHUNK_SAMPLES)
            var lastPartial = ""
            var lastLevelAt = 0L
            var utteranceStart = clock()
            var chunks = 0
            var slowStreak = 0
            while (s.active) {
                val n = input.read(buf)
                if (n == 0) continue
                val started = nanoClock()

                val level = gain.process(buf, n)
                val now = clock()
                if (now - lastLevelAt >= 60) {
                    lastLevelAt = now
                    post(s) { listener.onLevel(level) }
                }

                val dec = decoder!!
                val finished = dec.accept(buf, n)
                // Телефон не успевает за распознавателем: обработка блока дольше самого блока — звук копится, подсветка отстаёт.
                val budget = n * NANOS_PER_SECOND / AudioInput.SAMPLE_RATE
                if (++chunks > WARM_UP_CHUNKS && (nanoClock() - started) * 10 >= budget * 9) slowStreak++ else slowStreak = 0
                if (slowStreak >= SLOW_CHUNKS) {
                    post(s) { listener.onEngineFailure(TOO_SLOW) }
                    return
                }
                if (finished) {
                    val text = dec.finalText()
                    lastPartial = ""
                    utteranceStart = now
                    if (text.isNotBlank()) post(s) { listener.onFinal(text) }
                    // Фраза закончилась — самое время сменить подсказку.
                    pendingContext.getAndSet(null)?.let { req ->
                        dec.close()
                        decoder = createDecoder(req.words)
                    }
                } else {
                    val partial = dec.partialText()
                    if (partial != lastPartial) {
                        lastPartial = partial
                        if (partial.isNotBlank()) post(s) { listener.onPartial(partial) }
                    }
                    // Без пауз подсказка так и не сменится: принудительно закрываем фразу.
                    if (now - utteranceStart > FORCE_CONTEXT_AFTER_MS && partial.isNotBlank()) {
                        pendingContext.getAndSet(null)?.let { req ->
                            lastPartial = ""
                            utteranceStart = now
                            post(s) { listener.onFinal(partial) }
                            dec.close()
                            decoder = createDecoder(req.words)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            val msg = e.message ?: e.javaClass.simpleName
            post(s) { listener.onError(msg) }
        } finally {
            s.active = false
            input.close()
            try {
                decoder?.close()
            } catch (_: Exception) {
            }
            post(s) { listener.onListeningChanged(false) }
            synchronized(lock) {
                s.finished = true
                if (s.unloadRequested) unload()
            }
        }
    }

    /** Выгружает модель, если она загружена (под [lock]). */
    private fun unload() {
        if (modelLoaded) {
            modelLoaded = false
            unloadModel()
        }
    }

    /** Доставляет событие в главный поток, если запуск не вытеснен более новым. */
    private fun post(s: Session, block: () -> Unit) {
        main.post {
            val current = session
            if (current == null || current === s) block()
        }
    }

    private companion object {
        const val FORCE_CONTEXT_AFTER_MS = 20_000L
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** Первые блоки не считаются: библиотека «разогревается». */
        const val WARM_UP_CHUNKS = 20

        /** Столько блоков подряд (≈ 4 с звука) обработаны медленнее, чем длится звук, — движок не тянет. */
        const val SLOW_CHUNKS = 40
        const val TOO_SLOW = "телефон не успевает обрабатывать звук"
    }
}
