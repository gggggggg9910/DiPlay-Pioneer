package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoPipelineStatsTest {
    @Test fun overlappingFramesHaveLongLatencyButShortOutputIntervals() {
        var now = 1_000_000_000L
        val stats = VideoStats(nanoTime = { now })
        repeat(3) { stats.onReceived(100); stats.onCodecSubmitted() }
        stats.onInputWait(3_000_000)
        repeat(3) {
            now += 17_000_000
            stats.onCodecOutput()
            stats.onDecodeLatency(50_000_000)
            stats.onRendered()
        }
        now = 6_100_000_000L
        val report = checkNotNull(stats.logIfDue())
        assertTrue(report, report.contains("p50=50ms"))
        assertTrue(report, report.contains("outputGapP50Ms=17"))
        assertTrue(report, report.contains("inFlight=0 inFlightMax=3"))
        assertTrue(report, report.contains("submitted=3 codecOutputs=3"))
        assertTrue(report, report.contains("inputWaitMaxMs=3"))
    }

    @Test fun windowsRetainOutstandingFramesButCodecResetDoesNot() {
        var now = 1_000_000_000L
        val stats = VideoStats(nanoTime = { now })
        stats.onReceived(10)
        stats.onCodecSubmitted()
        stats.onCodecSubmitted()
        stats.onCodecOutput()
        now += 5_100_000_000
        assertTrue(checkNotNull(stats.logIfDue()).contains("inFlight=1 inFlightMax=2"))
        stats.onReceived(10)
        now += 5_100_000_000
        assertTrue(checkNotNull(stats.logIfDue()).contains("inFlight=1 inFlightMax=1"))
        stats.onCodecReset()
        stats.onCodecOutput() // stale notifications must not make the count negative
        stats.onReceived(10)
        now += 5_100_000_000
        assertTrue(checkNotNull(stats.logIfDue()).contains("inFlight=0"))
    }
}
