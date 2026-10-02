package com.alicegpt.textfollower.testutil

import com.alicegpt.textfollower.tracking.TrackerConfig

/** Разовые эксперименты: «needMid=3,rho=0.5» меняет параметры трекера без пересборки. */
fun TrackerConfig.override(spec: String?): TrackerConfig {
    if (spec.isNullOrBlank()) return this
    var c = this
    for (kv in spec.split(',')) {
        val (k, v) = kv.split('=').map { it.trim() }
        c = when (k) {
            "rho" -> c.copy(rho = v.toDouble())
            "needNear" -> c.copy(needNear = v.toInt())
            "lostWords" -> c.copy(lostWords = v.toInt())
            "lockTh" -> c.copy(lockThreshold = v.toDouble())
            "sFar" -> c.copy(searchNeedFar = v.toInt())
            "sJump" -> c.copy(searchNeedJump = v.toInt())
            "sHuge" -> c.copy(searchNeedHuge = v.toInt())
            "sTau" -> c.copy(searchTauFar = v.toDouble())
            "sPJump" -> c.copy(searchJump = v.toDouble())
            "needMid" -> c.copy(needMid = v.toInt())
            "needFar" -> c.copy(needFar = v.toInt())
            "needJump" -> c.copy(needJump = v.toInt())
            "needHuge" -> c.copy(needHuge = v.toInt())
            "tauMove" -> c.copy(tauMove = v.toDouble())
            "tauMid" -> c.copy(tauMid = v.toDouble())
            "tauFar" -> c.copy(tauFar = v.toDouble())
            "pStop" -> c.copy(pStop = v.toDouble())
            "pStart" -> c.copy(pStart = v.toDouble())
            "pSkip" -> c.copy(pSkip = v.toDouble())
            "pJump" -> c.copy(pJump = v.toDouble())
            "adv0" -> c.copy(advance = c.advance.copyOf().also { it[0] = v.toDouble() })
            "adv2" -> c.copy(advance = c.advance.copyOf().also { it[2] = v.toDouble() })
            "adv3" -> c.copy(advance = c.advance.copyOf().also { it[3] = v.toDouble() })
            else -> error("неизвестный параметр $k")
        }
    }
    return c
}
