package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class EventWriteStatsTest {
    @Test fun separatesLockEncodeCryptoAndWriteThenResets() {
        var now = 0L
        val stats = EventWriteStats { now }
        assertNull(stats.record(1_000, 2_000, 3_000, 4_000))
        now = 5_000_000_000
        val line = stats.record(10_000, 20_000, 30_000, 40_000)!!
        assertTrue(line.contains("n=2 avgUs=55"))
        assertTrue(line.contains("lockMaxUs=10 encodeMaxUs=20 cryptoMaxUs=30 writeMaxUs=40"))
        now += 5_000_000_000
        assertTrue(stats.record(0, 0, 0, 0)!!.contains("n=1 avgUs=0"))
    }
}
