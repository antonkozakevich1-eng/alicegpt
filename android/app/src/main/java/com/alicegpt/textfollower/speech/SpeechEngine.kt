package com.alicegpt.textfollower.speech

import java.io.File

/** Какой движок распознавания речи используется. */
enum class EngineKind(val id: Int) {
    /** Нейросеть (zipformer, sherpa-onnx) с подсказкой слов из текста — точнее и быстрее. */
    NEURAL(0),

    /** Классический Vosk (Kaldi) с ограничением словаря — запасной вариант. */
    VOSK(1);

    companion object {
        fun of(id: Int) = values().firstOrNull { it.id == id } ?: NEURAL
    }
}

/**
 * Непрерывное распознавание речи с микрофона. Результаты приходят в главный поток.
 * Фраза уточняется по ходу речи ([Listener.onPartial]) и заканчивается ([Listener.onFinal]) на паузе.
 */
interface SpeechEngine {

    interface Listener {
        /** Промежуточный результат: вся фраза с её начала, уточняется по ходу речи. */
        fun onPartial(text: String)

        /** Законченная фраза. */
        fun onFinal(text: String)

        fun onLevel(level: Float)

        /** Микрофон начал/закончил работу. */
        fun onListeningChanged(listening: Boolean)

        /** Ошибка микрофона или чтения звука. */
        fun onError(message: String)

        /** Сам движок не запустился (нет библиотеки, модель повреждена, не хватило памяти). */
        fun onEngineFailure(message: String)
    }

    val kind: EngineKind

    /** Программное усиление тихого голоса; чувствительность меняется на лету. */
    val gain: AutoGain

    val isRunning: Boolean

    /** Окно подсказки в словах: назад и вперёд от текущей позиции. */
    val contextBack: Int
    val contextAhead: Int

    /** Запускает прослушивание; [context] — слова текста вокруг позиции (подсказка распознавателю) либо null. */
    fun start(modelDir: File, context: List<String>?)

    /** Меняет подсказку; применяется на границе фразы. null — убрать подсказку. */
    fun setContext(words: List<String>?)

    fun stop()

    /** Освобождает модель (десятки мегабайт памяти). */
    fun release()
}
