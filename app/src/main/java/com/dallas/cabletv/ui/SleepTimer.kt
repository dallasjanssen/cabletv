package com.dallas.cabletv.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Stops live playback after a chosen number of minutes.
 * The deadline lives here, not on the live screen, so opening Home does not start the wait over.
 */
object SleepTimer {
    /** Set once the countdown reaches zero. Cleared when a stream starts again. */
    var fired by mutableStateOf(false)
        private set

    val message: String? get() = if (fired) "Sleep timer" else null

    private val handler = Handler(Looper.getMainLooper())
    private var armedMinutes = 0
    private var endsAt = 0L
    private var stopPlayback: (() -> Unit)? = null

    private val fire = Runnable {
        armedMinutes = 0
        endsAt = 0L
        fired = true
        stopPlayback?.invoke()
    }

    /** [stop] releases the live player. The player installs this once, when it is created. */
    fun attach(stop: () -> Unit) {
        stopPlayback = stop
    }

    /**
     * Starts the countdown when a live stream actually starts. The same length does not reset
     * the clock, so stepping channels keeps the time already counted. Zero cancels.
     */
    fun onPlaybackStarted(minutes: Int) {
        fired = false
        val chosen = minutes.coerceAtLeast(0)
        if (chosen == 0) {
            cancelCountdown()
            return
        }
        if (chosen == armedMinutes && endsAt > SystemClock.elapsedRealtime()) return
        arm(chosen)
    }

    /** The options bar picked a length. That choice starts from now. Zero cancels. */
    fun choose(minutes: Int) {
        if (minutes <= 0) {
            cancelCountdown()
            return
        }
        arm(minutes)
    }

    private fun arm(minutes: Int) {
        handler.removeCallbacks(fire)
        armedMinutes = minutes
        endsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        handler.postDelayed(fire, minutes * 60_000L)
    }

    private fun cancelCountdown() {
        handler.removeCallbacks(fire)
        armedMinutes = 0
        endsAt = 0L
    }
}

/** The sleep button walks these lengths and then returns to off. */
fun nextSleepMinutes(current: Int): Int {
    val steps = intArrayOf(0, 30, 60, 90, 120)
    for (step in steps) if (step > current) return step
    return 0
}
