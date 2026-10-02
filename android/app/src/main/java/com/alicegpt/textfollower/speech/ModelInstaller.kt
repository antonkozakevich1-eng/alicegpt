package com.alicegpt.textfollower.speech

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Готовит офлайн-модель Vosk: распаковывает её во внутреннее хранилище при первом запуске
 * (распакованная модель занимает около 90 МБ).
 *
 * Если модель вшита в APK, берёт её из assets. В облегчённой сборке (`-PbundleModel=false`) один раз
 * скачивает архив с сайта Vosk и распаковывает его на лету; после этого интернет не нужен.
 */
object ModelInstaller {
    const val MODEL_NAME = "vosk-model-small-ru-0.22"
    const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    private const val ASSET = "$MODEL_NAME.zip"
    private const val MARKER = ".installed"
    private const val DOWNLOAD_SIZE_FALLBACK = 46_236_750L

    fun modelDir(context: Context) = File(context.filesDir, "model/$MODEL_NAME")

    fun isInstalled(context: Context) = File(modelDir(context), MARKER).exists()

    /** Вшита ли модель в APK (иначе её придётся скачать). */
    fun isBundled(context: Context): Boolean = try {
        context.assets.list("")?.contains(ASSET) == true
    } catch (e: IOException) {
        false
    }

    /** Блокирующая операция — вызывать из фонового потока. [onProgress] получает долю 0..1. */
    fun install(context: Context, onProgress: (Float) -> Unit): File {
        val target = modelDir(context)
        if (isInstalled(context)) return target

        val root = File(context.filesDir, "model")
        root.deleteRecursively()
        root.mkdirs()

        if (isBundled(context)) {
            val total = context.assets.openFd(ASSET).use { it.length }
            context.assets.open(ASSET).use { unzip(it, total, root, onProgress) }
        } else {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("Не удалось скачать модель: HTTP ${conn.responseCode}")
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: DOWNLOAD_SIZE_FALLBACK
                conn.inputStream.use { unzip(it, total, root, onProgress) }
            } finally {
                conn.disconnect()
            }
        }
        File(target, MARKER).writeText("ok")
        onProgress(1f)
        return target
    }

    /** Распаковывает zip из потока в [root]; прогресс считается по прочитанным сжатым байтам из [total]. */
    internal fun unzip(raw: InputStream, total: Long, root: File, onProgress: (Float) -> Unit) {
        var read = 0L
        var lastReport = -1f
        val counting = object : FilterInputStream(raw) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) read += n
                return n
            }
        }
        val rootPath = root.canonicalPath + File.separator
        ZipInputStream(counting.buffered()).use { zip ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(root, entry.name)
                // защита от «zip slip»
                require(out.canonicalPath.startsWith(rootPath)) { "Некорректный путь в архиве" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                        }
                    }
                }
                val p = (read.toFloat() / total.coerceAtLeast(1)).coerceAtMost(1f)
                if (p - lastReport >= 0.01f) {
                    lastReport = p
                    onProgress(p)
                }
            }
        }
    }
}
