package com.dallas.cabletv.data.atvremote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream

class FramingTest {
    @Test
    fun framePrefixesTheLengthAsAVarint() {
        assertEquals("03010203", Framing.frame(byteArrayOf(1, 2, 3)).toHex())
        val long = ByteArray(300) { 7 }
        val framed = Framing.frame(long)
        assertEquals("ac02", framed.copyOfRange(0, 2).toHex())
        assertEquals(302, framed.size)
    }

    @Test
    fun oneByteAtATimeStillGivesWholeMessages() {
        val a = ByteArray(5) { it.toByte() }
        val b = ByteArray(200) { (it * 3).toByte() }
        val stream = Framing.frame(a) + Framing.frame(b)
        val reader = FrameReader()
        val got = ArrayList<ByteArray>()
        for (byte in stream) {
            reader.feed(byteArrayOf(byte))
            while (true) got.add(reader.next() ?: break)
        }
        assertEquals(2, got.size)
        assertArrayEquals(a, got[0])
        assertArrayEquals(b, got[1])
        assertFalse(reader.hasPartial)
    }

    @Test
    fun severalMessagesInOneChunkComeOutInOrder() {
        val reader = FrameReader()
        reader.feed(Framing.frame(byteArrayOf(1)) + Framing.frame(byteArrayOf(2, 2)) + Framing.frame(ByteArray(0)))
        assertArrayEquals(byteArrayOf(1), reader.next())
        assertArrayEquals(byteArrayOf(2, 2), reader.next())
        assertEquals(0, reader.next()!!.size)
        assertNull(reader.next())
    }

    @Test
    fun partialMessageWaits() {
        val reader = FrameReader()
        val framed = Framing.frame(ByteArray(10) { 9 })
        reader.feed(framed, 0, 4)
        assertNull(reader.next())
        assertTrue(reader.hasPartial)
        reader.feed(framed, 4, framed.size - 4)
        assertEquals(10, reader.next()!!.size)
    }

    @Test
    fun partialLengthWaits() {
        val reader = FrameReader()
        val framed = Framing.frame(ByteArray(300))
        reader.feed(framed, 0, 1)
        assertNull(reader.next())
        reader.feed(framed, 1, framed.size - 1)
        assertEquals(300, reader.next()!!.size)
    }

    @Test
    fun tooLongMessageIsRefused() {
        val reader = FrameReader(maxBytes = 100)
        reader.feed(ProtoWriter().varint(101).toByteArray())
        try {
            reader.next()
            fail("Expected an error")
        } catch (expected: ProtoException) {
            assertTrue(true)
        }
        val endless = FrameReader()
        endless.feed(hex("ffffffffff7f"))
        try {
            endless.next()
            fail("Expected an error")
        } catch (expected: ProtoException) {
            assertTrue(true)
        }
    }

    @Test
    fun readFromAssemblesMessagesFromSmallReads() {
        val stream = Framing.frame(byteArrayOf(5, 6, 7)) + Framing.frame(ByteArray(5000) { 1 })
        val trickle = object : InputStream() {
            private val inner = ByteArrayInputStream(stream)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, 3))
        }
        val reader = FrameReader()
        assertArrayEquals(byteArrayOf(5, 6, 7), reader.readFrom(trickle))
        assertEquals(5000, reader.readFrom(trickle)!!.size)
        assertNull(reader.readFrom(trickle))
    }

    @Test
    fun streamEndingInsideAMessageIsAnError() {
        val cut = Framing.frame(ByteArray(20)).copyOfRange(0, 10)
        try {
            FrameReader().readFrom(ByteArrayInputStream(cut))
            fail("Expected an error")
        } catch (expected: EOFException) {
            assertTrue(true)
        }
    }
}
