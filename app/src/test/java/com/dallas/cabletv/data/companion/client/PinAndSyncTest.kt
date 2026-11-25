package com.dallas.cabletv.data.companion.client

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PinAndSyncTest {
    private fun pinError(code: String) = CompanionException.fromError(JSONObject().put("code", code))

    @Test
    fun noPinNeededRunsOnceWithoutAsking() = runBlocking {
        val memory = PinMemory()
        var asked = 0
        val result = withPin("tv", memory, { asked++; "1111" }) { pin ->
            assertNull(pin)
            "done"
        }
        assertEquals("done", result)
        assertEquals(0, asked)
        assertNull(memory.get("tv"))
    }

    @Test
    fun pinRequiredAsksOnceThenRemembersAGoodPin() = runBlocking {
        val memory = PinMemory()
        val asks = mutableListOf<Boolean>()
        val calls = mutableListOf<String?>()
        val result = withPin("tv", memory, { asks.add(it); "4321" }) { pin ->
            calls.add(pin)
            if (pin == null) throw pinError("pin_required")
            "settings"
        }
        assertEquals("settings", result)
        assertEquals(listOf(false), asks)
        assertEquals(listOf<String?>(null, "4321"), calls)
        assertEquals("4321", memory.get("tv"))

        // The next call starts with the remembered PIN and asks nothing.
        var again = 0
        withPin("tv", memory, { again++; null }) { pin -> assertEquals("4321", pin) }
        assertEquals(0, again)
    }

    @Test
    fun wrongPinForgetsItAndAsksAgainSayingSo() = runBlocking {
        val memory = PinMemory()
        memory.set("tv", "0000")
        val asks = mutableListOf<Boolean>()
        val answers = ArrayDeque(listOf("1111", "2222"))
        val result = withPin("tv", memory, { asks.add(it); answers.removeFirst() }) { pin ->
            if (pin != "2222") throw pinError("wrong_pin")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf(true, true), asks)
        assertEquals("2222", memory.get("tv"))
    }

    @Test
    fun cancellingTheDialogStopsWithoutRememberingAnything() = runBlocking {
        val memory = PinMemory()
        try {
            withPin("tv", memory, { null }) { throw pinError("pin_required") }
            fail("cancelled")
        } catch (e: PinCancelledException) {
            assertNull(memory.get("tv"))
        }
    }

    @Test
    fun pinWaitAndOtherErrorsGoStraightToTheCaller() = runBlocking {
        var asked = false
        for (code in listOf("pin_wait", "busy", "invalid")) {
            try {
                withPin("tv", PinMemory(), { asked = true; "1" }) { throw pinError(code) }
                fail("should fail")
            } catch (e: CompanionException) {
                assertEquals(code, e.code)
            }
        }
        assertEquals(false, asked)
    }

    @Test
    fun pinsAreKeptPerTv() {
        val memory = PinMemory()
        memory.set("a", "1111")
        memory.set("b", "2222")
        memory.clear("a")
        assertNull(memory.get("a"))
        assertEquals("2222", memory.get("b"))
    }

    // Sync

    private class FakeRequester(private val answer: (JSONObject) -> JSONObject) : TvRequester {
        val sent = mutableListOf<JSONObject>()
        override suspend fun request(message: JSONObject): JSONObject {
            sent.add(message)
            return answer(message)
        }
    }

    private val living = PairedTv("1", "Living Room TV", "aa", "h1", 1)
    private val bedroom = PairedTv("2", "Bedroom TV", "bb", "h2", 2)

    @Test
    fun copyExportsFromTheSourceAndImportsIntoTheTargetThenReturnsItsMessages() = runBlocking {
        val data = JSONObject().put("format", 1).put("sections", JSONObject().put("lock", JSONObject()))
        val source = FakeRequester { JSONObject().put("type", "sync_data").put("data", data) }
        val target = FakeRequester { JSONObject().put("type", "saved").put("messages", org.json.JSONArray(listOf("Favorites copied.", "Lock set."))) }
        val runner = SyncRunner(PinMemory()) { _, _ -> fail("no PIN expected"); null }
        val messages = runner.copy(living, source, bedroom, target, listOf("favorites", "lock"))
        assertEquals(listOf("Favorites copied.", "Lock set."), messages)
        assertEquals("export_sync", source.sent.single().getString("type"))
        assertEquals(listOf("favorites", "lock"), source.sent.single().stringList("sections"))
        val imported = target.sent.single()
        assertEquals("import_sync", imported.getString("type"))
        assertEquals(1, imported.getJSONObject("data").getInt("format"))
    }

    @Test
    fun eachTvAsksForItsOwnPin() = runBlocking {
        val source = FakeRequester { m ->
            if (m.optString("pin") != "1111") throw pinError("pin_required")
            JSONObject().put("type", "sync_data").put("data", JSONObject())
        }
        val target = FakeRequester { m ->
            if (m.optString("pin") != "2222") throw pinError("pin_required")
            JSONObject().put("type", "saved").put("messages", org.json.JSONArray())
        }
        val asked = mutableListOf<String>()
        val memory = PinMemory()
        val runner = SyncRunner(memory) { id, _ -> asked.add(id); if (id == "1") "1111" else "2222" }
        runner.copy(living, source, bedroom, target, listOf("logins"))
        assertEquals(listOf("1", "2"), asked)
        assertEquals("1111", memory.get("1"))
        assertEquals("2222", memory.get("2"))
        assertEquals("2222", target.sent.last().getString("pin"))
    }

    @Test
    fun theTwoTvsMustDifferAndSomethingMustBeChosen() = runBlocking {
        val any = FakeRequester { JSONObject() }
        val runner = SyncRunner(PinMemory()) { _, _ -> null }
        assertTrue(runCatching { runner.copy(living, any, living, any, listOf("logins")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { runner.copy(living, any, bedroom, any, emptyList()) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(any.sent.isEmpty())
    }

    @Test
    fun aTargetFailureStopsAfterTheExportAndCarriesTheTvsMessage() = runBlocking {
        val source = FakeRequester { JSONObject().put("type", "sync_data").put("data", JSONObject()) }
        val target = FakeRequester { throw CompanionException.fromError(JSONObject().put("code", "busy").put("message", "Busy right now.")) }
        val runner = SyncRunner(PinMemory()) { _, _ -> null }
        try {
            runner.copy(living, source, bedroom, target, listOf("logins"))
            fail("should fail")
        } catch (e: CompanionException) {
            assertEquals("Busy right now.", e.message)
        }
    }

    @Test
    fun allFiveChoicesAreOfferedInTheDocumentedOrder() {
        assertEquals(listOf("logins", "channels", "favorites", "lock", "lineup"), SyncChoice.ALL.map { it.key })
        assertEquals(
            listOf("Logins", "Channel customizations", "Favorites", "Parental lock", "Channel lineup"),
            SyncChoice.ALL.map { it.label }
        )
    }
}
