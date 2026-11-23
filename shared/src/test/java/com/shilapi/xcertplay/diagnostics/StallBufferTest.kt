package com.shilapi.xcertplay.diagnostics

import org.junit.Assert.*
import org.junit.Test

class StallBufferTest {
    @Test fun ignoresSmallIntervalsAndLimitsEachStageWithoutHidingOtherStages() {
        val buffer = StallBuffer()
        assertFalse(buffer.offer("read", 99_000_000, 1_000_000_000))
        assertTrue(buffer.offer("read", 100_000_000, 1_000_000_000))
        assertFalse(buffer.offer("read", 300_000_000, 2_000_000_000))
        assertTrue(buffer.offer("decrypt", 200_000_000, 2_000_000_000))
        assertEquals(2, buffer.drain().size)
        assertTrue(buffer.drain().isEmpty())
        assertFalse(buffer.offer("read", 300_000_000, 3_000_000_000))
        assertTrue(buffer.offer("read", 300_000_000, 4_000_000_000))
    }

    @Test fun boundedBufferKeepsNewestMetadataWhenSeveralStagesStall() {
        val buffer = StallBuffer(capacity = 2)
        for (index in 1..3) buffer.offer("stage$index", 200_000_000, index * 1_000_000_000L)
        val events = buffer.drain()
        assertEquals(2, events.size)
        assertTrue(events[0].contains("stage=stage2"))
        assertTrue(events[1].contains("stage=stage3"))
        assertTrue(events[1].contains("atMs=3000 wallMs=200"))
    }
}
