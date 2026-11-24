package com.dallas.cabletv.data.atvremote

/**
 * The pairing messages (polo.proto). Every message is an OuterMessage with a protocol version of 2
 * and a status. A client always says 200 (OK).
 */
object Polo {
    const val PROTOCOL_VERSION = 2
    const val STATUS_OK = 200
    const val STATUS_BAD_SECRET = 402

    /** The service name the TV expects from a remote control. */
    const val SERVICE_NAME = "atvremote"

    private const val ROLE_INPUT = 1
    private const val ENCODING_HEXADECIMAL = 3
    private const val SYMBOL_LENGTH = 6

    /** Outer message fields. */
    private const val F_PAIRING_REQUEST = 10
    private const val F_PAIRING_REQUEST_ACK = 11
    private const val F_OPTIONS = 20
    private const val F_CONFIGURATION = 30
    private const val F_CONFIGURATION_ACK = 31
    private const val F_SECRET = 40
    private const val F_SECRET_ACK = 41

    private fun outer(body: ProtoWriter.() -> Unit): ByteArray = ProtoWriter()
        .int(1, PROTOCOL_VERSION)
        .int(2, STATUS_OK)
        .apply(body)
        .toByteArray()

    /** Asks to pair. The TV shows [clientName] next to the code. */
    fun pairingRequest(clientName: String): ByteArray = outer {
        message(F_PAIRING_REQUEST) {
            string(1, SERVICE_NAME)
            string(2, clientName)
        }
    }

    /** Says what this client can do: it types a 6 symbol hexadecimal code the TV shows. */
    fun options(): ByteArray = outer {
        message(F_OPTIONS) {
            message(1) {
                int(1, ENCODING_HEXADECIMAL)
                int(2, SYMBOL_LENGTH)
            }
            int(3, ROLE_INPUT)
        }
    }

    /** Settles on the options: this client is the input side, using a 6 symbol hexadecimal code. */
    fun configuration(): ByteArray = outer {
        message(F_CONFIGURATION) {
            message(1) {
                int(1, ENCODING_HEXADECIMAL)
                int(2, SYMBOL_LENGTH)
            }
            int(2, ROLE_INPUT)
        }
    }

    /** Sends the secret worked out from the code. */
    fun secret(secret: ByteArray): ByteArray = outer {
        message(F_SECRET) { bytes(1, secret) }
    }

    /** What kind of message the TV sent. */
    enum class Kind { PAIRING_REQUEST_ACK, OPTIONS, CONFIGURATION_ACK, SECRET_ACK, OTHER }

    /** A message from the TV. [status] is 0 when the TV left it out. */
    class Incoming(val status: Int, val kind: Kind)

    /** Reads a message from the TV. Throws [ProtoException] when it is not valid. */
    fun parse(bytes: ByteArray): Incoming {
        var status = 0
        var kind = Kind.OTHER
        ProtoReader(bytes).forEachField { f ->
            when (f.number) {
                2 -> f.asInt()?.let { status = it }
                F_PAIRING_REQUEST_ACK -> if (f.asBytes() != null) kind = Kind.PAIRING_REQUEST_ACK
                F_OPTIONS -> if (f.asBytes() != null) kind = Kind.OPTIONS
                F_CONFIGURATION_ACK -> if (f.asBytes() != null) kind = Kind.CONFIGURATION_ACK
                F_SECRET_ACK -> if (f.asBytes() != null) kind = Kind.SECRET_ACK
            }
        }
        return Incoming(status, kind)
    }
}
