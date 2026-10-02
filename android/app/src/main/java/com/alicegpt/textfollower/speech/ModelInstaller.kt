package com.alicegpt.textfollower.speech

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Распаковывает офлайн-модель Vosk из assets во внутреннее хранилище при первом запуске.
 * Распакованная модель занимает около 90 МБ.
 */
object ModelInstaller {
    const val MODEL_NAME = "vosk-model-small-ru-0.22"
    private const val ASSET = "$MODEL_NAME.zip"
    private const val MARKER = ".installed"

    fun modelDir(context: Context) = File(context.filesDir, "model/$MODEL_NAME")

    fun isInstalled(context: Context) = File(modelDir(context), MARKER).exists()

    /** Блокирующая операция — вызывать из фонового потока. [onProgress] получает долю 0..1. */
    fun install(context: Context, onProgress: (Float) -> Unit): File {
        val target = modelDir(context)
        if (isInstalled(context)) return target

        val root = File(context.filesDir, "model")
        root.deleteRecursively()
        root.mkdirs()

        val total = context.assets.openFd(ASSET).use { it.length }.coerceAtLeast(1)
        var read = 0L
        var lastReport = -1f
        context.assets.open(ASSET).use { raw ->
            val counting = object : java.io.FilterInputStream(raw) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) read += n
                    return n
                }
            }
            ZipInputStream(counting.buffered()).use { zip ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(root, entry.name)
                    // защита от «zip slip»
                    require(out.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Некорректный путь в архиве" }
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
                    val p = (read.toFloat() / total).coerceAtMost(1f)
                    if (p - lastReport >= 0.01f) {
                        lastReport = p
                        onProgress(p)
                    }
                }
            }
        }
        File(target, MARKER).writeText("ok")
        onProgress(1f)
        return target
    }
}
