package com.shilapi.xcertplay.diagnostics

/** Compare frame intervals in their own clock domains; never subtract absolute phone/host clocks. */
internal class FrameTimeline(private val report: (String) -> Unit,
    private val slow: (String, Long, String) -> Unit = VideoPipelineTrace::slow) {
    private val source = LongArray(512)
    private val arrival = LongArray(512)
    private val excess = LongArray(512)
    private var sourceCount = 0
    private var arrivalCount = 0
    private var invalidSource = 0
    private var previousSource = 0L
    private var previousArrival = 0L
    private var window = 0L
    private var frames = 0

    fun frame(sourceNs: Long, arrivalNs: Long) {
        val sourceGap = sourceNs - previousSource
        val hostGap = arrivalNs - previousArrival
        val validSource = previousSource > 0L && sourceNs > 0L && sourceGap in 1..10_000_000_000L
        if (previousArrival != 0L && hostGap >= 0) {
            if (arrivalCount < arrival.size) arrival[arrivalCount++] = hostGap
            if (validSource) {
                if (sourceCount < source.size) {
                    source[sourceCount] = sourceGap
                    excess[sourceCount++] = hostGap - sourceGap
                }
            } else invalidSource++
            if (hostGap >= 100_000_000L) slow("frame-arrival", hostGap,
                "sourceGapMs=${if (validSource) sourceGap / 1_000_000 else -1} sourceValid=$validSource")
        }
        previousSource = sourceNs
        previousArrival = arrivalNs
        if (window == 0L) window = arrivalNs
        frames++
        if (arrivalNs - window >= 5_000_000_000L) flush(arrivalNs)
    }

    fun flush(nowNs: Long) {
        if (frames == 0) return
        fun percentile(values: LongArray, count: Int): Long = if (count == 0) -1 else
            values.copyOf(count).also { it.sort() }[(count - 1) * 95 / 100] / 1000
        report("FrameTimeline frames=$frames windowMs=${(nowNs - window).coerceAtLeast(0) / 1_000_000} " +
            "sourceSamples=$sourceCount invalidSource=$invalidSource " +
            "sourceGapP95Us=${percentile(source, sourceCount)} arrivalGapP95Us=${percentile(arrival, arrivalCount)} " +
            "intervalExcessP95Us=${percentile(excess, sourceCount)}")
        sourceCount = 0; arrivalCount = 0; invalidSource = 0; frames = 0; window = nowNs
    }
}
