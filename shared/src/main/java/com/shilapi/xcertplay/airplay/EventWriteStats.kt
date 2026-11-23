package com.shilapi.xcertplay.airplay

/** Fixed-size counters on the event writer; never retains protocol contents. */
internal class EventWriteStats(private val nowNs: () -> Long = System::nanoTime) {
    private var start = nowNs()
    private var count = 0
    private val maxima = LongArray(4)
    private var totalNs = 0L

    @Synchronized fun record(lockNs: Long, encodeNs: Long, cryptoNs: Long, writeNs: Long): String? {
        count++
        maxima[0] = maxOf(maxima[0], lockNs)
        maxima[1] = maxOf(maxima[1], encodeNs)
        maxima[2] = maxOf(maxima[2], cryptoNs)
        maxima[3] = maxOf(maxima[3], writeNs)
        totalNs += lockNs + encodeNs + cryptoNs + writeNs
        val now = nowNs()
        if (now - start < 5_000_000_000L) return null
        val result = "EventPerf n=$count avgUs=${totalNs / count / 1000} " +
            "lockMaxUs=${maxima[0] / 1000} encodeMaxUs=${maxima[1] / 1000} " +
            "cryptoMaxUs=${maxima[2] / 1000} writeMaxUs=${maxima[3] / 1000}"
        count = 0
        totalNs = 0
        maxima.fill(0)
        start = now
        return result
    }
}
