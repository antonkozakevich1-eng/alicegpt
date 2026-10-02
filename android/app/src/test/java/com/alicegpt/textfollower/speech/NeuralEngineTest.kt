package com.alicegpt.textfollower.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Настройки нейросетевого движка проверяются без нативной библиотеки: конфигурация — обычные данные. */
class NeuralEngineTest {

    @Test
    fun hotwordsAreUniqueLongEnoughWordsJoinedBySlash() {
        val hint = NeuralEngine.hotwordsOf(listOf("и", "лодка", "по", "лодка", "тихой", "река", "я"))
        assertEquals("лодка/тихой/река", hint)
    }

    @Test
    fun noContextMeansNoHotwords() {
        assertEquals("", NeuralEngine.hotwordsOf(null))
        assertEquals("", NeuralEngine.hotwordsOf(emptyList()))
        assertEquals("", NeuralEngine.hotwordsOf(listOf("и", "в", "на")))
    }

    @Test
    fun configurationPointsToTheModelFilesAndEnablesBiasing() {
        val dir = File("/data/model")
        val c = NeuralEngine.buildConfig(dir)
        assertEquals("modified_beam_search", c.decodingMethod)
        assertEquals(NeuralEngine.HOTWORDS_SCORE, c.hotwordsScore, 0f)
        assertEquals("bpe", c.modelConfig.modelingUnit)
        assertEquals(File(dir, NeuralEngine.BPE_VOCAB).absolutePath, c.modelConfig.bpeVocab)
        assertEquals(File(dir, NeuralEngine.ENCODER).absolutePath, c.modelConfig.transducer.encoder)
        assertEquals(File(dir, NeuralEngine.DECODER).absolutePath, c.modelConfig.transducer.decoder)
        assertEquals(File(dir, NeuralEngine.JOINER).absolutePath, c.modelConfig.transducer.joiner)
        assertEquals(File(dir, NeuralEngine.TOKENS).absolutePath, c.modelConfig.tokens)
        assertEquals(16000, c.featConfig.sampleRate)
        assertTrue(c.enableEndpoint)
        assertTrue(c.modelConfig.numThreads in 1..4)
    }

    @Test
    fun contextWindowIsWideBecauseAccuracyBarelyDependsOnIt() {
        val engine = NeuralEngine(object : SpeechEngine.Listener {
            override fun onPartial(text: String) {}
            override fun onFinal(text: String) {}
            override fun onLevel(level: Float) {}
            override fun onListeningChanged(listening: Boolean) {}
            override fun onError(message: String) {}
            override fun onEngineFailure(message: String) {}
        })
        assertEquals(EngineKind.NEURAL, engine.kind)
        assertTrue(engine.contextAhead >= 300 && engine.contextBack >= 50)
    }
}
