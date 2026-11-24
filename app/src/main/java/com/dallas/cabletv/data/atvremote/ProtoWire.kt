package com.dallas.cabletv.data.atvremote

import java.io.ByteArrayOutputStream

/** The data was not valid protocol buffer data. */
class ProtoException(message: String) : Exception(message)

/** The wire types a field can have. Group types are skipped when reading and never written. */
object WireType {
    const val VARINT = 0
    const val FIXED64 = 1
    const val LENGTH_DELIMITED = 2
    const val START_GROUP = 3
    const val END_GROUP = 4
    const val FIXED32 = 5
}

/**
 * Builds one protocol buffer message. Fields are written in the order they are added, so callers
 * add them in field number order, which is what the official libraries do too.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    /** A base-128 varint of all 64 bits. Negative numbers take ten bytes, as the format says. */
    fun varint(value: Long): ProtoWriter {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
        return this
    }

    fun tag(field: Int, wireType: Int): ProtoWriter = varint((field.toLong() shl 3) or wireType.toLong())

    /** An integer or enum field. */
    fun int(field: Int, value: Long): ProtoWriter = tag(field, WireType.VARINT).varint(value)

    fun int(field: Int, value: Int): ProtoWriter = int(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = int(field, if (value) 1L else 0L)

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WireType.LENGTH_DELIMITED).varint(value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    /** A nested message, filled in by [build]. */
    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter =
        bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}

/**
 * One field read from a message. [value] holds a varint, or the raw bits of a fixed size field.
 * [data] holds the bytes of a length-delimited field and is null for the other wire types.
 */
class ProtoField(val number: Int, val wireType: Int, val value: Long, val data: ByteArray?) {
    /** The field as a number, or null when it does not have the number wire type. */
    fun asLong(): Long? = if (wireType == WireType.VARINT) value else null

    fun asInt(): Int? = asLong()?.toInt()

    fun asBool(): Boolean? = asLong()?.let { it != 0L }

    /** The field as raw bytes or a nested message, or null when it is not length-delimited. */
    fun asBytes(): ByteArray? = data

    fun asString(): String? = data?.toString(Charsets.UTF_8)
}

/**
 * Walks over the fields of one message. Unknown fields come back like any other, so callers pick
 * the numbers they know and ignore the rest. Group fields are skipped whole.
 */
class ProtoReader(private val buf: ByteArray) {
    private var pos = 0

    /** The next field, or null at the end of the message. Throws [ProtoException] on bad data. */
    fun next(): ProtoField? {
        while (true) {
            if (pos >= buf.size) return null
            val key = readVarint()
            val number = key ushr 3
            if (number < 1 || number > Int.MAX_VALUE) throw ProtoException("Bad field number")
            val n = number.toInt()
            when (val wire = (key and 7).toInt()) {
                WireType.VARINT -> return ProtoField(n, wire, readVarint(), null)
                WireType.FIXED64 -> return ProtoField(n, wire, readFixed(8), null)
                WireType.FIXED32 -> return ProtoField(n, wire, readFixed(4), null)
                WireType.LENGTH_DELIMITED -> {
                    val len = readVarint()
                    if (len < 0 || len > buf.size - pos) throw ProtoException("Field runs past the end")
                    val data = buf.copyOfRange(pos, pos + len.toInt())
                    pos += len.toInt()
                    return ProtoField(n, wire, 0, data)
                }
                WireType.START_GROUP -> skipGroup(number)
                WireType.END_GROUP -> throw ProtoException("Unexpected end of group")
                else -> throw ProtoException("Unknown wire type $wire")
            }
        }
    }

    /** Calls [block] for every field, in order. */
    fun forEachField(block: (ProtoField) -> Unit) {
        while (true) block(next() ?: return)
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= buf.size) throw ProtoException("Varint runs past the end")
            val b = buf[pos++].toInt() and 0xFF
            if (shift == 63 && (b and 0x7E) != 0) throw ProtoException("Varint is too long")
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
            if (shift > 63) throw ProtoException("Varint is too long")
        }
    }

    private fun readFixed(size: Int): Long {
        if (size > buf.size - pos) throw ProtoException("Field runs past the end")
        var v = 0L
        for (i in 0 until size) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += size
        return v
    }

    /** Skips the fields of a group until its matching end tag. Groups may nest. */
    private fun skipGroup(startNumber: Long) {
        val open = ArrayList<Long>()
        open.add(startNumber)
        while (open.isNotEmpty()) {
            if (pos >= buf.size) throw ProtoException("Group is not closed")
            val key = readVarint()
            val number = key ushr 3
            when ((key and 7).toInt()) {
                WireType.VARINT -> readVarint()
                WireType.FIXED64 -> readFixed(8)
                WireType.FIXED32 -> readFixed(4)
                WireType.LENGTH_DELIMITED -> {
                    val len = readVarint()
                    if (len < 0 || len > buf.size - pos) throw ProtoException("Field runs past the end")
                    pos += len.toInt()
                }
                WireType.START_GROUP -> open.add(number)
                WireType.END_GROUP ->
                    if (open.removeAt(open.size - 1) != number) throw ProtoException("Mismatched group end")
                else -> throw ProtoException("Unknown wire type")
            }
        }
    }
}
