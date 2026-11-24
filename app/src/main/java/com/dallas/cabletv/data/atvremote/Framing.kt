package com.dallas.cabletv.data.atvremote

import java.io.EOFException
import java.io.InputStream

/** Each message on the wire is its length as a varint, then the message. [frame] adds the length. */
object Framing {
    /** No message from the TV is anywhere near this long. A bigger length means the stream is broken. */
    const val MAX_MESSAGE_BYTES = 256 * 1024

    fun frame(message: ByteArray): ByteArray =
        ProtoWriter().varint(message.size.toLong()).toByteArray() + message
}

/**
 * Cuts a stream of bytes into messages. Bytes may arrive in any pieces, and a message may be split
 * anywhere, even inside its length. Feed what arrives and take messages out as they complete.
 */
class FrameReader(private val maxBytes: Int = Framing.MAX_MESSAGE_BYTES) {
    private var buf = ByteArray(1024)
    private var start = 0
    private var end = 0

    /** True when some bytes of an unfinished message are waiting. */
    val hasPartial: Boolean get() = end > start

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (end + length > buf.size) {
            val live = end - start
            val target = if (live + length > buf.size) ByteArray(maxOf(buf.size * 2, live + length)) else buf
            System.arraycopy(buf, start, target, 0, live)
            buf = target
            start = 0
            end = live
        }
        System.arraycopy(data, offset, buf, end, length)
        end += length
    }

    /** The next complete message, or null when more bytes are needed. Throws [ProtoException] on a bad length. */
    fun next(): ByteArray? {
        var len = 0L
        var shift = 0
        var i = start
        while (true) {
            if (i >= end) return null
            val b = buf[i++].toInt() and 0xFF
            len = len or ((b and 0x7F).toLong() shl shift)
            if (len > maxBytes) throw ProtoException("Message is too long")
            if ((b and 0x80) == 0) break
            shift += 7
            if (shift > 28) throw ProtoException("Message length is too long")
        }
        if (end - i < len) return null
        val message = buf.copyOfRange(i, i + len.toInt())
        start = i + len.toInt()
        if (start == end) {
            start = 0
            end = 0
        }
        return message
    }

    /**
     * Reads from [input] until one message is complete and returns it. Returns null when the stream
     * ends cleanly between messages. Throws [EOFException] when it ends inside one.
     */
    fun readFrom(input: InputStream): ByteArray? {
        val chunk = ByteArray(4096)
        while (true) {
            next()?.let { return it }
            val n = input.read(chunk)
            if (n < 0) {
                if (hasPartial) throw EOFException("Connection closed inside a message")
                return null
            }
            feed(chunk, 0, n)
        }
    }
}
