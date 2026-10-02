package com.alicegpt.textfollower.speech

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Цикл распознавания без микрофона и нативных библиотек: подставной источник звука и подставной распознаватель.
 * Проверяется то, что можно сломать только в самом цикле: порядок partial/final, смена подсказки на границе фразы,
 * принудительная смена при непрерывной речи, разделение ошибок движка и микрофона, остановка и освобождение.
 */
@RunWith(RobolectricTestRunner::class)
class StreamingEngineTest {

    /**
     * Источник звука: блоки тихого шума. Обычно идёт без остановок; в режиме [paced] отдаёт ровно столько блоков,
     * сколько разрешено [step] — так порядок событий в тесте не зависит от скорости потоков.
     */
    private class FakeAudio(
        private val failOpen: String? = null,
        private val failReadAt: Int = -1,
        private val paced: Boolean = false,
    ) : AudioSource {
        val reads = AtomicInteger()
        private val permits = java.util.concurrent.Semaphore(0)

        /** Разрешает прочитать ещё [blocks] блоков. */
        fun step(blocks: Int) = permits.release(blocks)

        @Volatile
        var opened = false

        @Volatile
        var closed = false

        override fun open() {
            failOpen?.let { throw IllegalStateException(it) }
            opened = true
        }

        override fun read(buf: ShortArray): Int {
            if (paced && !permits.tryAcquire(20, java.util.concurrent.TimeUnit.MILLISECONDS)) return 0
            val n = reads.incrementAndGet()
            if (n == failReadAt) throw IllegalStateException("Ошибка чтения микрофона (-3)")
            Thread.sleep(1)
            for (i in buf.indices) buf[i] = (((i * 7 + n) % 200) - 100).toShort()
            return buf.size
        }

        override fun close() {
            closed = true
        }
    }

    /** Распознаватель по сценарию: после блока с номером из [finalAt] фраза заканчивается. */
    private class ScriptedDecoder(
        val context: List<String>?,
        private val finalAt: Set<Int>,
        private val chunk: AtomicInteger,
    ) : SpeechDecoder {
        @Volatile
        var closed = false
        private var words = 0
        private var text = ""

        override fun accept(buf: ShortArray, n: Int): Boolean {
            val i = chunk.incrementAndGet()
            words++
            text = (1..words).joinToString(" ") { "слово$it" }
            if (i in finalAt) {
                words = 0
                return true
            }
            return false
        }

        override fun finalText() = text.also { text = "" }
        override fun partialText() = text
        override fun close() {
            closed = true
        }
    }

    private class Recorder : SpeechEngine.Listener {
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun onPartial(text: String) { events += "p:$text" }
        override fun onFinal(text: String) { events += "f:$text" }
        override fun onLevel(level: Float) {}
        override fun onListeningChanged(listening: Boolean) { events += "listen:$listening" }
        override fun onError(message: String) { events += "error:$message" }
        override fun onEngineFailure(message: String) { events += "failure:$message" }
    }

    private class TestEngine(
        listener: SpeechEngine.Listener,
        audio: AudioSource,
        clock: () -> Long = System::currentTimeMillis,
        private val finalAt: Set<Int> = emptySet(),
        private val loadFailure: String? = null,
        private val decoderFailure: String? = null,
        nanoClock: () -> Long = System::nanoTime,
    ) : StreamingEngine(listener, { audio }, clock, nanoClock) {
        override val kind = EngineKind.NEURAL
        override val threadName = "test-audio"
        override val contextBack = 10
        override val contextAhead = 10

        val decoders = java.util.Collections.synchronizedList(ArrayList<ScriptedDecoder>())
        val chunk = AtomicInteger()
        val loads = AtomicInteger()
        val unloads = AtomicInteger()

        override fun loadModel(modelDir: File) {
            loadFailure?.let { throw IllegalStateException(it) }
            loads.incrementAndGet()
        }

        override fun createDecoder(context: List<String>?): SpeechDecoder {
            decoderFailure?.let { throw IllegalStateException(it) }
            return ScriptedDecoder(context, finalAt, chunk).also { decoders += it }
        }

        override fun unloadModel() {
            unloads.incrementAndGet()
        }
    }

    private val engines = ArrayList<StreamingEngine>()

    @After
    fun cleanup() {
        engines.forEach { it.release() }
    }

    private fun <T : StreamingEngine> T.tracked(): T = also { engines += it }

    /** Ждёт условие, обрабатывая сообщения главного потока (коллбэки движка приходят туда). */
    private fun await(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("условие не выполнилось за $timeoutMs мс", cond())
    }

    private val dir = File("/nonexistent")

    @Test
    fun partialsAndFinalsArriveInOrderAndMicrophoneStateIsReported() {
        val rec = Recorder()
        val audio = FakeAudio()
        val engine = TestEngine(rec, audio, finalAt = setOf(3)).tracked()
        engine.start(dir, null)
        await { rec.events.count { it.startsWith("f:") } >= 1 && rec.events.any { it.startsWith("p:") } }
        engine.stop()
        shadowOf(Looper.getMainLooper()).idle()

        val e = rec.events.toList()
        assertEquals("listen:true", e.first())
        assertEquals("listen:false", e.last())
        // два блока — гипотеза растёт, третий закрывает фразу
        assertEquals(listOf("p:слово1", "p:слово1 слово2", "f:слово1 слово2 слово3"), e.filter { it.startsWith("p:") || it.startsWith("f:") }.take(3))
        assertTrue(audio.opened && audio.closed)
        assertFalse(engine.isRunning)
    }

    @Test
    fun contextChangeWaitsForTheEndOfThePhrase() {
        val rec = Recorder()
        val audio = FakeAudio(paced = true)
        val engine = TestEngine(rec, audio, finalAt = setOf(6)).tracked()
        engine.start(dir, listOf("а"))
        audio.step(2)
        await { engine.chunk.get() == 2 }
        engine.setContext(listOf("б", "в"))
        // посреди фразы новый распознаватель не создаётся
        audio.step(3)
        await { engine.chunk.get() == 5 }
        assertEquals(1, engine.decoders.size)
        // фраза закончилась (блок 6) — подсказка сменилась, старый распознаватель закрыт
        audio.step(1)
        await { engine.decoders.size == 2 }
        assertEquals(listOf("а"), engine.decoders[0].context)
        assertEquals(listOf("б", "в"), engine.decoders[1].context)
        assertTrue(engine.decoders[0].closed)
        assertTrue(rec.events.any { it.startsWith("f:") })
    }

    @Test
    fun contextCanBeRemoved() {
        val rec = Recorder()
        val audio = FakeAudio(paced = true)
        val engine = TestEngine(rec, audio, finalAt = setOf(2)).tracked()
        engine.start(dir, listOf("а"))
        engine.setContext(null)
        audio.step(2)
        await { engine.decoders.size == 2 }
        assertNull(engine.decoders[1].context)
    }

    @Test
    fun endlessSpeechStillGetsTheNewHintAfterTwentySeconds() {
        val rec = Recorder()
        var now = 0L
        val clockTick = AtomicInteger()
        val engine = TestEngine(rec, FakeAudio(), clock = { now + clockTick.get() * 1000L }).tracked() // пауз в речи нет
        engine.start(dir, listOf("а"))
        await { engine.chunk.get() >= 3 }
        engine.setContext(listOf("новая"))
        // «проходит» 25 секунд без единой паузы
        clockTick.set(25)
        await { engine.decoders.size == 2 }
        assertEquals(listOf("новая"), engine.decoders[1].context)
        // фраза закрыта принудительно: текст не потерян
        assertTrue(rec.events.any { it.startsWith("f:слово") })
    }

    @Test
    fun brokenEngineIsNotAMicrophoneError() {
        val rec = Recorder()
        val engine = TestEngine(rec, FakeAudio(), loadFailure = "нет библиотеки").tracked()
        engine.start(dir, null)
        await { rec.events.any { it.startsWith("failure:") } }
        assertEquals(listOf("failure:нет библиотеки"), rec.events.filter { it.startsWith("failure:") || it.startsWith("error:") })
        await { !engine.isRunning }
    }

    @Test
    fun decoderCreationFailureIsAnEngineFailureToo() {
        val rec = Recorder()
        val engine = TestEngine(rec, FakeAudio(), decoderFailure = "не хватило памяти").tracked()
        engine.start(dir, null)
        await { rec.events.any { it.startsWith("failure:") } }
        assertTrue(rec.events.none { it.startsWith("error:") })
    }

    /** Часы, у которых обработка каждого блока «длится» [perChunkMs] мс (два обращения на блок: до и после). */
    private fun slowClock(perChunkMs: Long): () -> Long {
        val t = java.util.concurrent.atomic.AtomicLong()
        return { t.addAndGet(perChunkMs * 1_000_000L) }
    }

    @Test
    fun engineThatCannotKeepUpWithTheAudioIsReportedAsFailing() {
        val rec = Recorder()
        // блок звука — 100 мс, а обрабатывается 95 мс подряд: через несколько секунд движок признаётся не тянущим
        val engine = TestEngine(rec, FakeAudio(), nanoClock = slowClock(95)).tracked()
        engine.start(dir, null)
        await { rec.events.any { it.startsWith("failure:") } }
        assertEquals(listOf("failure:телефон не успевает обрабатывать звук"), rec.events.filter { it.startsWith("failure:") })
        assertTrue("слишком рано: ${engine.chunk.get()} блоков", engine.chunk.get() >= 60)
        await { !engine.isRunning }
        assertTrue(rec.events.none { it.startsWith("error:") })
    }

    @Test
    fun aFastEngineIsNeverAccusedOfBeingSlow() {
        val rec = Recorder()
        val engine = TestEngine(rec, FakeAudio(), nanoClock = slowClock(30)).tracked()
        engine.start(dir, null)
        await { engine.chunk.get() >= 150 }
        assertTrue(engine.isRunning)
        assertTrue(rec.events.none { it.startsWith("failure:") })
    }

    @Test
    fun microphoneProblemsAreReportedAsErrors() {
        val rec = Recorder()
        val engine = TestEngine(rec, FakeAudio(failOpen = "Не удалось открыть микрофон")).tracked()
        engine.start(dir, null)
        await { rec.events.any { it.startsWith("error:") } }
        assertTrue(rec.events.contains("error:Не удалось открыть микрофон"))
        assertTrue(rec.events.none { it.startsWith("failure:") })
        await { rec.events.contains("listen:false") }
    }

    @Test
    fun readErrorInTheMiddleStopsAndClosesEverything() {
        val rec = Recorder()
        val audio = FakeAudio(failReadAt = 5)
        val engine = TestEngine(rec, audio).tracked()
        engine.start(dir, null)
        await { rec.events.any { it.startsWith("error:") } }
        await { audio.closed }
        assertTrue(engine.decoders.single().closed)
        assertFalse(engine.isRunning)
    }

    @Test
    fun stopThenStartAgainReusesTheLoadedModel() {
        val rec = Recorder()
        val engine = TestEngine(rec, FakeAudio()).tracked()
        engine.start(dir, null)
        await { engine.chunk.get() >= 2 }
        engine.stop()
        assertFalse(engine.isRunning)
        engine.start(dir, listOf("снова"))
        await { engine.decoders.size == 2 }
        assertEquals(1, engine.loads.get())
        assertEquals(listOf("снова"), engine.decoders[1].context)
        assertEquals(0, engine.unloads.get())
    }

    @Test
    fun releaseUnloadsTheModelExactlyOnce() {
        val rec = Recorder()
        val engine = TestEngine(rec, FakeAudio()).tracked()
        engine.start(dir, null)
        await { engine.chunk.get() >= 2 }
        engine.release()
        assertEquals(1, engine.unloads.get())
        engine.release()
        assertEquals("повторное освобождение безвредно", 1, engine.unloads.get())
    }

    @Test
    fun releaseWithoutEverStartingIsHarmless() {
        val engine = TestEngine(Recorder(), FakeAudio()).tracked()
        engine.release()
        assertEquals(0, engine.unloads.get())
    }

    @Test
    fun startAfterReleaseLoadsTheModelAgain() {
        val rec = Recorder()
        val engine = TestEngine(rec, FakeAudio()).tracked()
        engine.start(dir, null)
        await { engine.chunk.get() >= 2 }
        engine.release()
        engine.start(dir, null)
        await { engine.decoders.size == 2 }
        assertEquals(2, engine.loads.get())
    }
}
