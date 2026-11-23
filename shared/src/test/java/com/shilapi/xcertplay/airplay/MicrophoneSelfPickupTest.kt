package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * iPhone self-pickup mode: with the microphone flag off, a Siri input SETUP still gets a normal
 * answer (same ports, so the phone cannot tell the difference) but no uplink is ever queued.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class MicrophoneSelfPickupTest {
    @Test fun speechSetupWithoutMicrophoneAnswersButNeverStartsTheUplink() {
        val sink = recordingSink()
        val session = session()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = false)
        try {
            val response = engine.onAudio(session, 100, speechSetup())
            assertNotNull(response)
            assertTrue("answer keeps a real data port", (response?.get("dataPort") as? Number)?.toInt()!! > 0)
            assertTrue("no uplink is queued", pendingMicrophone(engine).isEmpty())
            assertTrue("the sink is never asked for the microphone", sink.started.isEmpty())
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun speechSetupWithMicrophoneEnabledQueuesTheUplink() {
        val sink = recordingSink()
        val session = session()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        try {
            assertNotNull(engine.onAudio(session, 100, speechSetup()))
            assertEquals(
                "the same SETUP would queue the uplink with the flag on",
                setOf(CarPlayMediaEngine.StreamKey(session, 100, "speechrecognition")),
                pendingMicrophone(engine).keys,
            )
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    private class RecordingSink : MediaSink {
        val started = mutableListOf<AudioStreamId>()
        override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
            started.add(id)
        }
    }

    private fun recordingSink() = RecordingSink()

    private fun speechSetup(): Map<String, Any?> = mapOf(
        "audioType" to "speechrecognition",
        "audioFormat" to 0x8000L,
        "streamConnectionID" to 42L,
        // The port the iPhone opened for our microphone audio.
        "dataPort" to 5000L,
    )

    @Suppress("UNCHECKED_CAST")
    private fun pendingMicrophone(engine: CarPlayMediaEngine) =
        CarPlayMediaEngine::class.java.getDeclaredField("pendingMicrophone").apply { isAccessible = true }
            .get(engine) as Map<CarPlayMediaEngine.StreamKey, MicrophoneConfig>

    private fun session(): AirPlaySession {
        // The input stream SETUP carries a return address; give the session a connected socket so
        // remoteAddress exists like on a real phone link.
        val listener = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val socket = Socket()
        try {
            socket.connect(listener.localSocketAddress, 2_000)
        } finally {
            listener.close()
        }
        val session = AirPlaySession(
            socket = socket,
            config = AirPlayConfig(
                deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {},
        )
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, secret)
        return session
    }
}
