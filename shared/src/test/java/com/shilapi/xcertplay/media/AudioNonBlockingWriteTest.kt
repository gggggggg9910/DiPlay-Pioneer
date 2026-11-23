package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28], manifest = Config.NONE, shadows = [AudioNonBlockingWriteTest.PressureTrack::class])
class AudioNonBlockingWriteTest {
    @Before fun reset() {
        PressureTrack.playing = false
        PressureTrack.permanentZero = false
        PressureTrack.error = 0
        PressureTrack.calls = 0
        PressureTrack.blocking = false
        PressureTrack.failStateGetter = false
        PressureTrack.accepted = ByteArrayOutputStream()
    }

    @Test fun fullPrebufferStartsPlaybackAndPartialWritesKeepAllPcm() = withRenderer { renderer, type, reports ->
        val pcm = ByteArray(256) { it.toByte() }
        write(type, renderer, pcm)
        assertTrue(PressureTrack.playing)
        assertFalse(PressureTrack.blocking)
        assertArrayEquals(pcm, PressureTrack.accepted.toByteArray())
        assertTrue(reports.any { it.contains("early play after full prebuffer") })
    }

    @Test(timeout = 5_000) fun permanentlyFullTrackReturnsAfterBoundedRetries() = withRenderer { renderer, type, reports ->
        PressureTrack.permanentZero = true
        write(type, renderer, ByteArray(256))
        assertTrue(PressureTrack.calls in 2..1000)
        assertFalse(PressureTrack.blocking)
        assertTrue(reports.any { it.contains("write no progress") })
        assertEquals(0, PressureTrack.accepted.size())
    }

    @Test fun negativeWriteCodeIsReportedImmediately() = withRenderer { renderer, type, reports ->
        PressureTrack.error = AudioTrack.ERROR_INVALID_OPERATION
        write(type, renderer, ByteArray(256))
        assertEquals(1, PressureTrack.calls)
        assertTrue(reports.any { it.contains("write failed") && it.contains("code=-3") })
    }

    @Test fun watchdogReadsOnlyCachedStateEvenWhenTrackGettersFail() = withRenderer { renderer, type, _ ->
        PressureTrack.failStateGetter = true
        val snapshot = type.getDeclaredMethod("watchSnapshot").apply { isAccessible = true }.invoke(renderer) as String
        assertTrue(snapshot.contains("AudioWatch"))
        assertTrue(snapshot.contains("playStateCached="))
        assertTrue(snapshot.contains("stateAgeMs="))
    }

    private fun write(type: Class<*>, renderer: Any, bytes: ByteArray) {
        type.getDeclaredMethod("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(renderer, bytes, 0, bytes.size)
    }

    private fun withRenderer(check: (Any, Class<*>, MutableList<String>) -> Unit) {
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(48_000)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(16_384).build()
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val reports = mutableListOf<String>()
        val renderer = type.declaredConstructors.single { it.parameterCount == 13 }.apply { isAccessible = true }
            .newInstance(AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"), false, false, 0, 0,
                AudioFocusCoordinator(null, false, false), 0, 300, { message: String -> reports.add(message) },
                0L, false, null, false)
        fun set(name: String, value: Any) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
        set("track", track)
        set("mappedChannel", AudioChannel.MEDIA)
        set("startThresholdBytes", 4096)
        set("fadeApplied", true)
        try { check(renderer, type, reports) } finally {
            PressureTrack.failStateGetter = false
            (renderer as java.io.Closeable).close()
            track.release()
        }
    }

    @Implements(AudioTrack::class)
    class PressureTrack {
        @Implementation fun getState(): Int {
            check(!failStateGetter) { "watchdog called native state getter" }
            return AudioTrack.STATE_INITIALIZED
        }
        @Implementation fun getPlayState(): Int {
            check(!failStateGetter) { "watchdog called native play getter" }
            return if (playing) AudioTrack.PLAYSTATE_PLAYING else AudioTrack.PLAYSTATE_STOPPED
        }
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun getPlaybackHeadPosition() = 0
        @Implementation fun play() { playing = true }
        @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int {
            calls++
            if (mode == AudioTrack.WRITE_BLOCKING) blocking = true
            if (error < 0) return error
            if (!playing || permanentZero) return 0
            val count = minOf(size, 64)
            accepted.write(bytes, offset, count)
            return count
        }
        companion object {
            var playing = false
            var permanentZero = false
            var error = 0
            var calls = 0
            var blocking = false
            var failStateGetter = false
            var accepted = ByteArrayOutputStream()
        }
    }
}
