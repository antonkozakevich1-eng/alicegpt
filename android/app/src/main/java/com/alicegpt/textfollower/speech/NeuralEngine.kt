package com.alicegpt.textfollower.speech

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Нейросетевое распознавание: стриминговый zipformer (`vosk-model-small-streaming-ru`, Apache-2.0) на sherpa-onnx.
 *
 * Подсказка — «горячие слова» из окна текста вокруг позиции (contextual biasing в modified beam search):
 * на тестовых записях это поднимает точность слов с 0,65 до 0,77 и вдвое ускоряет декодирование по сравнению с Vosk.
 * В отличие от ограничения словаря она не запрещает остальные слова, поэтому чужая речь не «притягивается» к тексту.
 */
class NeuralEngine(listener: SpeechEngine.Listener) : StreamingEngine(listener) {

    override val kind = EngineKind.NEURAL
    override val threadName = "neural-audio"

    // По тестам точность слабо зависит от размера окна (150–600 слов), поэтому окно широкое и меняется редко.
    override val contextBack = 100
    override val contextAhead = 500

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    override fun loadModel(modelDir: File) {
        if (recognizer != null) return
        recognizer = OnlineRecognizer(config = buildConfig(modelDir))
    }

    override fun unloadModel() {
        recognizer?.release()
        recognizer = null
    }

    override fun createDecoder(context: List<String>?): SpeechDecoder {
        val r = recognizer ?: throw IllegalStateException("Модель не загружена")
        val stream = r.createStream(hotwordsOf(context))
        return object : SpeechDecoder {
            private var current = ""
            private var ended = ""

            override fun accept(buf: ShortArray, n: Int): Boolean {
                val samples = FloatArray(n) { buf[it] / 32768f }
                stream.acceptWaveform(samples, AudioInput.SAMPLE_RATE)
                while (r.isReady(stream)) r.decode(stream)
                val text = r.getResult(stream).text
                if (r.isEndpoint(stream)) {
                    ended = text
                    current = ""
                    r.reset(stream)
                    return true
                }
                current = text
                return false
            }

            override fun finalText() = ended.lowercase()
            override fun partialText() = current.lowercase()
            override fun close() = stream.release()
        }
    }

    internal companion object {
        const val ENCODER = "encoder.int8.onnx"
        const val DECODER = "decoder.onnx"
        const val JOINER = "joiner.int8.onnx"
        const val TOKENS = "tokens.txt"
        const val BPE_VOCAB = "unigram_500.vocab"

        /** Усиление «горячих слов»: 2–4 одинаково хороши на тестах. */
        const val HOTWORDS_SCORE = 3.0f

        /** Слова короче трёх букв как подсказка бесполезны и только мешают. */
        fun hotwordsOf(context: List<String>?): String =
            context?.asSequence()?.filter { it.length >= 3 }?.distinct()?.joinToString("/") ?: ""

        fun buildConfig(dir: File) = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioInput.SAMPLE_RATE, featureDim = 80, dither = 0f),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, ENCODER).absolutePath,
                    decoder = File(dir, DECODER).absolutePath,
                    joiner = File(dir, JOINER).absolutePath,
                ),
                tokens = File(dir, TOKENS).absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelingUnit = "bpe",
                bpeVocab = File(dir, BPE_VOCAB).absolutePath,
            ),
            // Конец фразы: 0,8 с тишины после речи (так чаще приходят законченные фразы), 2,4 с тишины — без речи, 30 с — максимум.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 0.8f, 0f),
                rule3 = EndpointRule(false, 0f, 30f),
            ),
            enableEndpoint = true,
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
            hotwordsScore = HOTWORDS_SCORE,
        )
    }
}
