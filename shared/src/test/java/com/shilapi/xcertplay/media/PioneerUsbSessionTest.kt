package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class PioneerUsbSessionTest {
    private class Hardware : PioneerUsbHardware {
        var source = 201
        var valid = 2
        var storage = false
        var stopped: Boolean? = null
        var heartbeats = 0
        var refuse = false
        var prepared = true
        val selections = mutableListOf<Int>()
        override fun prepare() = prepared
        override fun current() = source
        override fun status() = valid
        override fun storagePresent() = storage
        override fun select(source: Int) { selections.add(source); if (!refuse) this.source = source }
        override fun status(value: Int) { valid = value }
        override fun awaitSource(source: Int) = this.source == source
        override fun watchdog() = object : PioneerUsbWatchdog {
            override fun heartbeat() { heartbeats++ }
            override fun stop(relinquish: Boolean) { stopped = relinquish }
        }
    }

    @Test fun playbackAndExitRestoreTheOriginalSourceAndInvalidUsbStatus() {
        val hw = Hardware(); val session = PioneerUsbSession(hw)
        assertTrue(session.start { true }); assertTrue(session.ready)
        assertEquals(202, hw.source); assertEquals(3, hw.valid)
        session.tick(); assertEquals(1, hw.heartbeats)
        session.close()
        assertEquals(201, hw.source); assertEquals(2, hw.valid); assertEquals(false, hw.stopped)
    }

    @Test fun realStorageArrivingIsRelinquishedWithoutInvalidatingOrTurningItOff() {
        val hw = Hardware(); val session = PioneerUsbSession(hw)
        session.start { true }; hw.storage = true
        assertFalse(session.tick()); assertFalse(session.ready)
        assertEquals(202, hw.source); assertEquals(3, hw.valid); assertEquals(true, hw.stopped)
    }

    @Test fun refusedActivationRollsBackAndStopsTheWatchdog() {
        val hw = Hardware().apply { refuse = true }; val session = PioneerUsbSession(hw)
        try { session.start { true }; fail() } catch (_: IllegalStateException) { }
        assertEquals(2, hw.valid); assertEquals(false, hw.stopped); assertFalse(session.ready)
    }

    @Test fun phoneInterruptionsYieldAndRestoreUsbOnlyAfterThePhoneEnds() {
        val hw = Hardware(); val session = PioneerUsbSession(hw)
        session.start { true }; hw.source = 302
        assertTrue(session.tick()); assertFalse(session.ready); assertEquals(302, hw.source)
        hw.source = 0
        assertTrue(session.tick()); assertTrue(session.ready); assertEquals(202, hw.source)
    }

    @Test fun ownAssistantCanReturnFromTheLegacyMediaSource() {
        val hw = Hardware(); val session = PioneerUsbSession(hw)
        session.start { true }; hw.source = 300
        assertTrue(session.tick(suspended = true)); assertFalse(session.ready)
        assertTrue(session.tick()); assertTrue(session.ready); assertEquals(202, hw.source)
    }

    @Test fun manualRadioSwitchIsNeverStolenBackOnTheNextTick() {
        val hw = Hardware(); val session = PioneerUsbSession(hw)
        session.start { true }; hw.source = 201
        assertFalse(session.tick()); assertEquals(201, hw.source); assertEquals(2, hw.valid)
    }

    @Test fun closeDuringCallDoesNotSelectAnotherSource() {
        val hw = Hardware(); val session = PioneerUsbSession(hw)
        session.start { true }; hw.source = 208; hw.selections.clear(); session.close()
        assertTrue(hw.selections.isEmpty()); assertEquals(208, hw.source)
    }

    @Test fun physicalUsbAndPhoneBaselinesAreNotClaimed() {
        val hw = Hardware().apply { source = 206 }; val session = PioneerUsbSession(hw)
        assertFalse(session.start { true }); assertTrue(hw.selections.isEmpty())
        hw.source = 201; hw.storage = true
        try { session.start { true }; fail() } catch (_: IllegalStateException) { }
        assertEquals(2, hw.valid); assertNull(hw.stopped)
    }

    @Test fun previousLiveWatchdogDefersAcquisitionWithoutChangingSourceState() {
        val hw = Hardware().apply { prepared = false }; val session = PioneerUsbSession(hw)
        assertFalse(session.start { true }); assertEquals(2, hw.valid); assertTrue(hw.selections.isEmpty())
        hw.prepared = true
        assertTrue(session.start { true }); assertEquals(202, hw.source)
    }
}
