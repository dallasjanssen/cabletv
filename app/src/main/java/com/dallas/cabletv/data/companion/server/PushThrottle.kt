package com.dallas.cabletv.data.companion.server

/**
 * Turns a burst of changes into at most one send per [minIntervalMs]. The first change sends at
 * once. Changes that come too soon are folded into one send when the interval is over, which sees
 * the latest state. [schedule] runs a task after a delay, and [send] does the sending. Safe to
 * call from any thread.
 */
class PushThrottle(
    private val minIntervalMs: Long,
    private val now: () -> Long,
    private val schedule: (delayMs: Long, task: Runnable) -> Unit,
    private val send: () -> Unit
) {
    private val lock = Any()
    private var lastSentAt = Long.MIN_VALUE / 2
    private var waiting = false

    /** Something changed. */
    fun poke() {
        synchronized(lock) {
            if (waiting) return
            val wait = lastSentAt + minIntervalMs - now()
            if (wait > 0) {
                waiting = true
                schedule(wait, Runnable { fire() })
                return
            }
            lastSentAt = now()
        }
        send()
    }

    private fun fire() {
        synchronized(lock) {
            waiting = false
            lastSentAt = now()
        }
        send()
    }
}
