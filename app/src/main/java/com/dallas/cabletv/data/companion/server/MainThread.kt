package com.dallas.cabletv.data.companion.server

import com.dallas.cabletv.data.companion.CompanionProtocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs work on the main thread for a connection thread and waits for the answer. Anything that
 * touches Compose state or the activity goes through here. When the main thread does not answer
 * in time the caller gets a [CompanionException] with the busy code, and the work, if it has not
 * started yet, is skipped. [post] hands a task to the main thread, and [isMain] says whether the
 * caller already is on it.
 */
class MainThread(private val post: (Runnable) -> Unit, private val isMain: () -> Boolean = { false }) {

    fun <T> call(timeoutMs: Long = DEFAULT_TIMEOUT_MS, block: () -> T): T {
        if (isMain()) return block()
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<T>>()
        val abandoned = AtomicBoolean(false)
        post(Runnable {
            if (abandoned.get()) return@Runnable
            result.set(runCatching(block))
            done.countDown()
        })
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            abandoned.set(true)
            throw CompanionException(CompanionProtocol.Errors.BUSY, "The TV is busy. Try again in a moment.")
        }
        return result.get().getOrThrow()
    }

    /** Runs [block] on the main thread without waiting for it. */
    fun run(block: () -> Unit) {
        if (isMain()) block() else post(Runnable { runCatching(block) })
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
