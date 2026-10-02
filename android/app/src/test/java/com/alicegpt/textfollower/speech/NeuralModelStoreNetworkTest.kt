package com.alicegpt.textfollower.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Проверка настоящей загрузки (нужен интернет, поэтому только по запросу: `NET_TESTS=1 ./gradlew testDebugUnitTest`).
 * Скачивает два маленьких файла модели с Hugging Face: проверяет адреса, переадресацию на CDN и то, что размеры на сайте
 * не изменились (иначе приложение откажется принимать файл).
 */
class NeuralModelStoreNetworkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun smallModelFilesDownloadWithTheExpectedSizes() {
        assumeTrue("NET_TESTS не задан", System.getenv("NET_TESTS") != null)
        val small = NeuralModelStore.FILES.filter { it.size < 100_000 }
        assertTrue(small.size >= 2)
        val target = File(tmp.newFolder(), "model")
        val progress = ArrayList<Float>()
        NeuralModelStore.installInto(target, small, { progress += it }) { NeuralModelStore.openRemote(it) }
        for (f in small) assertEquals(f.name, f.size, File(target, f.name).length())
        assertEquals(1f, progress.last(), 0f)
        // tokens.txt — обычный текст «токен номер»; значит, пришёл сам файл, а не страница ошибки
        assertTrue(File(target, "tokens.txt").readText().lineSequence().first().startsWith("<blk>"))
    }
}
