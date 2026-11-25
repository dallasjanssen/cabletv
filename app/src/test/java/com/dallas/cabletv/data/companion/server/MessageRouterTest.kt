package com.dallas.cabletv.data.companion.server

import com.dallas.cabletv.data.companion.CompanionProtocol
import com.dallas.cabletv.data.companion.CompanionProtocol.Errors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A backend that records what the router asked of it. */
private class FakeBackend : CompanionBackend {
    override val tvName = "Living Room TV"
    override val tvId = "tv-1"
    override val appBuild = 42
    val paired = mutableListOf<PairedPhone>()
    var pin: String? = null
    val keys = mutableListOf<Int>()
    val links = mutableListOf<String>()
    var failWith: CompanionException? = null

    override fun isPaired(fingerprint: String) = paired.any { it.fingerprint == fingerprint }
    override fun addPaired(phone: PairedPhone) { paired += phone }
    override fun pinSet() = pin != null
    override fun checkPin(pin: String) = this.pin == pin
    override fun stateMessage(): JSONObject = JSONObject().put("type", "state").put("screen", "live").put("state", "idle")
    override fun pressKey(code: Int) { failWith?.let { throw it }; keys += code }
    override fun openLink(link: String) { links += link }
    override fun favorites(): JSONArray = JSONArray().put(JSONObject().put("id", "1").put("name", "ESPN").put("number", 206))
    override fun settings(): JSONObject = JSONObject().put("pin_set", pin != null)
    override fun putSettings(settings: JSONObject) = PutResult(listOf("Saved."), settings)
    override fun exportSync(sections: List<String>): JSONObject = JSONObject().put("format", 1).put("sections", JSONObject())
    override fun importSync(data: JSONObject): List<String> = listOf("Done.")
    override fun debugLog() = DebugLogState(enabled = false, log = "")
    override fun changeDebugLog(enabled: Boolean?, clear: Boolean) = DebugLogState(enabled = enabled ?: false, log = "")
}

class MessageRouterTest {
    private var clock = 50_000L
    private val backend = FakeBackend()
    private val pairing = PairingManager(now = { clock }, newCode = { "482913" })
    private val pins = PinGate(hasPin = { backend.pinSet() }, matches = { backend.checkPin(it) }, now = { clock })
    private val router = MessageRouter(backend, pairing, pins, now = { clock })

    private val tvFp = ByteArray(32) { 1 }
    private val phoneFp = ByteArray(32) { 2 }
    private val conn = ConnectionInfo(tvFp, phoneFp)

    private var nextId = 1

    private fun send(type: String, vararg fields: Pair<String, Any?>): JSONObject {
        val msg = JSONObject().put("type", type).put("id", nextId++)
        fields.forEach { (k, v) -> msg.put(k, v) }
        return router.handle(conn, msg)
    }

    private fun pairPhone() {
        backend.paired += PairedPhone(conn.phoneKey, "Pixel 10", clock)
    }

    private fun assertError(code: String, reply: JSONObject) {
        assertEquals("error", reply.getString("type"))
        assertEquals(code, reply.getString("code"))
        assertTrue(reply.getString("message").isNotBlank())
    }

    @Test
    fun repliesCarryTheRequestId() {
        val reply = send("ping")
        assertEquals("pong", reply.getString("type"))
        assertEquals(1, reply.getInt("re"))
        assertEquals(2, send("ping").getInt("re"))
    }

    @Test
    fun aMessageWithoutAnIdGetsAReplyWithoutRe() {
        val reply = router.handle(conn, JSONObject().put("type", "ping"))
        assertEquals("pong", reply.getString("type"))
        assertFalse(reply.has("re"))
    }

    @Test
    fun beforePairingOnlyTheFourOpeningMessagesWork() {
        for (type in listOf("get_state", "key", "open", "get_favorites", "get_settings", "put_settings", "export_sync", "import_sync", "nonsense")) {
            val reply = send(type)
            assertError(Errors.NOT_PAIRED, reply)
        }
        assertEquals("pong", send("ping").getString("type"))
        assertEquals("welcome", send("hello").getString("type"))
    }

    @Test
    fun helloBeforePairingSaysNotPairedAndHidesThePin() {
        backend.pin = "1234"
        val reply = send("hello", "phone_name" to "Pixel 10", "app_build" to 7)
        assertEquals("welcome", reply.getString("type"))
        assertFalse(reply.getBoolean("paired"))
        assertEquals("Living Room TV", reply.getString("tv_name"))
        assertEquals("tv-1", reply.getString("tv_id"))
        assertEquals(42, reply.getInt("app_build"))
        assertEquals(CompanionProtocol.PROTOCOL_VERSION, reply.getInt("protocol"))
        assertFalse(reply.has("pin_set"))
    }

    @Test
    fun helloAfterPairingReportsThePin() {
        pairPhone()
        assertFalse(send("hello").getBoolean("pin_set"))
        backend.pin = "1234"
        val reply = send("hello")
        assertTrue(reply.getBoolean("paired"))
        assertTrue(reply.getBoolean("pin_set"))
    }

    @Test
    fun anUnknownTypeAfterPairingIsBadRequest() {
        pairPhone()
        assertError(Errors.BAD_REQUEST, send("make_coffee"))
        assertError(Errors.BAD_REQUEST, router.handle(conn, JSONObject()))
    }

    @Test
    fun pairingFromStartToFinish() {
        assertEquals("ok", send("pair_start", "phone_name" to "Pixel 10").getString("type"))
        assertEquals("482 913", pairing.offer.value?.displayCode)

        val wrong = send("pair_finish", "proof" to CompanionProtocol.pairingProof("000000", tvFp, phoneFp))
        assertError(Errors.WRONG_CODE, wrong)
        assertEquals(4, wrong.getInt("attempts_left"))

        val proof = CompanionProtocol.pairingProof("482913", tvFp, phoneFp)
        val done = send("pair_finish", "proof" to proof)
        assertEquals("paired", done.getString("type"))
        assertEquals("Living Room TV", done.getString("tv_name"))
        assertEquals("tv-1", done.getString("tv_id"))
        assertEquals(listOf(PairedPhone(conn.phoneKey, "Pixel 10", clock)), backend.paired)
        assertNull(pairing.offer.value)

        // The phone now counts as paired on the same connection.
        assertEquals("state", send("get_state").getString("type"))
    }

    @Test
    fun fiveWrongCodesLockThePairing() {
        send("pair_start", "phone_name" to "Pixel 10")
        val bad = CompanionProtocol.pairingProof("000000", tvFp, phoneFp)
        repeat(4) { assertError(Errors.WRONG_CODE, send("pair_finish", "proof" to bad)) }
        assertError(Errors.LOCKED, send("pair_finish", "proof" to bad))
        assertError(Errors.CODE_EXPIRED, send("pair_finish", "proof" to CompanionProtocol.pairingProof("482913", tvFp, phoneFp)))
        assertTrue(backend.paired.isEmpty())
    }

    @Test
    fun anExpiredOrCancelledCodeAnswersCodeExpired() {
        send("pair_start", "phone_name" to "Pixel 10")
        clock += CompanionProtocol.PAIR_CODE_LIFETIME_MS
        val proof = CompanionProtocol.pairingProof("482913", tvFp, phoneFp)
        assertError(Errors.CODE_EXPIRED, send("pair_finish", "proof" to proof))

        send("pair_start", "phone_name" to "Pixel 10")
        pairing.cancel()
        assertError(Errors.CODE_EXPIRED, send("pair_finish", "proof" to proof))
    }

    @Test
    fun pairStartNeedsAName() {
        assertError(Errors.BAD_REQUEST, send("pair_start"))
        assertError(Errors.BAD_REQUEST, send("pair_start", "phone_name" to "   "))
        assertNull(pairing.offer.value)
    }

    @Test
    fun aLongNameIsShortenedAndControlCharactersAreDropped() {
        send("pair_start", "phone_name" to ("Pixel\n" + "x".repeat(100)))
        val name = pairing.offer.value!!.phoneName
        assertEquals(MessageRouter.MAX_NAME, name.length)
        assertFalse(name.contains('\n'))
    }

    @Test
    fun pairFinishNeedsAProof() {
        send("pair_start", "phone_name" to "Pixel 10")
        assertError(Errors.BAD_REQUEST, send("pair_finish"))
    }

    @Test
    fun removingAPhoneTakesEffectOnTheNextMessage() {
        pairPhone()
        assertEquals("state", send("get_state").getString("type"))
        backend.paired.clear()
        assertError(Errors.NOT_PAIRED, send("get_state"))
    }

    @Test
    fun stateIsAnsweredWithRe() {
        pairPhone()
        val reply = send("get_state")
        assertEquals("live", reply.getString("screen"))
        assertEquals(reply.getInt("re"), nextId - 1)
    }

    @Test
    fun keysArePressedByNameAndUnknownKeysAreRefused() {
        pairPhone()
        assertEquals("ok", send("key", "key" to "DPAD_UP").getString("type"))
        assertEquals("ok", send("key", "key" to "5").getString("type"))
        assertEquals(listOf(19, 12), backend.keys)
        assertError(Errors.BAD_REQUEST, send("key", "key" to "POWER"))
        assertError(Errors.BAD_REQUEST, send("key"))
        assertEquals(2, backend.keys.size)
    }

    @Test
    fun aBackendFailureBecomesItsErrorCode() {
        pairPhone()
        backend.failWith = CompanionException(Errors.BUSY, "The TV is busy.")
        assertError(Errors.BUSY, send("key", "key" to "BACK"))
    }

    @Test
    fun onlyCabletvLinksAreOpened() {
        pairPhone()
        assertEquals("ok", send("open", "link" to "cabletv://channel?id=1234").getString("type"))
        assertEquals("ok", send("open", "link" to "cabletv://live").getString("type"))
        assertError(Errors.BAD_REQUEST, send("open", "link" to "https://example.com"))
        assertError(Errors.BAD_REQUEST, send("open", "link" to "cabletv://nowhere"))
        assertError(Errors.BAD_REQUEST, send("open"))
        assertEquals(listOf("cabletv://channel?id=1234", "cabletv://live"), backend.links)
    }

    @Test
    fun favoritesAreWrapped() {
        pairPhone()
        val reply = send("get_favorites")
        assertEquals("favorites", reply.getString("type"))
        assertEquals("ESPN", reply.getJSONArray("favorites").getJSONObject(0).getString("name"))
    }

    @Test
    fun settingsNeedNoPinWhenThereIsNone() {
        pairPhone()
        val reply = send("get_settings")
        assertEquals("settings", reply.getString("type"))
        assertFalse(reply.getJSONObject("settings").getBoolean("pin_set"))
    }

    @Test
    fun settingsAndSyncAskForThePin() {
        pairPhone()
        backend.pin = "1234"
        val requests = listOf(
            "get_settings" to emptyArray<Pair<String, Any?>>(),
            "put_settings" to arrayOf<Pair<String, Any?>>("settings" to JSONObject()),
            "export_sync" to arrayOf<Pair<String, Any?>>("sections" to JSONArray().put("logins")),
            "import_sync" to arrayOf<Pair<String, Any?>>("data" to JSONObject().put("format", 1).put("sections", JSONObject()))
        )
        for ((type, fields) in requests) {
            assertError(Errors.PIN_REQUIRED, send(type, *fields))
            val withPin = send(type, *(fields + ("pin" to "1234")))
            assertFalse("$type with the PIN", withPin.getString("type") == "error")
        }
    }

    @Test
    fun theRemoteLinksStateAndFavoritesNeverNeedThePin() {
        pairPhone()
        backend.pin = "1234"
        assertEquals("ok", send("key", "key" to "BACK").getString("type"))
        assertEquals("ok", send("open", "link" to "cabletv://live").getString("type"))
        assertEquals("state", send("get_state").getString("type"))
        assertEquals("favorites", send("get_favorites").getString("type"))
    }

    @Test
    fun aWrongPinIsRefusedAndFiveInARowPauseIt() {
        pairPhone()
        backend.pin = "1234"
        repeat(4) { assertError(Errors.WRONG_PIN, send("get_settings", "pin" to "0000")) }
        assertError(Errors.PIN_WAIT, send("get_settings", "pin" to "0000"))
        // The right PIN waits too, and so do the other requests that need it.
        assertError(Errors.PIN_WAIT, send("get_settings", "pin" to "1234"))
        assertError(Errors.PIN_WAIT, send("export_sync", "pin" to "1234", "sections" to JSONArray().put("lock")))
        clock += 30_000
        assertEquals("settings", send("get_settings", "pin" to "1234").getString("type"))
    }

    @Test
    fun putSettingsAnswersSavedWithMessagesAndSettings() {
        pairPhone()
        val reply = send("put_settings", "settings" to JSONObject().put("channels", JSONObject().put("strip_prefixes", true)))
        assertEquals("saved", reply.getString("type"))
        assertEquals("Saved.", reply.getJSONArray("messages").getString(0))
        assertTrue(reply.getJSONObject("settings").getJSONObject("channels").getBoolean("strip_prefixes"))
        assertError(Errors.BAD_REQUEST, send("put_settings"))
    }

    @Test
    fun exportSyncChecksTheSectionNames() {
        pairPhone()
        assertEquals("sync_data", send("export_sync", "sections" to JSONArray().put("logins").put("lock")).getString("type"))
        assertError(Errors.BAD_REQUEST, send("export_sync"))
        assertError(Errors.BAD_REQUEST, send("export_sync", "sections" to JSONArray()))
        assertError(Errors.BAD_REQUEST, send("export_sync", "sections" to JSONArray().put("webhook")))
    }

    @Test
    fun importSyncChecksTheFormat() {
        pairPhone()
        val good = JSONObject().put("format", 1).put("sections", JSONObject())
        val reply = send("import_sync", "data" to good)
        assertEquals("saved", reply.getString("type"))
        assertEquals("Done.", reply.getJSONArray("messages").getString(0))
        assertError(Errors.UNSUPPORTED, send("import_sync", "data" to JSONObject().put("format", 2).put("sections", JSONObject())))
        assertError(Errors.BAD_REQUEST, send("import_sync", "data" to JSONObject().put("format", 1)))
        assertError(Errors.BAD_REQUEST, send("import_sync"))
    }
}
