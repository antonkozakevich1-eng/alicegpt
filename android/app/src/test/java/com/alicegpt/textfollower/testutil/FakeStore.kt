package com.alicegpt.textfollower.testutil

import android.content.Context
import com.alicegpt.textfollower.speech.EngineKind
import com.alicegpt.textfollower.speech.ModelStore
import java.io.File
import java.util.concurrent.CountDownLatch

/** Подставное хранилище модели: установка по сценарию — с ожиданием, сбоями и прогрессом. */
class FakeStore(
    override val kind: EngineKind,
    private val dir: File,
    var installed: Boolean = false,
    var bundled: Boolean = true,
    /** Сбои установки по порядку попыток; пока список не пуст, очередная попытка бросает первый из них. */
    val failures: MutableList<Throwable> = ArrayList(),
    /** Если задана, установка ждёт, пока тест её не отпустит. */
    val gate: CountDownLatch? = null,
) : ModelStore {
    override val downloadMb = 28

    @Volatile
    var installCalls = 0

    override fun modelDir(context: Context) = dir

    override fun isInstalled(context: Context) = installed

    override fun isBundled(context: Context) = bundled

    override fun install(context: Context, onProgress: (Float) -> Unit): File {
        installCalls++
        onProgress(0.4f)
        gate?.await()
        synchronized(failures) { if (failures.isNotEmpty()) throw failures.removeAt(0) }
        onProgress(1f)
        installed = true
        return dir
    }
}
