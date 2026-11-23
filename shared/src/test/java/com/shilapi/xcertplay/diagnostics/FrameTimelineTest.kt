package com.shilapi.xcertplay.diagnostics

import org.junit.Assert.*
import org.junit.Test

class FrameTimelineTest {
    @Test fun comparesIntervalsWithoutAssumingPhoneAndHostShareAnEpoch() {
        val logs = mutableListOf<String>()
        val stalls = mutableListOf<String>()
        val timeline = FrameTimeline(logs::add) { stage, _, detail -> stalls.add("$stage $detail") }
        timeline.frame(9_000_000_000_000, 1_000_000_000)
        timeline.frame(9_000_016_000_000, 1_200_000_000)
        timeline.flush(1_200_000_000)
        assertTrue(logs.single().contains("sourceGapP95Us=16000 arrivalGapP95Us=200000 intervalExcessP95Us=184000"))
        assertTrue(stalls.single().contains("sourceGapMs=16 sourceValid=true"))
    }

    @Test fun phonePauseIsVisibleAsSourcePauseRatherThanInventedLocalDelay() {
        val logs = mutableListOf<String>()
        val stalls = mutableListOf<String>()
        val timeline = FrameTimeline(logs::add) { _, _, detail -> stalls.add(detail) }
        timeline.frame(1_000_000_000, 2_000_000_000)
        timeline.frame(1_500_000_000, 2_500_000_000)
        timeline.flush(2_500_000_000)
        assertTrue(logs.single().contains("intervalExcessP95Us=0"))
        assertTrue(stalls.single().contains("sourceGapMs=500"))
    }

    @Test fun missingOrResetSourceTimestampDoesNotBecomeNetworkLatency() {
        val logs = mutableListOf<String>()
        val timeline = FrameTimeline(logs::add) { _, _, _ -> }
        timeline.frame(0, 1_000_000_000)
        timeline.frame(0, 1_100_000_000)
        timeline.frame(8_000_000_000, 1_200_000_000)
        timeline.frame(1_000_000_000, 1_300_000_000)
        timeline.flush(1_300_000_000)
        assertTrue(logs.single().contains("sourceSamples=0 invalidSource=3"))
        assertTrue(logs.single().contains("sourceGapP95Us=-1"))
    }

    @Test fun windowResetRetainsCrossWindowFrameContinuity() {
        val logs = mutableListOf<String>()
        val timeline = FrameTimeline(logs::add) { _, _, _ -> }
        timeline.frame(1_000_000_000, 2_000_000_000)
        timeline.frame(6_000_000_000, 7_000_000_000)
        timeline.frame(6_016_000_000, 7_016_000_000)
        timeline.flush(7_016_000_000)
        assertEquals(2, logs.size)
        assertTrue(logs.last().contains("sourceSamples=1 invalidSource=0"))
        assertTrue(logs.last().contains("sourceGapP95Us=16000 arrivalGapP95Us=16000"))
    }
}
