package com.dallas.cabletv.data.companion.client

import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

/**
 * Numbers requests and matches each reply to its request by the "re" field. A request waits on the
 * [Pending] it got back. Messages without a matching "re" are left for the caller, such as `state`
 * messages the TV sends on its own.
 */
class ReplyMatcher {
    /** A request that has been numbered and is waiting for its reply. */
    class Pending(val id: Int, val reply: CompletableDeferred<JSONObject>)

    private val lock = Any()
    private var nextId = 1
    private val waiting = HashMap<Int, CompletableDeferred<JSONObject>>()
    private var failure: Throwable? = null

    /** Adds the next id to [message] and starts waiting for its reply. Fails at once after [failAll]. */
    fun prepare(message: JSONObject): Pending {
        val deferred = CompletableDeferred<JSONObject>()
        val id = synchronized(lock) {
            val number = nextId++
            val broken = failure
            if (broken != null) {
                deferred.completeExceptionally(broken)
            } else {
                waiting[number] = deferred
            }
            number
        }
        message.put("id", id)
        return Pending(id, deferred)
    }

    /** Hands [message] to the request it answers. False when it answers nothing that is waiting. */
    fun deliver(message: JSONObject): Boolean {
        if (!message.has("re") || message.isNull("re")) return false
        val deferred = synchronized(lock) { waiting.remove(message.optInt("re", -1)) } ?: return false
        deferred.complete(message)
        return true
    }

    /** Stops waiting for [id], for example after a timeout. A late reply is then ignored. */
    fun cancel(id: Int) {
        synchronized(lock) { waiting.remove(id) }
    }

    /** Ends every request that is waiting, and any made later, with [error]. */
    fun failAll(error: Throwable) {
        val all = synchronized(lock) {
            if (failure == null) failure = error
            waiting.values.toList().also { waiting.clear() }
        }
        all.forEach { it.completeExceptionally(error) }
    }

    /** How many requests are waiting, for tests. */
    fun waitingCount(): Int = synchronized(lock) { waiting.size }
}
