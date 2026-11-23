package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchReportQueueTest {
    @Test fun swipeKeepsOnlyTheNewestMove() {
        val queue = TouchReportQueue()
        assertTrue(queue.offer(listOf(finger(0.1, down = true))))
        assertFalse(queue.offer(listOf(finger(0.4, down = true))))
        assertFalse(queue.offer(listOf(finger(0.9, down = true))))
        assertEquals(0.1, queue.poll()!!.single().x, 0.0) // initial down starts the drag
        assertEquals(0.9, queue.poll()!!.single().x, 0.0)
        assertNull(queue.poll())
    }

    @Test fun liftIsNotReplacedByTheNextPress() {
        val queue = TouchReportQueue()
        assertTrue(queue.offer(listOf(finger(0.2, down = true))))
        assertFalse(queue.offer(listOf(finger(0.2, down = false))))
        assertFalse(queue.offer(listOf(finger(0.8, down = true))))
        assertEquals(true, queue.poll()!!.single().down)
        assertEquals(false, queue.poll()!!.single().down)
        assertEquals(0.8, queue.poll()!!.single().x, 0.0)
    }

    @Test fun releaseAndFingerChangesSurviveMoveCoalescing() {
        val queue = TouchReportQueue()
        queue.offer(listOf(finger(0.1, true)))
        queue.offer(listOf(finger(0.2, true)))
        queue.offer(listOf(finger(0.3, true)))
        queue.offer(listOf(finger(0.3, false)))
        queue.offer(listOf(finger(0.8, true)))
        assertEquals(listOf(0.1, 0.3, 0.3, 0.8), List(4) { queue.poll()!!.single().x })
        assertNull(queue.poll())
        assertTrue(queue.offer(listOf(finger(0.9, false))))
        queue.clear()
        assertTrue(queue.offer(listOf(finger(0.4, true))))
        queue.offer(listOf(finger(0.5, true)))
        assertEquals(0.4, queue.poll()!!.single().x, 0.0)
        assertEquals(0.5, queue.poll()!!.single().x, 0.0)
    }

    @Test fun reportsQueueAgeAndCoalescingWithoutRetainingAnOldMoveTimestamp() {
        var now = 1L
        val queue = TouchReportQueue { now }
        queue.offer(listOf(finger(0.1, true)))
        now += 1_000_000
        queue.offer(listOf(finger(0.2, true)))
        now += 1_000_000
        queue.offer(listOf(finger(0.3, true)))
        queue.poll(); queue.poll()
        now += 5_000_000_000
        assertTrue(queue.summaryIfDue()!!.contains("n=3 merged=1 depthMax=2 waitMaxUs=2000"))
    }

    private fun finger(x: Double, down: Boolean) = AirPlayContact(id = 0, x = x, y = 0.5, down = down)
}
