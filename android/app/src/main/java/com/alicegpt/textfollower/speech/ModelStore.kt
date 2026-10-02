package com.alicegpt.textfollower.speech

import android.content.Context
import java.io.File

/** Место, где лежит модель распознавания движка: вшита в APK, скачана или распакована во внутреннее хранилище. */
interface ModelStore {
    val kind: EngineKind

    /** Сколько мегабайт придётся скачать, если модели нет в APK. */
    val downloadMb: Int

    fun modelDir(context: Context): File

    fun isInstalled(context: Context): Boolean

    /** Вшита ли модель в APK (иначе её придётся скачать). */
    fun isBundled(context: Context): Boolean

    /** Блокирующая операция — вызывать из фонового потока. [onProgress] получает долю 0..1. */
    fun install(context: Context, onProgress: (Float) -> Unit): File

    companion object {
        fun of(kind: EngineKind): ModelStore = when (kind) {
            EngineKind.NEURAL -> NeuralModelStore
            EngineKind.VOSK -> ModelInstaller
        }
    }
}
