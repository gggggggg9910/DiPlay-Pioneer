package com.shilapi.xcertplay.airplay

/** Receive-thread timing only: no payloads, endpoint addresses, or route data. */
internal class StreamReceiveStats(
    private val label: String,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var processingStart = windowStart
    private var packets = 0
    private var bytes = 0L
    private var maxReadNs = 0L
    private var readsOver250Ms = 0
    private var seqGapAfterReadOver250Ms = 0
    private var seqMissingAfterReadOver250Ms = 0
    private var lastReceivedNs = 0L
    private var maxInterArrivalNs = 0L
    private var maxProcessNs = 0L
    private var lastTimestamp: Int? = null
    private var nextSequence: Int? = null
    private var forwardGapPackets = 0
    private var sequenceGapEvents = 0
    private var maxSequenceGap = 0
    private var lastSequenceGap = "none"
    private var lastSequenceGapAtMs = -1L
    private var lateOrDuplicate = 0
    private var decryptSamples = 0
    private var decryptBytes = 0L
    private var decryptSumNs = 0L
    private var maxDecryptNs = 0L
    private var sequenceTracked = false
    private var queueMaxNs = 0L
    private var queueDepthMax = 0
    private val arrivalGapsUs = LongArray(512)
    private var arrivalGapSamples = 0

    @Synchronized fun reading() { readStart = nowNs() }

    @Synchronized fun received(size: Int, sequence: Int? = null, timestamp: Int? = null) {
        processingStart = nowNs()
        val readNs = processingStart - readStart
        maxReadNs = maxOf(maxReadNs, readNs)
        if (readNs >= LONG_READ_NS) readsOver250Ms++
        if (lastReceivedNs != 0L) {
            if (arrivalGapSamples < arrivalGapsUs.size) {
                arrivalGapsUs[arrivalGapSamples++] = (processingStart - lastReceivedNs) / 1000
            }
            maxInterArrivalNs = maxOf(maxInterArrivalNs, processingStart - lastReceivedNs)
        }
        lastReceivedNs = processingStart
        packets++
        bytes += size
        if (sequence != null) {
            sequenceTracked = true
            val expected = nextSequence
            val delta = if (expected == null) 0 else (sequence - expected) and 0xffff
            if (delta < 0x8000) {
                forwardGapPackets += delta
                if (delta > 0) {
                    sequenceGapEvents++
                    if (readNs >= LONG_READ_NS) {
                        seqGapAfterReadOver250Ms++
                        seqMissingAfterReadOver250Ms += delta
                    }
                    maxSequenceGap = maxOf(maxSequenceGap, delta)
                    lastSequenceGap = "expected=$expected received=$sequence missing=$delta " +
                        "previousRtpTs=${lastTimestamp?.toUnsignedLong() ?: "unknown"} " +
                        "receivedRtpTs=${timestamp?.toUnsignedLong() ?: "unknown"}"
                    lastSequenceGapAtMs = (processingStart - windowStart) / 1_000_000L
                }
                nextSequence = (sequence + 1) and 0xffff
            } else lateOrDuplicate++
        }
        if (timestamp != null) lastTimestamp = timestamp
    }

    /** One payload opened in [durationNs]; reported only for streams that call it. */
    @Synchronized fun decrypted(durationNs: Long, size: Int) {
        decryptSamples++
        decryptBytes += size
        decryptSumNs += durationNs
        maxDecryptNs = maxOf(maxDecryptNs, durationNs)
    }

    @Synchronized fun queued(waitNs: Long, depth: Int) {
        queueMaxNs = maxOf(queueMaxNs, waitNs)
        queueDepthMax = maxOf(queueDepthMax, depth)
    }

    @Synchronized fun processed() {
        maxProcessNs = maxOf(maxProcessNs, nowNs() - processingStart)
        flush()
    }

    @Synchronized fun flush(ended: Boolean = false) {
        val now = nowNs()
        if (!ended && now - windowStart < 5_000_000_000L) return
        val gaps = arrivalGapsUs.copyOf(arrivalGapSamples).also { it.sort() }
        val gapP95Us = if (gaps.isEmpty()) 0L else gaps[(gaps.size - 1) * 95 / 100]
        runCatching { report("Receive: $label packets=$packets bytes=$bytes readMaxMs=${maxReadNs / 1_000_000} " +
            "processMaxUs=${maxProcessNs / 1000} seqForwardGaps=$forwardGapPackets " +
            "lateOrDuplicate=$lateOrDuplicate interArrivalMaxMs=${maxInterArrivalNs / 1_000_000} " +
            "seqGapEvents=$sequenceGapEvents seqGapMax=$maxSequenceGap " +
            "seqGapLast=[$lastSequenceGap] seqGapAtMs=$lastSequenceGapAtMs ended=$ended " +
            "windowMs=${(now - windowStart).coerceAtLeast(0) / 1_000_000} " +
            "readsOver250Ms=$readsOver250Ms seqGapAfterReadOver250Ms=$seqGapAfterReadOver250Ms " +
            "seqMissingAfterReadOver250Ms=$seqMissingAfterReadOver250Ms " +
            "seqTracked=$sequenceTracked gapP95Us=$gapP95Us " +
            "decryptQueueMaxUs=${queueMaxNs / 1000} decryptDepthMax=$queueDepthMax" + decryptSummary()) }
        windowStart = now
        arrivalGapSamples = 0; queueMaxNs = 0; queueDepthMax = 0
        decryptSamples = 0
        decryptBytes = 0
        decryptSumNs = 0
        maxDecryptNs = 0
        packets = 0
        bytes = 0
        maxReadNs = 0
        readsOver250Ms = 0
        seqGapAfterReadOver250Ms = 0
        seqMissingAfterReadOver250Ms = 0
        maxInterArrivalNs = 0
        maxProcessNs = 0
        forwardGapPackets = 0
        sequenceGapEvents = 0
        maxSequenceGap = 0
        lastSequenceGap = "none"
        lastSequenceGapAtMs = -1L
        lateOrDuplicate = 0
    }

    private fun Int.toUnsignedLong(): Long = toLong() and 0xffff_ffffL

    private fun decryptSummary(): String {
        if (decryptSamples == 0) return ""
        val mbPerSecond = if (decryptSumNs == 0L) 0L else decryptBytes * 1_000L / decryptSumNs
        return " decryptAvgUs=${decryptSumNs / decryptSamples / 1000} decryptMaxUs=${maxDecryptNs / 1000} " +
            "decryptMBps=$mbPerSecond"
    }

    private companion object { const val LONG_READ_NS = 250_000_000L }

}
