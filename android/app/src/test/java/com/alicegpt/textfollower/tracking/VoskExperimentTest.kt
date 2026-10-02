package com.alicegpt.textfollower.tracking

import com.alicegpt.textfollower.text.Doc
import com.alicegpt.textfollower.text.TextLoader
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Разовый эксперимент (не часть обычного набора тестов): прогоняет записанные события Vosk
 * (partial/final) из каталога EXP_DIR/events через трекер и пишет метрики в EXP_DIR/results.tsv.
 */
class VoskExperimentTest {

    private val textRe = Regex("\"text\": \"(.*?)\"")
    private fun int(line: String, key: String) = Regex("\"$key\": (-?\\d+)").find(line)?.groupValues?.get(1)?.toInt() ?: 0

    @Test
    fun run() {
        val dir = System.getenv("EXP_DIR")
        assumeTrue("EXP_DIR не задан", dir != null)
        val doc = Doc.parse(TextLoader.load(File("src/main/assets/odyssey_zhukovsky.txt").readBytes()))
        val out = StringBuilder("name\tmoves\tbad\tfalseFwd\tmarks\tmarksOk\tendPos\tendTruth\n")
        val files = File(dir, "events").listFiles { f -> f.name.endsWith(".jsonl") }!!.sortedBy { it.name }
        for (f in files) {
            val tracker = TextTracker(doc)
            var moves = 0; var bad = 0; var falseFwd = 0; var marks = 0; var marksOk = 0; var endTruth = 0; var start = 0
            for (line in f.readLines()) {
                val k = Regex("\"k\": \"(\\w+)\"").find(line)!!.groupValues[1]
                when (k) {
                    "start" -> { start = int(line, "pos"); tracker.seek(start) }
                    "p", "f" -> {
                        val text = textRe.find(line)?.groupValues?.get(1) ?: ""
                        val m = if (k == "p") tracker.onPartial(text) else tracker.onFinal(text)
                        val le = int(line, "le")
                        if (m != null) {
                            moves++
                            if (abs(m.to - (le + 1)) > 25) bad++ // сдвиг не туда, где реально читают
                        }
                        if (tracker.position > le + 2) falseFwd++
                    }
                    "mark" -> {
                        marks++
                        val le = int(line, "le")
                        if (tracker.position in (le + 1 - 3)..(le + 2)) marksOk++
                    }
                    "end" -> endTruth = int(line, "le") + 1
                }
            }
            out.append("${f.name.removeSuffix(".jsonl")}\t$moves\t$bad\t$falseFwd\t$marks\t$marksOk\t${tracker.position}\t$endTruth\n")
        }
        File(dir, "results.tsv").writeText(out.toString())
        println("EXP wrote ${files.size} rows")
    }
}
