package com.dallas.cabletv.data.companion.server

import com.dallas.cabletv.data.companion.CompanionProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Executors

class PairedPhonesTest {
    private val a = PairedPhone("aa11", "Pixel 10", 1_000L)
    private val b = PairedPhone("bb22", "Galaxy", 2_000L)

    @Test
    fun theListSurvivesBeingStored() {
        val text = PairedPhones.encode(listOf(a, b))
        assertEquals(listOf(a, b), PairedPhones.decode(text))
    }

    @Test
    fun badTextGivesAnEmptyList() {
        assertEquals(emptyList<PairedPhone>(), PairedPhones.decode(null))
        assertEquals(emptyList<PairedPhone>(), PairedPhones.decode(""))
        assertEquals(emptyList<PairedPhone>(), PairedPhones.decode("not json"))
        assertEquals(emptyList<PairedPhone>(), PairedPhones.decode("[{\"name\":\"no fingerprint\"}]"))
    }

    @Test
    fun pairingAgainReplacesThePhone() {
        val again = a.copy(name = "Pixel 10 Pro", pairedAt = 3_000L)
        assertEquals(listOf(b, again), PairedPhones.with(listOf(a, b), again))
    }

    @Test
    fun removingOnePhoneLeavesTheOthers() {
        assertEquals(listOf(b), PairedPhones.without(listOf(a, b), "AA11"))
        assertTrue(PairedPhones.contains(listOf(a), "AA11"))
        assertFalse(PairedPhones.contains(listOf(a), "bb22"))
    }
}

class PushThrottleTest {
    private var clock = 10_000L
    private var sends = 0
    private val scheduled = mutableListOf<Pair<Long, Runnable>>()
    private val throttle = PushThrottle(1_000L, { clock }, { delay, task -> scheduled += delay to task }, { sends++ })

    @Test
    fun theFirstChangeSendsAtOnce() {
        throttle.poke()
        assertEquals(1, sends)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun changesInsideTheIntervalFoldIntoOneSendAtTheEnd() {
        throttle.poke()
        clock += 300
        throttle.poke()
        clock += 300
        throttle.poke()
        assertEquals(1, sends)
        assertEquals(1, scheduled.size)
        assertEquals(700L, scheduled[0].first)
        clock += 700
        scheduled[0].second.run()
        assertEquals(2, sends)
    }

    @Test
    fun aChangeAfterTheIntervalSendsAtOnceAgain() {
        throttle.poke()
        clock += 1_000
        throttle.poke()
        assertEquals(2, sends)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun theWaitingSendCountsAsASend() {
        throttle.poke()
        clock += 500
        throttle.poke()
        clock += 500
        scheduled[0].second.run()
        // Right after the folded send, the next change has to wait the full interval again.
        throttle.poke()
        assertEquals(2, sends)
        assertEquals(2, scheduled.size)
        assertEquals(1_000L, scheduled[1].first)
    }
}

class MainThreadTest {
    @Test
    fun runsTheWorkOnTheMainThreadAndReturnsItsAnswer() {
        val main = Executors.newSingleThreadExecutor()
        try {
            var mainThread: Thread? = null
            main.submit { mainThread = Thread.currentThread() }.get()
            val runner = MainThread(post = { main.execute(it) })
            val seen = runner.call { Thread.currentThread() }
            assertEquals(mainThread, seen)
        } finally {
            main.shutdown()
        }
    }

    @Test
    fun errorsFromTheWorkReachTheCaller() {
        val runner = MainThread(post = { it.run() })
        try {
            runner.call<Unit> { throw CompanionException(CompanionProtocol.Errors.INVALID, "Nope.") }
            fail()
        } catch (e: CompanionException) {
            assertEquals(CompanionProtocol.Errors.INVALID, e.code)
        }
    }

    @Test
    fun aMainThreadThatNeverAnswersGivesBusyAndSkipsTheWork() {
        val queue = mutableListOf<Runnable>()
        val runner = MainThread(post = { queue += it })
        var ran = false
        try {
            runner.call(50L) { ran = true }
            fail()
        } catch (e: CompanionException) {
            assertEquals(CompanionProtocol.Errors.BUSY, e.code)
        }
        // The main thread wakes up later. The abandoned work does not run.
        queue.forEach { it.run() }
        assertFalse(ran)
    }

    @Test
    fun callsFromTheMainThreadRunAtOnce() {
        val runner = MainThread(post = { fail() }, isMain = { true })
        assertEquals(7, runner.call { 7 })
    }
}
