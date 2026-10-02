package com.alicegpt.textfollower.text

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Сверка разбора настоящего файла .mht с готовым текстом из assets.
 * Запускается, только если задана переменная окружения MHT_PATH (сам файл в репозитории не хранится).
 */
class RealFilesTest {

    @Test
    fun mhtGivesTheSameWordsAsTheBundledText() {
        val path = System.getenv("MHT_PATH")
        assumeTrue("MHT_PATH не задан", path != null && File(path).exists())
        val bundled = Doc.parse(TextLoader.load(File("src/main/assets/odyssey_zhukovsky.txt").readBytes()))
        val fromMht = Doc.parse(TextLoader.load(File(path!!).readBytes()))
        // У страницы есть титульный блок (автор, название, источник) — он идёт в секцию «Начало».
        val first = fromMht.sections.first { it.title != "Начало" }
        assertEquals("Начало", fromMht.sections.first().title)
        assertEquals(bundled.norm.toList(), fromMht.norm.drop(first.firstWord))
        assertEquals(bundled.sections.filter { it.inToc }.map { it.title }, fromMht.sections.filter { it.inToc && it.title != "Начало" }.map { it.title })
        assertEquals(bundled.markerRanges.size, fromMht.markerRanges.size)
    }
}
