package com.dallas.cabletv.data.companion.client

import com.dallas.cabletv.data.companion.CompanionProtocol

/** A TV found on the network. [serviceName] is the network service's own name, used to notice when it goes away. */
data class DiscoveredTv(
    val id: String,
    val name: String,
    val serviceName: String,
    val host: String,
    val port: Int,
    val version: Int
) {
    /** True when the TV speaks a newer protocol than this phone app knows. */
    val needsNewerApp: Boolean get() = version > CompanionProtocol.PROTOCOL_VERSION

    companion object {
        /**
         * Builds a TV from what resolving a network service gave: its host and port and its TXT
         * attributes. Null when the service has no usable id or address, because the phone never
         * identifies a TV by its name.
         */
        fun fromResolved(serviceName: String, host: String?, port: Int, txt: Map<String, ByteArray?>): DiscoveredTv? {
            val id = txt[CompanionProtocol.TXT_ID]?.toString(Charsets.UTF_8)?.trim().orEmpty()
            val address = host?.trim().orEmpty()
            if (id.isEmpty() || address.isEmpty() || port !in 1..65535) return null
            val version = txt[CompanionProtocol.TXT_VERSION]?.toString(Charsets.UTF_8)?.trim()?.toIntOrNull() ?: 1
            return DiscoveredTv(id, cleanServiceName(serviceName), serviceName, address, port, version)
        }

        /** A service name without the " (2)" suffix the network adds when two TVs share a name. */
        fun cleanServiceName(serviceName: String): String =
            serviceName.replace(Regex("\\s\\(\\d+\\)$"), "").ifBlank { "TV" }
    }
}

/** The TVs found so far. A TV seen again replaces its earlier entry, and a lost service removes it. */
class DiscoveryList {
    private val byId = LinkedHashMap<String, DiscoveredTv>()

    @Synchronized
    fun found(tv: DiscoveredTv): List<DiscoveredTv> {
        byId[tv.id] = tv
        return snapshot()
    }

    @Synchronized
    fun lost(serviceName: String): List<DiscoveredTv> {
        byId.values.removeAll { it.serviceName == serviceName }
        return snapshot()
    }

    @Synchronized
    fun clear(): List<DiscoveredTv> {
        byId.clear()
        return emptyList()
    }

    private fun snapshot(): List<DiscoveredTv> = byId.values.sortedBy { it.name.lowercase() }
}

/**
 * Runs one job at a time. Older Android versions can resolve only one network service at once, so
 * discovery queues what it finds and resolves them in turn. Call [finished] when the job started
 * by [start] is over, whether it worked or not.
 */
class OneAtATimeQueue<T>(private val start: (T) -> Unit) {
    private val waiting = ArrayDeque<T>()
    private var busy = false

    /** Queues [item]. An item already waiting is not queued twice. */
    fun enqueue(item: T) {
        synchronized(this) {
            if (item !in waiting) waiting.addLast(item)
        }
        pump()
    }

    fun finished() {
        synchronized(this) { busy = false }
        pump()
    }

    /** Drops everything waiting and forgets the running job, so a lost callback cannot block the queue for good. */
    fun clear() {
        synchronized(this) {
            waiting.clear()
            busy = false
        }
    }

    private fun pump() {
        val next = synchronized(this) {
            if (busy || waiting.isEmpty()) null else waiting.removeFirst().also { busy = true }
        } ?: return
        start(next)
    }
}
