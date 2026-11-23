package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.util.ArrayDeque

/** Keep finger transitions; only successive moves may collapse to the newest sample. Caller owns the lock. */
internal class TouchReportQueue(private val nowNs: () -> Long = System::nanoTime) {
    private class Report(val contacts: List<AirPlayContact>, val transition: Boolean, val offeredNs: Long)
    private val pending = ArrayDeque<Report>()
    private var previous = emptyList<AirPlayContact>()
    private var draining = false
    private var windowNs = nowNs()
    private var offered = 0
    private var merged = 0
    private var peak = 0
    private var maxWaitNs = 0L

    fun offer(contacts: List<AirPlayContact>): Boolean {
        val transition = !sameFingers(previous, contacts)
        val last = pending.lastOrNull()
        if (!transition && last != null && !last.transition) {
            pending.removeLast()
            merged++
        }
        // Contacts are immutable values; detach the list from the UI caller.
        val saved = contacts.toList()
        pending.addLast(Report(saved, transition, nowNs()))
        previous = saved
        offered++
        peak = maxOf(peak, pending.size)
        if (draining) return false
        draining = true
        return true
    }

    fun poll(): List<AirPlayContact>? {
        if (pending.isEmpty()) { draining = false; return null }
        val report = pending.removeFirst()
        maxWaitNs = maxOf(maxWaitNs, nowNs() - report.offeredNs)
        return report.contacts
    }

    fun summaryIfDue(): String? {
        val now = nowNs()
        if (now - windowNs < 5_000_000_000L) return null
        val result = "TouchQueuePerf n=$offered merged=$merged depthMax=$peak waitMaxUs=${maxWaitNs / 1000}"
        offered = 0; merged = 0; peak = 0; maxWaitNs = 0; windowNs = now
        return result
    }

    fun clear() {
        pending.clear()
        previous = emptyList()
        draining = false
    }

    private fun sameFingers(before: List<AirPlayContact>, after: List<AirPlayContact>): Boolean {
        if (before.size != after.size) return false
        for (index in before.indices) {
            if (before[index].id != after[index].id || before[index].down != after[index].down) return false
        }
        return true
    }
}
