package dev.kb896.aiusage.rear

/**
 * When a video run's frames are due, in uptime milliseconds: [due] of a frame's wall offset
 * from the run's start. Paused, every later frame moves on by the time paused; resumed
 * having never started, it starts then — the preview's first ask is to carry on looping,
 * and a clock that had never been set put every frame at the epoch.
 */
class RunClock {
    private var base: Long? = null
    private var pausedAt: Long? = null

    fun start(now: Long) {
        base = now
        pausedAt = null
    }

    /** A run picked up part-way: the frame [offsetMs] into it is due [now]. */
    fun startFrom(now: Long, offsetMs: Long) = start(now - offsetMs)

    fun pause(now: Long) {
        if (pausedAt == null) pausedAt = now
    }

    fun resume(now: Long) {
        val b = base ?: return start(now)
        pausedAt?.let { base = b + (now - it) }
        pausedAt = null
    }

    /** A loop going round: its next run is due from [base]. */
    fun restartAt(base: Long) {
        this.base = base
        pausedAt = null
    }

    fun due(offsetMs: Long): Long = (base ?: 0L) + offsetMs
}
