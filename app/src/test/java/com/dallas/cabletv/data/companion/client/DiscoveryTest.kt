package com.dallas.cabletv.data.companion.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {
    private fun txt(id: String?, v: String? = "1"): Map<String, ByteArray?> = buildMap {
        if (id != null) put("id", id.toByteArray())
        if (v != null) put("v", v.toByteArray())
    }

    @Test
    fun resolvedServiceBecomesATvIdentifiedByItsId() {
        val tv = DiscoveredTv.fromResolved("Living Room TV (2)", "192.168.1.20", 8766, txt("abc-123"))!!
        assertEquals("abc-123", tv.id)
        assertEquals("Living Room TV", tv.name)
        assertEquals("Living Room TV (2)", tv.serviceName)
        assertEquals("192.168.1.20", tv.host)
        assertEquals(8766, tv.port)
        assertEquals(1, tv.version)
        assertFalse(tv.needsNewerApp)
    }

    @Test
    fun serviceWithoutIdOrAddressIsIgnored() {
        assertNull(DiscoveredTv.fromResolved("TV", "10.0.0.2", 8766, txt(null)))
        assertNull(DiscoveredTv.fromResolved("TV", "10.0.0.2", 8766, txt("  ")))
        assertNull(DiscoveredTv.fromResolved("TV", null, 8766, txt("a")))
        assertNull(DiscoveredTv.fromResolved("TV", "10.0.0.2", 0, txt("a")))
    }

    @Test
    fun newerProtocolIsFlaggedAndMissingVersionReadsAsOne() {
        assertTrue(DiscoveredTv.fromResolved("TV", "h", 1, txt("a", "2"))!!.needsNewerApp)
        assertEquals(1, DiscoveredTv.fromResolved("TV", "h", 1, txt("a", null))!!.version)
        assertEquals(1, DiscoveredTv.fromResolved("TV", "h", 1, txt("a", "x"))!!.version)
    }

    @Test
    fun suffixIsRemovedOnlyWhenItIsTheNetworksCounter() {
        assertEquals("Den", DiscoveredTv.cleanServiceName("Den (12)"))
        assertEquals("Den (HD)", DiscoveredTv.cleanServiceName("Den (HD)"))
        assertEquals("TV", DiscoveredTv.cleanServiceName(" (2)"))
    }

    @Test
    fun listReplacesByIdRemovesLostAndSortsByName() {
        val list = DiscoveryList()
        val b = DiscoveredTv("2", "Bedroom", "Bedroom", "h2", 1, 1)
        val l = DiscoveredTv("1", "Living", "Living", "h1", 1, 1)
        list.found(b)
        assertEquals(listOf("Bedroom", "Living"), list.found(l).map { it.name })
        val moved = l.copy(host = "h9")
        assertEquals("h9", list.found(moved).first { it.id == "1" }.host)
        assertEquals(listOf("1"), list.lost("Bedroom").map { it.id })
        assertTrue(list.clear().isEmpty())
    }

    @Test
    fun queueRunsOneJobAtATimeInOrder() {
        val started = mutableListOf<String>()
        val queue = OneAtATimeQueue<String> { started.add(it) }
        queue.enqueue("a")
        queue.enqueue("b")
        queue.enqueue("c")
        queue.enqueue("c")
        assertEquals(listOf("a"), started)
        queue.finished()
        assertEquals(listOf("a", "b"), started)
        queue.finished()
        queue.finished()
        assertEquals(listOf("a", "b", "c"), started)
        queue.finished()
        queue.enqueue("d")
        assertEquals(listOf("a", "b", "c", "d"), started)
    }

    @Test
    fun queueKeepsGoingWhenAJobFailsRightAway() {
        val started = mutableListOf<String>()
        lateinit var queue: OneAtATimeQueue<String>
        queue = OneAtATimeQueue { item ->
            started.add(item)
            if (item == "bad") queue.finished()
        }
        queue.enqueue("bad")
        queue.enqueue("good")
        assertEquals(listOf("bad", "good"), started)
    }

    @Test
    fun clearingTheQueueLetsALaterJobStart() {
        val started = mutableListOf<String>()
        val queue = OneAtATimeQueue<String> { started.add(it) }
        queue.enqueue("a")
        queue.enqueue("b")
        queue.clear()
        queue.enqueue("c")
        assertEquals(listOf("a", "c"), started)
    }
}
