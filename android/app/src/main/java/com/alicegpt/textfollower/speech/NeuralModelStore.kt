package com.alicegpt.textfollower.speech

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Нейросетевая модель: пять небольших файлов (≈28 МБ). Если вшита в APK (assets/neural-ru) — копируется оттуда,
 * иначе один раз скачивается с Hugging Face. Размеры файлов проверяются.
 */
object NeuralModelStore : ModelStore {
    override val kind = EngineKind.NEURAL
    override val downloadMb = 28

    private const val DIR_NAME = "neural-ru-streaming-zipformer-small-2025-08-16"
    const val ASSET_DIR = "neural-ru"
    private const val MARKER = ".installed"

    /** Файл модели: имя, откуда скачать и ожидаемый размер в байтах. */
    class RemoteFile(val name: String, val url: String, val size: Long)

    private const val SHERPA_REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/main"
    private const val VOSK_REPO = "https://huggingface.co/alphacep/vosk-model-small-streaming-ru/resolve/main"

    val FILES = listOf(
        RemoteFile(NeuralEngine.ENCODER, "$SHERPA_REPO/encoder.int8.onnx", 26_214_060),
        RemoteFile(NeuralEngine.DECODER, "$SHERPA_REPO/decoder.onnx", 2_093_080),
        RemoteFile(NeuralEngine.JOINER, "$SHERPA_REPO/joiner.int8.onnx", 259_417),
        RemoteFile(NeuralEngine.TOKENS, "$SHERPA_REPO/tokens.txt", 6_388),
        RemoteFile(NeuralEngine.BPE_VOCAB, "$VOSK_REPO/lang/unigram_500.vocab", 8_891),
    )

    override fun modelDir(context: Context) = File(context.filesDir, "model/$DIR_NAME")

    override fun isInstalled(context: Context) = File(modelDir(context), MARKER).exists()

    override fun isBundled(context: Context): Boolean = try {
        val present = context.assets.list(ASSET_DIR)?.toSet() ?: emptySet()
        FILES.all { it.name in present }
    } catch (e: IOException) {
        false
    }

    override fun install(context: Context, onProgress: (Float) -> Unit): File {
        val target = modelDir(context)
        if (isInstalled(context)) return target
        val bundled = isBundled(context)
        installInto(target, FILES, onProgress) { f ->
            if (bundled) context.assets.open("$ASSET_DIR/${f.name}") else openRemote(f)
        }
        return target
    }

    /**
     * Кладёт файлы [files] в [target], получая каждый через [open]; размер каждого файла проверяется,
     * при любой ошибке каталог очищается, чтобы не остался «полумодели».
     */
    internal fun installInto(target: File, files: List<RemoteFile>, onProgress: (Float) -> Unit, open: (RemoteFile) -> InputStream) {
        target.deleteRecursively()
        target.mkdirs()
        val total = files.sumOf { it.size }.toFloat()
        var done = 0L
        var lastReport = -1f
        fun report(bytes: Long) {
            val p = ((done + bytes) / total).coerceAtMost(1f)
            if (p - lastReport >= 0.01f) {
                lastReport = p
                onProgress(p)
            }
        }
        try {
            for (f in files) {
                val part = File(target, f.name + ".part")
                open(f).use { input -> copy(input, part) { report(it) } }
                if (part.length() != f.size) {
                    throw IOException("Файл ${f.name} получен не полностью: ${part.length()} из ${f.size} байт")
                }
                if (!part.renameTo(File(target, f.name))) throw IOException("Не удалось сохранить ${f.name}")
                done += f.size
            }
            File(target, MARKER).writeText("ok")
        } catch (e: Throwable) {
            target.deleteRecursively()
            throw e
        }
        onProgress(1f)
    }

    /** Поток скачиваемого файла; соединение закрывается вместе с потоком. */
    private fun openRemote(f: RemoteFile): InputStream {
        val conn = URL(f.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Не удалось скачать ${f.name}: HTTP ${conn.responseCode}")
            }
        } catch (e: Throwable) {
            conn.disconnect()
            throw e
        }
        return object : FilterInputStream(conn.inputStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    private fun copy(input: InputStream, to: File, progress: (Long) -> Unit) {
        FileOutputStream(to).use { out ->
            val buf = ByteArray(64 * 1024)
            var read = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                read += n
                progress(read)
            }
        }
    }
}
