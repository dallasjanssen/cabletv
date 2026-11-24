package com.dallas.cabletv.data.companion.client

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedTvJsonTest {
    private val living = PairedTv("id-1", "Living Room TV", "ab12cd", "192.168.1.20", 8766)
    private val bedroom = PairedTv("id-2", "Bedroom TV", "ef34", "192.168.1.21", 8767, atvPaired = true)

    @Test
    fun listSurvivesEncodingAndDecoding() {
        val text = PairedTvJson.encode(listOf(living, bedroom))
        assertEquals(listOf(living, bedroom), PairedTvJson.decode(text))
    }

    @Test
    fun storedShapeUsesTheDocumentedKeys() {
        val o = JSONArray(PairedTvJson.encode(listOf(bedroom))).getJSONObject(0)
        assertEquals("id-2", o.getString("id"))
        assertEquals("Bedroom TV", o.getString("name"))
        assertEquals("ef34", o.getString("fingerprint"))
        assertEquals("192.168.1.21", o.getString("host"))
        assertEquals(8767, o.getInt("port"))
        assertTrue(o.getBoolean("atv_paired"))
    }

    @Test
    fun badOrIncompleteStorageReadsAsFewerTvs() {
        assertEquals(emptyList<PairedTv>(), PairedTvJson.decode(null))
        assertEquals(emptyList<PairedTv>(), PairedTvJson.decode(""))
        assertEquals(emptyList<PairedTv>(), PairedTvJson.decode("not json"))
        val text = """[{"id":"a"},{"id":"b","fingerprint":"ff","host":"h","port":9},"x",
            {"id":"b","fingerprint":"ee","host":"again","port":1}]"""
        val tvs = PairedTvJson.decode(text)
        assertEquals(1, tvs.size)
        assertEquals("TV", tvs[0].name)
        assertFalse(tvs[0].atvPaired)
        assertEquals("h", tvs[0].host)
    }

    @Test
    fun missingPortFallsBackToTheDefault() {
        val tvs = PairedTvJson.decode("""[{"id":"a","fingerprint":"ff","host":"h"}]""")
        assertEquals(8766, tvs[0].port)
    }

    @Test
    fun upsertKeepsThePlaceOfAnExistingTv() {
        val renamed = living.copy(name = "Den TV")
        assertEquals(listOf(renamed, bedroom), PairedTvJson.upsert(listOf(living, bedroom), renamed))
        assertEquals(listOf(living, bedroom), PairedTvJson.upsert(listOf(living), bedroom))
    }

    @Test
    fun removeDropsOnlyThatTv() {
        assertEquals(listOf(bedroom), PairedTvJson.remove(listOf(living, bedroom), "id-1"))
    }

    @Test
    fun newAddressIsStoredAndTheSameAddressChangesNothing() {
        val list = listOf(living, bedroom)
        val moved = PairedTvJson.withAddress(list, "id-1", "192.168.1.99", 9000)
        assertEquals("192.168.1.99", moved[0].host)
        assertEquals(9000, moved[0].port)
        assertEquals(bedroom, moved[1])
        assertSame(list, PairedTvJson.withAddress(list, "id-1", "192.168.1.20", 8766))
        assertSame(list, PairedTvJson.withAddress(list, "unknown", "x", 1))
    }

    @Test
    fun atvFlagIsSetAndCleared() {
        val list = listOf(living, bedroom)
        assertTrue(PairedTvJson.withAtvPaired(list, "id-1", true)[0].atvPaired)
        assertFalse(PairedTvJson.withAtvPaired(list, "id-2", false)[1].atvPaired)
        assertSame(list, PairedTvJson.withAtvPaired(list, "id-2", true))
    }

    @Test
    fun fingerprintComesBackAsBytes() {
        val bytes = living.fingerprintBytes()
        assertEquals(3, bytes.size)
        assertEquals(0xab.toByte(), bytes[0])
    }
}
