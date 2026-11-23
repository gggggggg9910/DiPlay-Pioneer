package com.shilapi.xcertplay.media

/** Approximate touch-send to next received frame, not a causally matched display latency. */
internal object TouchLatencyProbe {
    private var pendingTouchNs = 0L
    private var maxSendNs = 0L
    private const val EXPIRE_NS = 2_000_000_000L

    @Synchronized fun onTouchSent(sentAtNs: Long, sendDurationNs: Long) {
        if (pendingTouchNs == 0L || sentAtNs - pendingTouchNs > EXPIRE_NS) pendingTouchNs = sentAtNs
        maxSendNs = maxOf(maxSendNs, sendDurationNs)
    }

    @Synchronized fun onFrame(nowNs: Long): Long {
        val touch = pendingTouchNs
        pendingTouchNs = 0L
        val elapsed = nowNs - touch
        return if (touch != 0L && elapsed in 0..EXPIRE_NS) elapsed else -1L
    }

    @Synchronized fun takeMaxSendNs(): Long = maxSendNs.also { maxSendNs = 0L }

    @Synchronized fun reset() { pendingTouchNs = 0L; maxSendNs = 0L }
}
