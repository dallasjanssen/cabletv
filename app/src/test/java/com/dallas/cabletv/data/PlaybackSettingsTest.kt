package com.dallas.cabletv.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PlaybackSettingsTest {

    @Before
    fun resetGate() {
        repeat(16) { ConnectionGate.release() }
        ConnectionGate.setLimit(0)
    }

    @After
    fun clearGate() {
        repeat(16) { ConnectionGate.release() }
        ConnectionGate.setLimit(0)
    }

    @Test
    fun unlimitedAcquireDoesNotUseUpALaterCap() {
        ConnectionGate.setLimit(0)
        assertTrue(ConnectionGate.tryAcquire())
        ConnectionGate.release()
        ConnectionGate.setLimit(1)
        assertTrue(ConnectionGate.tryAcquire())
        assertFalse(ConnectionGate.tryAcquire())
        ConnectionGate.release()
        assertTrue(ConnectionGate.tryAcquire())
    }

    @Test
    fun concurrentAcquiresStopAtTheCap() {
        ConnectionGate.setLimit(4)
        val acquired = AtomicInteger(0)
        val threads = List(8) {
            Thread {
                if (ConnectionGate.tryAcquire()) acquired.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(4, acquired.get())
    }

    @Test
    fun audioOffsetStaysInsideTheStoredRange() {
        assertEquals(0, clampedAudioOffset(0))
        assertEquals(2_000, clampedAudioOffset(9_000))
        assertEquals(-2_000, clampedAudioOffset(-9_000))
    }

    @Test
    fun softwareDecodersMoveAheadWithoutReorderingTheRest() {
        val names = listOf(
            "OMX.qcom.video.decoder.avc",
            "OMX.google.h264.decoder",
            "c2.android.avc.decoder",
        )
        assertEquals(
            listOf("OMX.google.h264.decoder", "c2.android.avc.decoder", "OMX.qcom.video.decoder.avc"),
            orderedDecoderNames(names, preferSoftware = true, dropAmlogic = false),
        )
        assertEquals(names, orderedDecoderNames(names, preferSoftware = false, dropAmlogic = false))
    }

    @Test
    fun amlogicNamesAreDropped() {
        val names = listOf(
            "OMX.amlogic.avc.decoder.awesome",
            "c2.amlogic.hevc.decoder",
            "OMX.aml.video.decoder",
            "OMX.qcom.video.decoder.avc",
        )
        assertEquals(
            listOf("OMX.qcom.video.decoder.avc"),
            orderedDecoderNames(names, preferSoftware = false, dropAmlogic = true),
        )
        assertFalse(isAmlogicCodec("OMX.qcom.video.decoder.avc"))
        assertTrue(isSoftwareCodec("c2.android.aac.decoder"))
    }

    @Test
    fun autoFrameRateStaysOnTheCurrentResolution() {
        val current = mode(1, 1920, 1080, 60f)
        val modes = listOf(
            current,
            mode(2, 1920, 1080, 50f),
            mode(3, 3840, 2160, 50f),
        )
        val chosen = chooseDisplayMode(
            modes,
            current,
            frameRate = 50f,
            autoFrameRate = true,
            filmRatesOnly = false,
            switchResolution = true,
        )
        assertEquals(2, chosen?.id)
    }

    @Test
    fun filmRatesOnlySkipsRatesAwayFromFiftyAndSixty() {
        val current = mode(1, 1920, 1080, 60f)
        val modes = listOf(current, mode(2, 1920, 1080, 24f))
        assertNull(
            chooseDisplayMode(
                modes,
                current,
                frameRate = 24f,
                autoFrameRate = true,
                filmRatesOnly = true,
                switchResolution = false,
            )
        )
        assertTrue(isFiftyOrSixty(59.94f))
        assertFalse(isFiftyOrSixty(48.4f))
        val pulled = chooseDisplayMode(
            listOf(current, mode(3, 1920, 1080, 59.94f)),
            current,
            frameRate = 59.94f,
            autoFrameRate = true,
            filmRatesOnly = true,
            switchResolution = false,
        )
        assertEquals(3, pulled?.id)
    }

    @Test
    fun switchesOffDoNotPickAMode() {
        val current = mode(1, 1920, 1080, 60f)
        assertNull(
            chooseDisplayMode(
                listOf(current, mode(2, 1920, 1080, 50f)),
                current,
                frameRate = 50f,
                autoFrameRate = false,
                filmRatesOnly = true,
                switchResolution = false,
            )
        )
    }

    private fun mode(id: Int, width: Int, height: Int, refresh: Float) =
        DisplayModeSpec(id, width, height, refresh)
}
