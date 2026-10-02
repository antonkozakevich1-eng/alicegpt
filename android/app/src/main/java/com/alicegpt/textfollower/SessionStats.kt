package com.alicegpt.textfollower

/**
 * Статистика чтения: сколько слов прочитано, скорость и время.
 *
 * Считаются только обычные продвижения вперёд; перескок в другое место ([TextTracker.Move.far])
 * и ручной переход не добавляют «прочитанных» слов. Время идёт только пока прослушивание включено.
 */
class SessionStats(private val clock: () -> Long = System::currentTimeMillis) {

    var wordsRead: Int = 0
        private set

    private var activeMs = 0L
    private var runningSince = 0L
    private val recent = ArrayDeque<Pair<Long, Int>>() // (время, слов) для скорости за последнюю минуту

    val isRunning: Boolean get() = runningSince != 0L

    fun start() {
        if (runningSince == 0L) runningSince = clock()
    }

    fun pause() {
        if (runningSince != 0L) {
            activeMs += clock() - runningSince
            runningSince = 0L
        }
    }

    /** Прослушивание шло [activeMillis] мс. */
    val activeMillis: Long get() = activeMs + if (runningSince != 0L) clock() - runningSince else 0L

    fun onForwardMove(words: Int) {
        if (words <= 0 || words > MAX_STEP) return
        wordsRead += words
        recent.addLast(clock() to words)
    }

    /** Слов в минуту: за последнюю минуту чтения, а пока она не прошла — за всё время. */
    fun wordsPerMinute(): Int {
        val now = clock()
        while (recent.isNotEmpty() && now - recent.first().first > WINDOW_MS) recent.removeFirst()
        val active = activeMillis
        if (active < MIN_MS || wordsRead == 0) return 0
        val windowMs = minOf(active, WINDOW_MS)
        val words = if (active <= WINDOW_MS) wordsRead else recent.sumOf { it.second }
        return (words * 60_000L / windowMs).toInt()
    }

    fun reset() {
        wordsRead = 0
        activeMs = 0
        runningSince = if (isRunning) clock() else 0L
        recent.clear()
    }

    private companion object {
        const val MAX_STEP = 40       // продвижение дальше считается перескоком, а не чтением
        const val WINDOW_MS = 60_000L
        const val MIN_MS = 5_000L
    }
}
