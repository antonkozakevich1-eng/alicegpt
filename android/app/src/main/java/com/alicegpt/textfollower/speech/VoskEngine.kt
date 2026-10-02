package com.alicegpt.textfollower.speech

import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Классический Vosk (Kaldi), модель `vosk-model-small-ru-0.22`. Подсказка — ограничение словаря
 * словами из окна текста вокруг позиции (плюс `[unk]`).
 */
class VoskEngine(listener: SpeechEngine.Listener) : StreamingEngine(listener) {

    override val kind = EngineKind.VOSK
    override val threadName = "vosk-audio"
    override val contextBack = 50
    override val contextAhead = 250

    @Volatile
    private var model: Model? = null

    override fun loadModel(modelDir: File) {
        if (model != null) return
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        model = Model(modelDir.absolutePath)
    }

    override fun unloadModel() {
        model?.close()
        model = null
    }

    override fun createDecoder(context: List<String>?): SpeechDecoder {
        val m = model ?: throw IllegalStateException("Модель не загружена")
        val grammar = context?.let { GrammarBuilder.fromWords(it) }
        val r = if (grammar != null) Recognizer(m, AudioInput.SAMPLE_RATE.toFloat(), grammar) else Recognizer(m, AudioInput.SAMPLE_RATE.toFloat())
        r.setWords(false)
        r.setPartialWords(false)
        return object : SpeechDecoder {
            override fun accept(buf: ShortArray, n: Int) = r.acceptWaveForm(buf, n)
            override fun finalText() = JSONObject(r.result).optString("text", "")
            override fun partialText() = JSONObject(r.partialResult).optString("partial", "")
            override fun close() = r.close()
        }
    }
}
