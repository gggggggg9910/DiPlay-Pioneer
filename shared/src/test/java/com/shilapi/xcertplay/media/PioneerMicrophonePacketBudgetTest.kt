package com.shilapi.xcertplay.media

import android.Manifest
import com.shilapi.xcertplay.airplay.AirPlayCrypto
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.consumeMicrophoneCapture
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.File
import java.nio.file.Files
import com.shilapi.xcertplay.network.UserspaceLink
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29], manifest = Config.NONE)
class PioneerMicrophonePacketBudgetTest {
    private fun config() = MicrophoneConfig("speechrecognition", 48_000, 1, 100, 20,
        InetAddress.getByName("fe80::2"), 50_000, ByteArray(32) { (it + 1).toByte() })

    @Test fun allPcmRatesFitMinimumIpv6MtuAndOpusKeepsItsFrame() {
        for (rate in listOf(8_000, 16_000, 24_000, 32_000, 44_100, 48_000)) {
            for (channels in 1..2) {
                val bounded = config().copy(sampleRate = rate, channels = channels).withPcmPacketBudget()
                assertTrue("rate=$rate channels=$channels", bounded.frameBytes + 84 <= 1280)
                assertEquals(rate * bounded.frameMillis / 1000, bounded.rtpSamplesPerPacket)
            }
        }
        assertEquals(5, config().copy(frameMillis = 5).withPcmPacketBudget().frameMillis)
        // A negotiated frame that already fits the MTU keeps the phone's cadence (Siri's 24 kHz 20 ms).
        assertEquals(20, config().copy(sampleRate = 24_000).withPcmPacketBudget().frameMillis)
        // 48 kHz 20 ms does not fit; it halves once instead of jumping to a fixed 10 ms rule.
        assertEquals(10, config().withPcmPacketBudget().frameMillis)
        val opus = config().copy(codec = AudioCodecKind.OPUS)
        assertSame(opus, opus.withPcmPacketBudget())
    }

    @Test fun capturedPcmReachesUserspaceWithContinuousClockAndUnchangedSamples() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val directory = Files.createTempDirectory("microphone-capture-test").toFile()
        val bounded = config().withPcmPacketBudget().copy(captureDirectory = directory)
        val packets = CopyOnWriteArrayList<ByteArray>()
        val sent = CountDownLatch(3)
        val reads = AtomicInteger()
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    Thread.sleep(10)
                    if (reads.getAndIncrement() >= 3) return 0
                    repeat(bounded.frameBytes) { buffer[offset + it] = if (it % 2 == 0) 0x34 else 0x12 }
                    return bounded.frameBytes
                }
            }
        }
        UserspaceLink.activate { _, destination, port, packet ->
            assertEquals(bounded.host, destination)
            assertEquals(bounded.port, port)
            packets += packet
            sent.countDown()
            true
        }
        val uplink = MicrophoneUplink(bounded)
        try {
            assertTrue(uplink.start())
            assertTrue(sent.await(5, TimeUnit.SECONDS))
            assertEquals(3, packets.size)
            packets.forEachIndexed { index, packet ->
                assertTrue(packet.size + 48 <= 1280)
                assertEquals(index * 480, ByteBuffer.wrap(packet).getInt(4))
                val sealedEnd = packet.size - 8
                val nonce = ByteArray(12).also { packet.copyInto(it, 4, sealedEnd) }
                val pcm = AirPlayCrypto.chachaOpen(bounded.key, nonce,
                    packet.copyOfRange(12, sealedEnd), packet.copyOfRange(4, 12))
                assertEquals(960, pcm.size)
                pcm.indices.forEach { assertEquals(if (it % 2 == 0) 0x12.toByte() else 0x34.toByte(), pcm[it]) }
            }
            uplink.close()
            DataInputStream(FileInputStream(directory.listFiles()!!.single())).use { capture ->
                val magic = ByteArray(8).also(capture::readFully)
                assertEquals("XCAUDIO1", String(magic, Charsets.US_ASCII))
                capture.readInt(); capture.readInt(); capture.readLong()
                packets.forEachIndexed { index, packet ->
                    val wireLength = capture.readInt()
                    val plainLength = capture.readInt()
                    assertEquals(index * 480, capture.readInt())
                    capture.readLong()
                    assertEquals(0, capture.readInt())
                    assertArrayEquals(packet, ByteArray(wireLength).also(capture::readFully))
                    val plain = ByteArray(plainLength).also(capture::readFully)
                    assertEquals(972, plain.size)
                    assertEquals(0x12.toByte(), plain[12])
                    assertEquals(0x34.toByte(), plain[13])
                }
                assertEquals(-1, capture.read())
            }
        } finally {
            uplink.close()
            UserspaceLink.deactivate()
            ShadowAudioRecord.clearSource()
            directory.deleteRecursively()
        }
    }

    @Test fun captureRequiresAnExplicitMarkerAndIsConsumedOnce() {
        val directory = Files.createTempDirectory("microphone-opt-in-test").toFile()
        try {
            val marker = File(directory, "microphone-capture.once")
            assertNull(consumeMicrophoneCapture(null))
            assertNull(consumeMicrophoneCapture(marker))
            marker.writeText("explicit local diagnostic")
            assertEquals(File(directory, "microphone-captures"), consumeMicrophoneCapture(marker))
            assertFalse(marker.exists())
            assertNull(consumeMicrophoneCapture(marker))
        } finally { directory.deleteRecursively() }
    }
}
