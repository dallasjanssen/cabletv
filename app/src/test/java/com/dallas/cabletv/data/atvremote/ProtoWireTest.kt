package com.dallas.cabletv.data.atvremote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

class ProtoWireTest {
    @Test
    fun varintsUseSevenBitGroups() {
        assertEquals("00", ProtoWriter().varint(0).toByteArray().toHex())
        assertEquals("7f", ProtoWriter().varint(127).toByteArray().toHex())
        assertEquals("8001", ProtoWriter().varint(128).toByteArray().toHex())
        assertEquals("ac02", ProtoWriter().varint(300).toByteArray().toHex())
        assertEquals("e807", ProtoWriter().varint(1000).toByteArray().toHex())
    }

    @Test
    fun sixtyFourBitVarintsRoundTrip() {
        for (v in listOf(0L, 1L, 127L, 128L, 16384L, Int.MAX_VALUE.toLong(), 1L shl 40, Long.MAX_VALUE, -1L, Long.MIN_VALUE)) {
            val bytes = ProtoWriter().int(1, v).toByteArray()
            val field = ProtoReader(bytes).next()
            assertNotNull(field)
            assertEquals(v, field!!.asLong())
        }
        // Minus one takes ten bytes: a tag byte and nine of ones then a single one.
        assertEquals(11, ProtoWriter().int(1, -1L).toByteArray().size)
    }

    @Test
    fun negativeIntFieldsAreSignExtended() {
        assertEquals("08ffffffffffffffffff01", ProtoWriter().int(1, -1).toByteArray().toHex())
    }

    @Test
    fun nestedMessagesAndStringsRoundTrip() {
        val bytes = ProtoWriter()
            .int(1, 150)
            .message(2) { string(1, "héllo"); bool(2, true) }
            .bytes(3, byteArrayOf(1, 2, 3))
            .toByteArray()
        val fields = ArrayList<ProtoField>()
        ProtoReader(bytes).forEachField { fields.add(it) }
        assertEquals(3, fields.size)
        assertEquals(150, fields[0].asInt())
        val inner = ArrayList<ProtoField>()
        ProtoReader(fields[1].asBytes()!!).forEachField { inner.add(it) }
        assertEquals("héllo", inner[0].asString())
        assertEquals(true, inner[1].asBool())
        assertArrayEquals(byteArrayOf(1, 2, 3), fields[2].asBytes())
    }

    @Test
    fun wrongTypedAccessGivesNull() {
        val f = ProtoReader(ProtoWriter().string(1, "x").toByteArray()).next()!!
        assertNull(f.asLong())
        assertNull(ProtoReader(ProtoWriter().int(1, 5).toByteArray()).next()!!.asBytes())
    }

    @Test
    fun unknownFieldsOfEveryWireTypeAreSkippedOrReturned() {
        val raw = ProtoWriter()
            .int(1, 7)
            // Unknown fixed 64 bit field number 20.
            .tag(20, WireType.FIXED64)
            .toByteArray() + hex("0102030405060708") +
            ProtoWriter().tag(21, WireType.FIXED32).toByteArray() + hex("01020304") +
            // A group, number 22, holding a varint, a nested group and a string, then its end tag.
            ProtoWriter().tag(22, WireType.START_GROUP)
                .int(1, 9)
                .tag(2, WireType.START_GROUP).int(3, 1).tag(2, WireType.END_GROUP)
                .string(4, "skip me")
                .tag(22, WireType.END_GROUP).toByteArray() +
            ProtoWriter().string(23, "unknown text").int(2, 42).toByteArray()
        val numbers = ArrayList<Int>()
        var last: ProtoField? = null
        ProtoReader(raw).forEachField { numbers.add(it.number); last = it }
        // The group is skipped whole, the other unknown fields are returned for the caller to ignore.
        assertEquals(listOf(1, 20, 21, 23, 2), numbers)
        assertEquals(42, last!!.asInt())
    }

    @Test
    fun brokenDataThrows() {
        for (bad in listOf("0880", "0a05010203", "ff", "0b", "0c", "0f", "0d010203", "09010203")) {
            try {
                ProtoReader(hex(bad)).forEachField { }
                fail("Expected an error for $bad")
            } catch (expected: ProtoException) {
                assertTrue(true)
            }
        }
        // A varint of eleven bytes is too long.
        try {
            ProtoReader(hex("08ffffffffffffffffffff01")).next()
            fail("Expected an error")
        } catch (expected: ProtoException) {
            assertTrue(true)
        }
    }
}
