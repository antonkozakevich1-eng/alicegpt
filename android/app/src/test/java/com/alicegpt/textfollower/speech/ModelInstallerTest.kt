package com.alicegpt.textfollower.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun unpacksFilesAndReportsProgress() {
        val big = ByteArray(300_000).also { Random(1).nextBytes(it) }
        val data = zip("model/graph/a.bin" to big, "model/conf.txt" to "ok".toByteArray())
        val root = tmp.newFolder("root")
        val progress = ArrayList<Float>()
        ModelInstaller.unzip(ByteArrayInputStream(data), data.size.toLong(), root) { progress += it }

        assertArrayEquals(big, root.resolve("model/graph/a.bin").readBytes())
        assertEquals("ok", root.resolve("model/conf.txt").readText())
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(progress.last() > 0.9f)
    }

    @Test
    fun entriesOutsideTheTargetFolderAreRejected() {
        val data = zip("../evil.txt" to "x".toByteArray())
        val root = tmp.newFolder("safe")
        try {
            ModelInstaller.unzip(ByteArrayInputStream(data), data.size.toLong(), root) {}
            fail("ожидали отказ")
        } catch (e: IllegalArgumentException) {
            // так и должно быть
        }
        assertFalse(root.resolve("../evil.txt").exists())
    }
}

/** Потоковая распаковка настоящего архива модели (так же, как при скачивании из сети), если он есть после сборки. */
class ModelInstallerRealZipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun realModelArchiveUnpacksFromAPlainStream() {
        val zip = java.io.File("build/vosk-assets/${ModelInstaller.MODEL_NAME}.zip")
        org.junit.Assume.assumeTrue("архива модели нет", zip.exists())
        val root = tmp.newFolder("model")
        var last = 0f
        // чтение «как из сети»: без произвольного доступа к файлу
        zip.inputStream().use { ModelInstaller.unzip(it, zip.length(), root) { p -> last = p } }
        val dir = root.resolve(ModelInstaller.MODEL_NAME)
        for (f in listOf("am/final.mdl", "graph/Gr.fst", "graph/HCLr.fst", "ivector/final.ie", "conf/model.conf")) {
            assertTrue("нет $f", dir.resolve(f).length() > 0)
        }
        assertTrue(last > 0.95f)
    }
}
