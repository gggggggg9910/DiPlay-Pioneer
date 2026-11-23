package com.shilapi.xcertplay.media

import android.Manifest
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.network.UserspaceLink
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord

/**
 * iOS Siri teardowns its first speech stream — sometimes while our uplink for it is still
 * starting — and re-SETUPs the same stream at another rate. A stopped-before-started uplink and
 * a reused stale one both leave the phone's real stream without microphone audio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class AndroidMediaSinkUplinkReplacementTest {
    private fun speech(port: Int, rate: Int) = MicrophoneConfig(
        "speechrecognition", rate, 1, 100, 20,
        InetAddress.getByName("fe80::10"), port, ByteArray(32) { (it + 1).toByte() },
    )

    @Test fun stoppedThenRestartedAndReSetupStreamsGetTheirOwnUplink() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    Thread.sleep(5)
                    return size
                }
            }
        }
        UserspaceLink.activate { _, _, _, _ -> true }
        val lines = mutableListOf<String>()
        val sink = AndroidMediaSink(onAudioDiagnostic = { lines += it })
        val id = AudioStreamId(100, "speechrecognition")
        try {
            // The 48 kHz probe stream, stopped the way iOS stops it mid-start.
            sink.onMicrophoneStarted(id, speech(port = 52993, rate = 48_000))
            sink.onMicrophoneStopped(id)
            // The 24 kHz stream iOS re-SETUPs must get a fresh uplink of its own.
            sink.onMicrophoneStarted(id, speech(port = 61859, rate = 24_000))
            // A running uplink whose destination changed is replaced, not reused.
            sink.onMicrophoneStarted(id, speech(port = 7001, rate = 24_000))

            val uplinks = lines.filter { it.startsWith("Microphone: uplink") }
            assertEquals(3, uplinks.size)
            assertTrue(uplinks[0].contains(":52993"))
            assertTrue(uplinks[1].contains(":61859"))
            assertTrue(uplinks[2].contains(":7001"))
            assertTrue(uplinks[1].contains("frameBytes=960")) // 24 kHz 20 ms, the negotiated cadence
            assertTrue(lines.any { it.startsWith("Microphone: replacing uplink") })
        } finally {
            sink.close()
            UserspaceLink.deactivate()
            ShadowAudioRecord.clearSource()
        }
    }
}
