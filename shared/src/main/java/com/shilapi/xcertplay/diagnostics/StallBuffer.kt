package com.shilapi.xcertplay.diagnostics

import java.util.ArrayDeque

/** Only bounded numeric metadata; no frame contents, headers, addresses or credentials. */
internal class StallBuffer(private val capacity: Int = 16) {
    private val events = ArrayDeque<String>()
    private val last = LinkedHashMap<String, Long>()

    @Synchronized fun offer(stage: String, durationNs: Long, nowNs: Long, detail: String = ""): Boolean {
        if (durationNs < 100_000_000L) return false
        val previous = last[stage]
        if (previous != null && nowNs - previous in 0 until 3_000_000_000L) return false
        if (!last.containsKey(stage) && last.size >= 32) last.remove(last.keys.first())
        last[stage] = nowNs
        if (events.size == capacity) events.removeFirst()
        events.addLast("PipelineStall stage=$stage atMs=${nowNs / 1_000_000} wallMs=${durationNs / 1_000_000} $detail")
        return true
    }

    @Synchronized fun drain(): List<String> {
        if (events.isEmpty()) return emptyList()
        return events.toList().also { events.clear() }
    }
}
