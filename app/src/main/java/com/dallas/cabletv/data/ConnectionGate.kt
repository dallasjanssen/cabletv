package com.dallas.cabletv.data

/**
 * Caps how many streams may be open at once. Zero means the provider did not ask for a cap,
 * so every request is allowed and nothing is counted. The player publishes the saved cap when
 * it is built. The screen that actually opens a stream is what acquires and releases a slot.
 * A lower cap does not close a stream that is already open; the next acquire waits until
 * enough of those slots are released.
 */
object ConnectionGate {
    private val lock = Any()
    private var max = 0
    private var held = 0

    /** 0 means unlimited. A negative value is treated the same way. */
    fun setLimit(max: Int) {
        synchronized(lock) {
            this.max = if (max > 0) max else 0
        }
    }

    /**
     * Takes one slot. Returns immediately when the cap is unlimited, without counting the call,
     * so a later cap is not already full from streams that were never limited.
     */
    fun tryAcquire(): Boolean {
        synchronized(lock) {
            if (max == 0) return true
            if (held >= max) return false
            held += 1
            return true
        }
    }

    /** Gives a slot back. Extra releases, including ones after an unlimited acquire, do nothing. */
    fun release() {
        synchronized(lock) {
            if (held > 0) held -= 1
        }
    }
}
