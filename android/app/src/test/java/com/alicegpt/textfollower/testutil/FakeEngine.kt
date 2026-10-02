package com.alicegpt.textfollower.testutil

import com.alicegpt.textfollower.speech.AutoGain
import com.alicegpt.textfollower.speech.EngineKind
import com.alicegpt.textfollower.speech.SpeechEngine
import java.io.File

/** Подставной движок для проверки экрана: микрофона и нативных библиотек на JVM нет. Всё вызывается из главного потока. */
class FakeEngine(
    private val listener: SpeechEngine.Listener,
    override val kind: EngineKind,
    /** Если задано — [start] сообщает, что движок не поднялся. */
    private val failure: String? = null,
) : SpeechEngine {
    override val gain = AutoGain()
    override var isRunning = false
        private set
    override val contextBack = 100
    override val contextAhead = 500

    var starts = 0
        private set
    var stops = 0
        private set
    var released = false
        private set

    /** Все подсказки по порядку: начальная и присланные [setContext]. */
    val contexts = ArrayList<List<String>?>()
    var modelDir: File? = null
        private set

    override fun start(modelDir: File, context: List<String>?) {
        starts++
        this.modelDir = modelDir
        contexts += context
        if (failure != null) {
            listener.onEngineFailure(failure)
            return
        }
        isRunning = true
        listener.onListeningChanged(true)
    }

    override fun setContext(words: List<String>?) {
        contexts += words
    }

    override fun stop() {
        if (!isRunning) return
        isRunning = false
        stops++
        listener.onListeningChanged(false)
    }

    override fun release() {
        stop()
        released = true
    }

    // Что «услышал» микрофон.
    fun partial(text: String) = listener.onPartial(text)
    fun final(text: String) = listener.onFinal(text)
    fun level(v: Float) = listener.onLevel(v)
    fun error(message: String) {
        isRunning = false
        listener.onError(message)
        listener.onListeningChanged(false)
    }
}
