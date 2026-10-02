package com.alicegpt.textfollower.tracking

import com.alicegpt.textfollower.testutil.override
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

    private interface Trk {
        val position: Int
        val searching: Boolean
        fun seek(i: Int)
        fun onPartial(t: String): Int?
        fun onFinal(t: String): Int?
    }

    private val textRe = Regex("\"text\": \"(.*?)\"")
    private fun int(line: String, key: String) = Regex("\"$key\": (-?\\d+)").find(line)?.groupValues?.get(1)?.toInt() ?: 0

    @Test
    fun run() {
        val dir = System.getenv("EXP_DIR")
        assumeTrue("EXP_DIR не задан", dir != null)
        val docs = HashMap<String, Doc>()
        fun docFor(name: String) = docs.getOrPut(name) {
            val file = if (name == "odyssey") File("src/main/assets/odyssey_zhukovsky.txt") else File(dir, "docs/$name.txt")
            Doc.parse(TextLoader.load(file.readBytes()))
        }
        val eventsName = System.getenv("EXP_EVENTS") ?: "events"
        val out = StringBuilder("name\tmoves\tbad\tfalseFwd\tmarks\tmarksOk\tendPos\tendTruth\tsearchOn\n")
        val files = File(dir, eventsName).listFiles { f -> f.name.endsWith(".jsonl") }!!.sortedBy { it.name }
        val suffix = System.getenv("EXP_SUFFIX") ?: ""
        val traceDir = File(dir, eventsName.replace("events", "traces") + suffix).also { it.mkdirs() }
        for (f in files) {
            val variant = f.name.substringBefore("__")
            val docName = Regex("\"doc\": \"(\\w+)\"").find(f.useLines { it.first() })?.groupValues?.get(1) ?: "odyssey"
            val doc = docFor(docName)
            val base = if (variant.startsWith("zip")) TrackerConfig.NEURAL else TrackerConfig.VOSK
            val legacy = System.getenv("EXP_TRACKER") == "legacy"
            val tracker: Trk = if (legacy) {
                LegacyTextTracker(doc).let { old ->
                    object : Trk {
                        override val position get() = old.position
                        override val searching get() = false
                        override fun seek(i: Int) = old.seek(i)
                        override fun onPartial(t: String) = old.onPartial(t)?.to
                        override fun onFinal(t: String) = old.onFinal(t)?.to
                    }
                }
            } else {
                TextTracker(doc, base.override(System.getenv("EXP_CFG"))).let { nw ->
                    object : Trk {
                        override val position get() = nw.position
                        override val searching get() = nw.searchMode
                        override fun seek(i: Int) = nw.seek(i)
                        override fun onPartial(t: String) = nw.onPartial(t)?.to
                        override fun onFinal(t: String) = nw.onFinal(t)?.to
                    }
                }
            }
            val trace = StringBuilder()
            var moves = 0; var bad = 0; var falseFwd = 0; var marks = 0; var marksOk = 0; var endTruth = 0; var start = 0
            var searchOn = 0; var wasSearching = false
            for (line in f.readLines()) {
                val k = Regex("\"k\": \"(\\w+)\"").find(line)!!.groupValues[1]
                when (k) {
                    "start" -> { start = int(line, "pos"); tracker.seek(start) }
                    "p", "f" -> {
                        val text = textRe.find(line)?.groupValues?.get(1) ?: ""
                        val m = if (k == "p") tracker.onPartial(text) else tracker.onFinal(text)
                        trace.append(tracker.position).append('\n')
                        if (tracker.searching && !wasSearching) searchOn++
                        wasSearching = tracker.searching
                        val le = int(line, "le")
                        if (m != null) {
                            moves++
                            if (abs(m - (le + 1)) > 25) bad++ // сдвиг не туда, где реально читают
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
            File(traceDir, f.name.removeSuffix(".jsonl") + ".txt").writeText(trace.toString())
            out.append("${f.name.removeSuffix(".jsonl")}\t$moves\t$bad\t$falseFwd\t$marks\t$marksOk\t${tracker.position}\t$endTruth\t$searchOn\n")
        }
        File(dir, (if (eventsName == "events") "results" else "results_${eventsName.removePrefix("events_")}") + suffix + ".tsv").writeText(out.toString())
        println("EXP wrote ${files.size} rows")
    }
}
