package com.shilapi.xcertplay.media

/** Primitive-only hot path. Sample every 16 outputs plus slow frames/gaps, retain at most 8. */
internal class CodecFrameTrace {
    private val pts = LongArray(8)
    private val submitted = LongArray(8)
    private val output = LongArray(8)
    private var cursor = 0
    private var count = 0
    private var seen = 0L
    private var lastOutputNs = 0L

    @Synchronized fun record(ptsUs: Long, submittedNs: Long, outputNs: Long) {
        if (submittedNs <= 0 || outputNs < submittedNs) return
        val gap = if (lastOutputNs > 0) outputNs - lastOutputNs else 0
        lastOutputNs = outputNs
        if (seen++ % 16 != 0L && outputNs - submittedNs < 100_000_000L && gap < 100_000_000L) return
        pts[cursor] = ptsUs
        submitted[cursor] = submittedNs / 1000
        output[cursor] = outputNs / 1000
        cursor = (cursor + 1) % pts.size
        count = (count + 1).coerceAtMost(pts.size)
    }

    @Synchronized fun drain(): String? {
        if (count == 0) return null
        val line = buildString {
            append("CodecTimeline units=us fields=pts/submit/output samples=")
            repeat(count) { n ->
                val i = (cursor - count + pts.size + n) % pts.size
                if (n > 0) append(';')
                append(pts[i]).append('/').append(submitted[i]).append('/').append(output[i])
            }
        }
        count = 0
        return line
    }

    @Synchronized fun reset() { cursor = 0; count = 0; seen = 0; lastOutputNs = 0 }
}
