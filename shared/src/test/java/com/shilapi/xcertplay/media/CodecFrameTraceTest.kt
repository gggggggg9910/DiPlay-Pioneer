package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class CodecFrameTraceTest {
    @Test fun retainsBoundedSlowFramePairsInOrderAndDrains() {
        val trace = CodecFrameTrace()
        repeat(12) { trace.record(it.toLong(), 1_000_000_000L + it, 1_200_000_000L + it) }
        val line = checkNotNull(trace.drain())
        val records = line.substringAfter("samples=").split(';')
        assertEquals(8, records.size)
        assertTrue(records.first().startsWith("4/"))
        assertTrue(records.last().startsWith("11/"))
        assertNull(trace.drain())
    }

    @Test fun samplesNormalFramesButAlwaysCapturesLongOutputGaps() {
        val trace = CodecFrameTrace()
        repeat(4) { trace.record(it.toLong(), 1_000_000_000L + it * 17_000_000L, 1_050_000_000L + it * 17_000_000L) }
        trace.record(99, 2_000_000_000, 2_050_000_000)
        val records = checkNotNull(trace.drain()).substringAfter("samples=").split(';')
        assertEquals(2, records.size)
        assertTrue(records.last().startsWith("99/"))
        trace.reset()
        assertNull(trace.drain())
    }
}
