package com.alicegpt.textfollower.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

/** Источник звука для движка: настоящий микрофон или подставной в тестах. */
interface AudioSource {
    /** Открывает и запускает запись; любая ошибка — исключение. */
    fun open()

    /** Блокирующее чтение; возвращает число прочитанных отсчётов. */
    fun read(buf: ShortArray): Int

    fun close()
}

/**
 * Микрофон для распознавания: источник VOICE_RECOGNITION, 16 кГц, моно, 16 бит.
 * Включает аппаратные AutomaticGainControl и NoiseSuppressor, если они есть.
 */
class AudioInput : AudioSource {
    private var record: AudioRecord? = null
    private val effects = ArrayList<AudioEffect>()

    /** Разрешение RECORD_AUDIO проверяет вызывающий. */
    @SuppressLint("MissingPermission")
    override fun open() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IllegalStateException("Микрофон недоступен")
        // Запас в секунду звука: пока распознаватель занят (например, пересборкой подсказки), запись не теряется.
        val bufBytes = maxOf(minBuf * 4, SAMPLE_RATE * 2)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes,
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("Не удалось открыть микрофон")
        }
        record = r
        val session = r.audioSessionId
        if (AutomaticGainControl.isAvailable()) addEffect(true) { AutomaticGainControl.create(session) }
        if (NoiseSuppressor.isAvailable()) addEffect(true) { NoiseSuppressor.create(session) }
        // Эхоподавитель при чтении вслух не нужен и может «съедать» речь.
        if (AcousticEchoCanceler.isAvailable()) addEffect(false) { AcousticEchoCanceler.create(session) }
        r.startRecording()
    }

    /** Аппаратные эффекты — необязательное улучшение: на некоторых телефонах они падают, и это не должно ронять запись. */
    private fun addEffect(enabled: Boolean, create: () -> AudioEffect?) {
        try {
            create()?.let {
                it.enabled = enabled
                effects += it
            }
        } catch (_: Throwable) {
        }
    }

    override fun read(buf: ShortArray): Int {
        val n = record?.read(buf, 0, buf.size) ?: -1
        if (n < 0) throw IllegalStateException("Ошибка чтения микрофона ($n)")
        return n
    }

    override fun close() {
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        effects.forEach { try { it.release() } catch (_: Exception) {} }
        effects.clear()
        record?.release()
        record = null
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /** 100 мс звука. */
        const val CHUNK_SAMPLES = 1600
    }
}
