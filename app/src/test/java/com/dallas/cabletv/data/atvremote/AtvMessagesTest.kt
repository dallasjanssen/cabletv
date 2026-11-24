package com.dallas.cabletv.data.atvremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expected bytes were made by the official protobuf library from the same message definitions
 * the reference remote library uses, not by this code.
 */
class AtvMessagesTest {
    private val secret = ByteArray(32) { (0x80 + it).toByte() }

    @Test
    fun pairingRequestBytes() {
        assertEquals(
            "080210c80152150a0961747672656d6f746512084361626c65205456",
            Polo.pairingRequest("Cable TV").toHex()
        )
    }

    @Test
    fun optionsBytes() {
        assertEquals("080210c801a201080a04080310061801", Polo.options().toHex())
    }

    @Test
    fun configurationBytes() {
        assertEquals("080210c801f201080a04080310061001", Polo.configuration().toHex())
    }

    @Test
    fun secretBytes() {
        assertEquals(
            "080210c801c202220a20808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f",
            Polo.secret(secret).toHex()
        )
    }

    @Test
    fun pairingRepliesFromTheTvAreRecognised() {
        assertEquals(Polo.Kind.PAIRING_REQUEST_ACK, Polo.parse(hex("080210c8015a0d0a0b4c6976696e6720726f6f6d")).kind)
        assertEquals(Polo.Kind.OPTIONS, Polo.parse(hex("080210c801a2010e0a04080310061204080310061802")).kind)
        assertEquals(Polo.Kind.CONFIGURATION_ACK, Polo.parse(hex("080210c801fa0100")).kind)
        val ack = Polo.parse(
            hex("080210c801ca02220a20808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        )
        assertEquals(Polo.Kind.SECRET_ACK, ack.kind)
        assertEquals(Polo.STATUS_OK, ack.status)
    }

    @Test
    fun badSecretStatusIsReported() {
        val m = Polo.parse(hex("0802109203"))
        assertEquals(Polo.STATUS_BAD_SECRET, m.status)
        assertEquals(Polo.Kind.OTHER, m.kind)
    }

    @Test
    fun keyPressBytes() {
        assertEquals("5204081a1003", RemoteWire.key(AtvKeys.POWER).toHex())
        assertEquals("520508ac011003", RemoteWire.key(AtvKeys.GUIDE).toHex())
    }

    @Test
    fun appLinkBytes() {
        assertEquals("d205100a0e6361626c6574763a2f2f6c697665", RemoteWire.appLink("cabletv://live").toHex())
    }

    @Test
    fun setupRepliesBytes() {
        assertEquals(615, RemoteWire.WANTED_FEATURES)
        assertEquals(
            "0a1c08e704121718012201312a0961747672656d6f74653205312e302e30",
            RemoteWire.configureReply(615).toHex()
        )
        assertEquals("120308e704", RemoteWire.setActiveReply(615).toHex())
    }

    @Test
    fun pingIsEchoedWithTheSameNumber() {
        assertEquals("4a020805", RemoteWire.pingResponse(5).toHex())
        assertEquals("4a00", RemoteWire.pingResponse(0).toHex())
        val ping = RemoteWire.parse(hex("420408071003")) as RemoteWire.Incoming.Ping
        assertEquals(7, ping.value)
        assertEquals(0, (RemoteWire.parse(hex("42021003")) as RemoteWire.Incoming.Ping).value)
    }

    @Test
    fun configureFromTheTvLimitsTheActiveFeatures() {
        val msg = RemoteWire.parse(
            hex(
                "0a4c08ef0412470a0e4c6976696e6720726f6f6d205456120441636d6518012201312a23636f6d2e676f6f676c652e" +
                    "616e64726f69642e747672656d6f74652e736572766963653205352e332e31"
            )
        ) as RemoteWire.Incoming.Configure
        assertEquals(623, msg.features)
        assertEquals(615, RemoteWire.activeFeatures(msg.features))
        // A TV without the on screen keyboard channel is asked for less.
        assertEquals(611, RemoteWire.activeFeatures(611 or 16))
        assertTrue(RemoteWire.parse(hex("120308ee04")) is RemoteWire.Incoming.SetActive)
    }

    @Test
    fun stateFollowsTheTvsMessages() {
        var state = AtvState(connected = true)
        state = RemoteWire.apply(state, RemoteWire.parse(hex("c202020801")))
        assertEquals(true, state.poweredOn)
        state = RemoteWire.apply(state, RemoteWire.parse(hex("920317080110011a07737065616b657220022803306438254001")))
        assertEquals(37, state.volumeLevel)
        assertEquals(100, state.volumeMax)
        assertEquals(true, state.muted)
        state = RemoteWire.apply(
            state,
            RemoteWire.parse(hex("a2012c0a2a08045207596f7554756265621d636f6d2e676f6f676c652e616e64726f69642e796f75747562652e7476"))
        )
        assertEquals("com.google.android.youtube.tv", state.currentApp)
        // Standby: started is false, which arrives as an empty message.
        state = RemoteWire.apply(state, RemoteWire.parse(hex("c20200")))
        assertEquals(false, state.poweredOn)
        assertTrue(state.connected)
        // An error and an unknown message change nothing.
        assertEquals(state, RemoteWire.apply(state, RemoteWire.parse(hex("1a020801"))))
        assertEquals(state, RemoteWire.apply(state, RemoteWire.parse(hex("f80100"))))
        assertNull(AtvState().currentApp)
    }
}
