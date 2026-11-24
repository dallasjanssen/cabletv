package com.dallas.cabletv.data.companion.client

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MessagesTest {

    @Test
    fun requestsCarryTheirFieldsAndPinOnlyWhenThereIsOne() {
        assertEquals("hello", CompanionRequests.hello("Pixel", 7).getString("type"))
        assertEquals("Pixel", CompanionRequests.hello("Pixel", 7).getString("phone_name"))
        assertEquals(7, CompanionRequests.hello("Pixel", 7).getInt("app_build"))
        assertEquals("BACK", CompanionRequests.key("BACK").getString("key"))
        assertEquals("cabletv://live", CompanionRequests.open("cabletv://live").getString("link"))
        assertFalse(CompanionRequests.getSettings(null).has("pin"))
        assertFalse(CompanionRequests.getSettings("").has("pin"))
        assertEquals("1234", CompanionRequests.getSettings("1234").getString("pin"))
        val put = CompanionRequests.putSettings("1234", JSONObject().put("channels", JSONObject()))
        assertEquals("put_settings", put.getString("type"))
        assertTrue(put.getJSONObject("settings").has("channels"))
        val export = CompanionRequests.exportSync(null, listOf("logins", "lock"))
        assertEquals(listOf("logins", "lock"), export.stringList("sections"))
        val import = CompanionRequests.importSync("9999", JSONObject().put("format", 1))
        assertEquals(1, import.getJSONObject("data").getInt("format"))
        assertEquals("9999", import.getString("pin"))
    }

    @Test
    fun channelLinkEncodesTheId() {
        assertEquals("cabletv://channel?id=1234", CompanionRequests.channelLink("1234"))
        assertEquals("cabletv://channel?id=a%26b%3Dc+d%2F1", CompanionRequests.channelLink("a&b=c d/1"))
    }

    @Test
    fun channelLinkReadsBackThroughTheTvsOwnParser() {
        // The TV reads these with the same rules as Home Assistant links, so the id must survive.
        val id = "x&y=z 1"
        val link = CompanionRequests.channelLink(id)
        val value = link.substringAfter("?id=")
        assertEquals(id, java.net.URLDecoder.decode(value, "UTF-8"))
    }

    @Test
    fun requestsAreNumberedAndMatchedToReplies() {
        val matcher = ReplyMatcher()
        val a = matcher.prepare(JSONObject().put("type", "x"))
        val b = matcher.prepare(JSONObject().put("type", "y"))
        assertEquals(1, a.id)
        assertEquals(2, b.id)
        assertEquals(2, matcher.waitingCount())
        assertTrue(matcher.deliver(JSONObject().put("re", 2).put("type", "ok")))
        assertTrue(b.reply.isCompleted)
        assertFalse(a.reply.isCompleted)
        assertEquals("ok", runBlocking { b.reply.await() }.getString("type"))
    }

    @Test
    fun messagesWithoutAMatchingReAreLeftForTheCaller() {
        val matcher = ReplyMatcher()
        matcher.prepare(JSONObject())
        assertFalse(matcher.deliver(JSONObject().put("type", "state")))
        assertFalse(matcher.deliver(JSONObject().put("re", 99)))
        assertFalse(matcher.deliver(JSONObject().put("re", JSONObject.NULL)))
        assertEquals(1, matcher.waitingCount())
    }

    @Test
    fun theIdIsWrittenIntoTheMessage() {
        val message = JSONObject().put("type", "ping")
        val pending = ReplyMatcher().prepare(message)
        assertEquals(pending.id, message.getInt("id"))
    }

    @Test
    fun cancelledRequestIgnoresALateReply() {
        val matcher = ReplyMatcher()
        val p = matcher.prepare(JSONObject())
        matcher.cancel(p.id)
        assertFalse(matcher.deliver(JSONObject().put("re", p.id)))
    }

    @Test
    fun failAllEndsWaitingAndLaterRequests() {
        val matcher = ReplyMatcher()
        val waiting = matcher.prepare(JSONObject())
        val boom = CompanionException.lost("Den TV")
        matcher.failAll(boom)
        val later = matcher.prepare(JSONObject())
        for (p in listOf(waiting, later)) {
            try {
                runBlocking { p.reply.await() }
                fail("should have failed")
            } catch (e: CompanionException) {
                assertEquals(boom.message, e.message)
            }
        }
        assertEquals(0, matcher.waitingCount())
    }

    @Test
    fun stateReadsEveryFieldAndTreatsMissingOnesAsAbsent() {
        val s = TvState.fromJson(
            JSONObject(
                """{"type":"state","screen":"live","state":"playing","kind":"live","title":"SportsCenter",
                "channel":"ESPN","channel_number":206,"channel_id":"1234","image":"http://x/y.png"}"""
            )
        )
        assertEquals("live", s.screen)
        assertEquals("SportsCenter", s.title)
        assertEquals("ESPN", s.channel)
        assertEquals(206, s.channelNumber)
        assertEquals("1234", s.channelId)
        assertFalse(s.isIdle)
        assertFalse(s.isPaused)

        val idle = TvState.fromJson(JSONObject("""{"type":"state","screen":"home","state":"idle"}"""))
        assertTrue(idle.isIdle)
        assertNull(idle.title)
        assertNull(idle.channelNumber)

        assertTrue(TvState.fromJson(JSONObject("""{"state":"paused","title":"News"}""")).isPaused)
    }

    @Test
    fun favoritesSkipEntriesWithoutAnIdAndKeepOptionalFieldsOptional() {
        val reply = JSONObject(
            """{"type":"favorites","favorites":[{"id":"1","name":"ESPN","number":206,"logo":"http://l"},
            {"id":"2","name":"CNN"},{"name":"No id"},{"id":"3"}]}"""
        )
        val list = Favorite.listFrom(reply)
        assertEquals(3, list.size)
        assertEquals(Favorite("1", "ESPN", 206, "http://l"), list[0])
        assertEquals(Favorite("2", "CNN", 0, null), list[1])
        assertEquals("3", list[2].name)
        assertTrue(Favorite.listFrom(JSONObject()).isEmpty())
    }

    @Test
    fun welcomeReadsTheTvsAnswer() {
        val w = Welcome.fromJson(
            JSONObject("""{"paired":true,"tv_name":"Den","tv_id":"abc","app_build":12,"pin_set":true,"protocol":1}""")
        )
        assertTrue(w.paired)
        assertEquals("Den", w.tvName)
        assertEquals("abc", w.tvId)
        assertTrue(w.pinSet)
    }

    @Test
    fun errorMessagesUseTheTvsSentenceOrAPlainFallback() {
        val withText = CompanionException.fromError(
            JSONObject("""{"type":"error","code":"wrong_pin","message":"That PIN did not match."}""")
        )
        assertEquals("That PIN did not match.", withText.message)
        assertEquals("wrong_pin", withText.code)
        assertEquals(CompanionException.Kind.REMOTE, withText.kind)

        val without = CompanionException.fromError(JSONObject("""{"type":"error","code":"pin_wait"}"""))
        assertTrue(without.message!!.isNotBlank())
        assertEquals("Can't reach Den TV. Is Cable TV open on it?", CompanionException.unreachable("Den TV").message)
        assertEquals("This TV's identity changed. Pair it again.", CompanionException.identityChanged().message)
    }

    @Test
    fun pairingErrorsAreCountedAndPlain() {
        fun text(json: String) = pairingErrorText(CompanionException.fromError(JSONObject(json)))
        assertEquals("That code did not match. 4 tries left.", text("""{"code":"wrong_code","attempts_left":4}"""))
        assertEquals("That code did not match. 1 try left.", text("""{"code":"wrong_code","attempts_left":1}"""))
        assertEquals("That code did not match. Check the TV and try again.", text("""{"code":"wrong_code"}"""))
        assertTrue(text("""{"code":"code_expired"}""").contains("expired"))
        assertTrue(text("""{"code":"locked"}""").contains("Too many"))
    }

    @Test
    fun noTextShownToPeopleHasADashOrDoubleHyphen() {
        val samples = listOf(
            CompanionException.unreachable("A").message, CompanionException.timeout("A").message,
            CompanionException.lost("A").message, CompanionException.IDENTITY_CHANGED_TEXT
        ) + listOf("pin_required", "wrong_pin", "pin_wait", "busy", "unsupported", "invalid", "not_paired", "other")
            .map { CompanionException.remoteText(it, "") }
        for (s in samples) {
            assertFalse(s, s!!.contains('\u2014') || s.contains("-" + "-"))
        }
    }

    @Test
    fun backoffGrowsAndStops() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 15000L, 15000L), (0..5).map { Backoff.delayMs(it) })
        assertEquals(1000L, Backoff.delayMs(-3))
    }
}
