package com.alicegpt.textfollower.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

class NeuralModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val files = listOf(
        NeuralModelStore.RemoteFile("big.onnx", "https://example.invalid/big", 200_000),
        NeuralModelStore.RemoteFile("small.txt", "https://example.invalid/small", 1_234),
    )

    private fun bytes(f: NeuralModelStore.RemoteFile, size: Long = f.size) = ByteArray(size.toInt()) { (it % 251).toByte() }

    @Test
    fun filesListMatchesTheGradleBuildScript() {
        // скрипт сборки вшивает те же файлы в APK, поэтому имена, адреса и размеры должны совпадать
        val script = File("build.gradle.kts").readText()
        for (f in NeuralModelStore.FILES) {
            assertTrue("нет ${f.name} в build.gradle.kts", script.contains("\"${f.name}\""))
            assertTrue("нет адреса ${f.url} в build.gradle.kts", script.contains("\"${f.url}\""))
            assertTrue("нет размера ${f.size} у ${f.name}", script.contains(f.size.toString()))
        }
    }

    @Test
    fun everyModelFileTheEngineNeedsIsDownloaded() {
        val names = NeuralModelStore.FILES.map { it.name }.toSet()
        assertEquals(
            setOf(NeuralEngine.ENCODER, NeuralEngine.DECODER, NeuralEngine.JOINER, NeuralEngine.TOKENS, NeuralEngine.BPE_VOCAB),
            names,
        )
        assertTrue(NeuralModelStore.FILES.all { it.url.startsWith("https://") })
        assertTrue(NeuralModelStore.FILES.sumOf { it.size } in 27_000_000..29_000_000)
    }

    @Test
    fun installsFilesChecksSizesAndWritesTheMarker() {
        val target = File(tmp.newFolder(), "model")
        val progress = ArrayList<Float>()
        NeuralModelStore.installInto(target, files, { progress += it }) { ByteArrayInputStream(bytes(it)) }
        for (f in files) assertEquals(f.size, File(target, f.name).length())
        assertTrue(File(target, ".installed").exists())
        assertTrue(target.listFiles()!!.none { it.name.endsWith(".part") })
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1f, progress.last(), 0f)
    }

    @Test
    fun truncatedDownloadIsRejectedAndNothingIsLeftBehind() {
        val target = File(tmp.newFolder(), "model")
        try {
            NeuralModelStore.installInto(target, files, {}) { f ->
                ByteArrayInputStream(bytes(f, if (f.name == "small.txt") f.size - 1 else f.size))
            }
            fail("ожидали ошибку")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("small.txt"))
        }
        assertFalse("осталась полумодель", target.exists())
    }

    @Test
    fun networkErrorInTheMiddleCleansUp() {
        val target = File(tmp.newFolder(), "model")
        try {
            NeuralModelStore.installInto(target, files, {}) { f ->
                if (f.name == "small.txt") throw IOException("обрыв связи")
                ByteArrayInputStream(bytes(f))
            }
            fail("ожидали ошибку")
        } catch (e: IOException) {
            assertEquals("обрыв связи", e.message)
        }
        assertFalse(target.exists())
    }

    @Test
    fun streamsAreClosedAndAnOldBrokenInstallIsReplaced() {
        val target = File(tmp.newFolder(), "model")
        target.mkdirs()
        File(target, "stale.part").writeText("мусор от прошлой попытки")
        val closed = ArrayList<String>()
        NeuralModelStore.installInto(target, files, {}) { f ->
            object : FilterInputStream(ByteArrayInputStream(bytes(f))) {
                override fun close() {
                    closed += f.name
                    super.close()
                }
            } as InputStream
        }
        assertEquals(files.map { it.name }, closed)
        assertFalse(File(target, "stale.part").exists())
    }
}
