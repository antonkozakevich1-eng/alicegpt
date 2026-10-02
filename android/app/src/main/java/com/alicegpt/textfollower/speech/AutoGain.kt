package com.alicegpt.textfollower.speech

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Программное усиление тихой речи: медленная автоматическая регулировка уровня
 * плюс ограничитель, чтобы громкие всплески не искажались.
 *
 * [sensitivity] (0..100) задаёт, насколько сильно можно усиливать: чем выше, тем тише голос
 * ещё будет «подтянут» (при этом растёт и шум).
 */
class AutoGain {

    @Volatile
    var sensitivity: Int = 50

    private var gain = 1f
    private var speechLevel = 0f          // оценка уровня речи (RMS, 0..1)
    private var noiseFloor = 0.002f       // оценка уровня шума (RMS, 0..1)

    /** Усиливает [buf] на месте, возвращает уровень после усиления для индикатора (0..1). */
    fun process(buf: ShortArray, n: Int): Float {
        if (n <= 0) return 0f
        val rms = rms(buf, n)

        // Шум: быстро опускаемся к тишине в паузах, очень медленно поднимаемся при росте фона.
        noiseFloor = if (rms < noiseFloor) rms * 0.5f + noiseFloor * 0.5f else noiseFloor * 1.002f + 1e-6f
        noiseFloor = noiseFloor.coerceIn(0.0002f, 0.05f)

        val isSpeech = rms > noiseFloor * 2.5f && rms > 0.0012f
        if (isSpeech) speechLevel = if (speechLevel == 0f) rms else speechLevel * 0.8f + rms * 0.2f

        val s = sensitivity.coerceIn(0, 100) / 100f
        val maxGain = 2f + 38f * s                    // 2× … 40×
        val target = if (speechLevel > 0f) (TARGET_RMS / speechLevel).coerceIn(1f, maxGain) else min(maxGain, 4f)
        // Усиление растёт медленно (не раздуваем шум в паузах) и падает быстро (чтобы не клиппировать).
        val rate = if (target < gain) 0.5f else if (isSpeech) 0.15f else 0.02f
        gain += (target - gain) * rate
        gain = gain.coerceIn(1f, maxGain)

        var sumSq = 0.0
        for (i in 0 until n) {
            val v = limit(buf[i] * gain)
            buf[i] = v.toInt().toShort()
            sumSq += (v / 32768.0) * (v / 32768.0)
        }
        val outRms = sqrt(sumSq / n).toFloat()
        return levelOf(outRms)
    }

    fun reset() {
        gain = 1f
        speechLevel = 0f
    }

    private fun rms(buf: ShortArray, n: Int): Float {
        var s = 0.0
        for (i in 0 until n) {
            val v = buf[i] / 32768.0
            s += v * v
        }
        return sqrt(s / n).toFloat()
    }

    /** Мягкое ограничение вблизи полной шкалы. */
    private fun limit(v: Float): Float {
        val a = abs(v)
        if (a <= KNEE) return v
        val over = a - KNEE
        val soft = KNEE + (32767f - KNEE) * (1f - 1f / (1f + over / (32767f - KNEE)))
        return if (v < 0) -min(soft, 32767f) else min(soft, 32767f)
    }

    private fun levelOf(rms: Float): Float {
        val db = 20f * log10(max(rms, 1e-5f))
        return ((db + 60f) / 50f).coerceIn(0f, 1f)
    }

    private companion object {
        const val TARGET_RMS = 0.08f
        const val KNEE = 24000f
    }
}
