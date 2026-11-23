package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class TouchLatencyProbeTest {
    @Test fun staleTouchCannotBecomeAFortySecondResponse() {
        TouchLatencyProbe.reset()
        TouchLatencyProbe.onTouchSent(1_000_000_000, 5_000_000)
        assertEquals(-1L, TouchLatencyProbe.onFrame(44_000_000_000))
        TouchLatencyProbe.onTouchSent(45_000_000_000, 2_000_000)
        assertEquals(100_000_000L, TouchLatencyProbe.onFrame(45_100_000_000))
        assertEquals(-1L, TouchLatencyProbe.onFrame(45_200_000_000))
        assertEquals(5_000_000L, TouchLatencyProbe.takeMaxSendNs())
        assertEquals(0L, TouchLatencyProbe.takeMaxSendNs())
        TouchLatencyProbe.reset()
    }

    @Test fun newGestureReplacesAnExpiredUnconsumedTouch() {
        TouchLatencyProbe.reset()
        TouchLatencyProbe.onTouchSent(1_000_000_000, 1)
        TouchLatencyProbe.onTouchSent(10_000_000_000, 1)
        assertEquals(20_000_000L, TouchLatencyProbe.onFrame(10_020_000_000))
        TouchLatencyProbe.reset()
    }
}
