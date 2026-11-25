package com.dallas.cabletv.data.companion

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What the TV app and the phone companion agree on. docs/companion-protocol.md describes the whole
 * protocol. Everything here is plain Kotlin, so unit tests run it on the JVM.
 */
object CompanionProtocol {
    /** The network service the TV advertises while the app is on screen. */
    const val SERVICE_TYPE = "_cabletv._tcp"
    const val DEFAULT_PORT = 8766
    const val PROTOCOL_VERSION = 1

    /** TXT attributes of the network service. */
    const val TXT_ID = "id"
    const val TXT_VERSION = "v"

    /** A single message may not be longer than this, so a broken peer cannot fill the memory. */
    const val MAX_LINE_BYTES = 2 * 1024 * 1024

    const val PAIR_CODE_DIGITS = 6
    const val PAIR_CODE_LIFETIME_MS = 2 * 60_000L
    const val PAIR_MAX_WRONG = 5

    const val PING_INTERVAL_MS = 20_000L
    const val IDLE_TIMEOUT_MS = 60_000L
    const val MAX_CONNECTIONS = 4

    /** Message types. */
    object Types {
        const val HELLO = "hello"
        const val WELCOME = "welcome"
        const val PAIR_START = "pair_start"
        const val PAIR_FINISH = "pair_finish"
        const val PAIRED = "paired"
        const val PING = "ping"
        const val PONG = "pong"
        const val OK = "ok"
        const val ERROR = "error"
        const val GET_STATE = "get_state"
        const val STATE = "state"
        const val KEY = "key"
        const val OPEN = "open"
        const val GET_FAVORITES = "get_favorites"
        const val FAVORITES = "favorites"
        const val GET_SETTINGS = "get_settings"
        const val SETTINGS = "settings"
        const val PUT_SETTINGS = "put_settings"
        const val SAVED = "saved"
        const val EXPORT_SYNC = "export_sync"
        const val SYNC_DATA = "sync_data"
        const val IMPORT_SYNC = "import_sync"
        const val GET_DEBUG_LOG = "get_debug_log"
        const val SET_DEBUG_LOG = "set_debug_log"
        const val DEBUG_LOG = "debug_log"
        const val GET_GRID = "get_grid"
        const val GRID = "grid"
        const val SEARCH = "search"
        const val RESULTS = "results"
        const val GET_LIBRARY = "get_library"
        const val LIBRARY = "library"
        const val OPEN_TITLE = "open_title"

        /** Types a phone may send before it has paired. */
        val BEFORE_PAIRING = setOf(HELLO, PAIR_START, PAIR_FINISH, PING)
    }

    /** Error codes in an error message's "code" field. */
    object Errors {
        const val BAD_REQUEST = "bad_request"
        const val NOT_PAIRED = "not_paired"
        const val WRONG_CODE = "wrong_code"
        const val CODE_EXPIRED = "code_expired"
        const val LOCKED = "locked"
        const val PIN_REQUIRED = "pin_required"
        const val WRONG_PIN = "wrong_pin"
        const val PIN_WAIT = "pin_wait"
        const val INVALID = "invalid"
        const val BUSY = "busy"
        const val UNSUPPORTED = "unsupported"
    }

    /** Sync sections. */
    object Sections {
        const val LOGINS = "logins"
        const val CHANNELS = "channels"
        const val FAVORITES = "favorites"
        const val LOCK = "lock"
        const val LINEUP = "lineup"
        val ALL = listOf(LOGINS, CHANNELS, FAVORITES, LOCK, LINEUP)
    }

    /**
     * The keys a phone may press in the TV app, by name, with their Android key codes. The codes
     * are the stable KeyEvent values, written out so this file needs no Android classes.
     */
    val REMOTE_KEYS: Map<String, Int> = buildMap {
        put("DPAD_UP", 19)
        put("DPAD_DOWN", 20)
        put("DPAD_LEFT", 21)
        put("DPAD_RIGHT", 22)
        put("DPAD_CENTER", 23)
        put("BACK", 4)
        put("GUIDE", 172)
        put("INFO", 165)
        put("CHANNEL_UP", 166)
        put("CHANNEL_DOWN", 167)
        put("LAST_CHANNEL", 229)
        put("MEDIA_PLAY_PAUSE", 85)
        put("MEDIA_REWIND", 89)
        put("MEDIA_FAST_FORWARD", 90)
        for (d in 0..9) put(d.toString(), 7 + d)
    }

    private const val PROOF_LABEL = "cabletv-pair-v1"

    /** A new pairing code: six digits, with leading zeros kept. */
    fun newPairCode(random: SecureRandom = SecureRandom()): String =
        random.nextInt(1_000_000).toString().padStart(PAIR_CODE_DIGITS, '0')

    /**
     * What the phone sends to prove it knows the code shown on the TV. It also covers both
     * certificates, so a code typed into a connection that someone else set up proves nothing.
     */
    fun pairingProof(code: String, tvFingerprint: ByteArray, phoneFingerprint: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.trim().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(PROOF_LABEL.toByteArray(Charsets.UTF_8))
        mac.update(tvFingerprint)
        mac.update(phoneFingerprint)
        return toHex(mac.doFinal())
    }

    /** Compares two proofs in constant time, ignoring case. */
    fun proofMatches(expected: String, given: String?): Boolean {
        if (given == null) return false
        return MessageDigest.isEqual(
            expected.lowercase().toByteArray(Charsets.US_ASCII),
            given.trim().lowercase().toByteArray(Charsets.US_ASCII)
        )
    }

    /** SHA-256 of a certificate's encoded form. Pairing stores it, and every connection checks it. */
    fun fingerprint(encodedCertificate: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(encodedCertificate)

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Odd length" }
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** An error reply to the request numbered [re], or a stray error when [re] is null. */
    fun error(re: Int?, code: String, message: String): JSONObject =
        JSONObject().put("type", Types.ERROR).put("code", code).put("message", message).also { if (re != null) it.put("re", re) }

    /** A reply of [type] to the request numbered [re]. */
    fun reply(re: Int?, type: String): JSONObject =
        JSONObject().put("type", type).also { if (re != null) it.put("re", re) }
}

/**
 * Reads and writes one JSON object per line over a connection. Reading blocks, so it belongs on a
 * background thread. Writing may happen from any thread, one message at a time.
 */
class CompanionLineChannel(input: InputStream, private val output: OutputStream) {
    private val input = BufferedInputStream(input)
    private val writeLock = Any()

    /**
     * The next message, or null when the other side closed the connection. Throws IOException for
     * a line longer than [CompanionProtocol.MAX_LINE_BYTES] or one that is not a JSON object.
     */
    fun read(): JSONObject? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                break
            }
            if (b == '\n'.code) {
                if (buf.size() == 0) continue
                break
            }
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > CompanionProtocol.MAX_LINE_BYTES) throw IOException("Message too large")
        }
        val text = buf.toString(Charsets.UTF_8.name())
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("Not a JSON object", e)
        }
    }

    fun write(message: JSONObject) {
        val bytes = (message.toString() + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > CompanionProtocol.MAX_LINE_BYTES) throw IOException("Message too large")
        synchronized(writeLock) {
            output.write(bytes)
            output.flush()
        }
    }
}
