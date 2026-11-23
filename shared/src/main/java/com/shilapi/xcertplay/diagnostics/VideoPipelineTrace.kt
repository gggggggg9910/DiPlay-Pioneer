package com.shilapi.xcertplay.diagnostics

import android.os.Debug
import android.os.Process
import java.io.Closeable
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One background sample/second while a screen stream exists. Hot paths only offer slow-event metadata. */
internal object VideoPipelineTrace {
    private val lock = Any()
    private val threads = LinkedHashMap<Int, String>()
    private var recorder = StallBuffer()
    private var executor: ScheduledExecutorService? = null
    private val owners = LinkedHashMap<Any, (String) -> Unit>()

    fun registerThread(stage: String) = synchronized(lock) {
        val tid = Process.myTid()
        if (threads.size < 24 || threads.containsKey(tid)) threads[tid] = stage
    }

    fun open(report: (String) -> Unit): Closeable {
        val owner = Any()
        synchronized(lock) {
            owners[owner] = report
            if (executor == null) {
                val sampler = Sampler()
                executor = Executors.newSingleThreadScheduledExecutor { task ->
                    Thread(task, "video-runtime-probe").apply { isDaemon = true }
                }.also { it.scheduleWithFixedDelay({ sampler.sample() }, 0, 1, TimeUnit.SECONDS) }
            }
        }
        return Closeable {
            synchronized(lock) {
                owners.remove(owner)
                if (owners.isEmpty()) {
                    executor?.shutdownNow(); executor = null
                    recorder = StallBuffer(); threads.clear()
                }
            }
        }
    }

    fun slow(stage: String, durationNs: Long, detail: String = "") {
        if (durationNs < 100_000_000L) return
        synchronized(lock) {
            if (owners.isNotEmpty()) recorder.offer(stage, durationNs, System.nanoTime(), detail)
        }
    }

    private class Sampler {
        private val history = ArrayDeque<String>()
        private var lastNs = 0L
        private var lastCpu = 0L
        private var lastGcCount = -1L
        private var lastGcMs = -1L
        private var lastBlockedMs = -1L
        private var samples = 0
        private var lastStallReportNs = 0L
        private var lastHistoryNs = 0L
        private val whitespace = Regex("\\s+")
        private val schedBefore = HashMap<Int, Pair<Long, Long>>()

        fun sample() {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            val report: (String) -> Unit
            val registered: Map<Int, String>
            val stalls: List<String>
            val inspectThreads: Boolean
            synchronized(lock) {
                report = owners.values.firstOrNull() ?: return
                val now = System.nanoTime()
                inspectThreads = now - lastStallReportNs >= 5_000_000_000L
                // Retain bounded events until the next allowed diagnostic burst.
                stalls = if (inspectThreads) recorder.drain() else emptyList()
                registered = if (inspectThreads && stalls.isNotEmpty()) threads.toMap() else emptyMap()
            }
            runCatching {
                val now = System.nanoTime()
                val cpu = Process.getElapsedCpuTime()
                val stats = runCatching { Debug.getRuntimeStats() }.getOrDefault(emptyMap())
                fun value(key: String) = stats[key]?.toLongOrNull() ?: -1L
                fun delta(current: Long, previous: Long) =
                    if (current >= 0 && previous >= 0 && current >= previous) current - previous else -1L
                val gcCount = value("art.gc.gc-count")
                val gcMs = value("art.gc.gc-time")
                val blockedMs = value("art.gc.blocking-gc-time")
                val runtime = Runtime.getRuntime()
                val heapKiB = (runtime.totalMemory() - runtime.freeMemory()) / 1024
                val frequency = runCatching {
                    File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").readText().trim().toLong()
                }.getOrDefault(-1L)
                val allocated = value("art.gc.bytes-allocated")
                val line = "RuntimePerf atMs=${now / 1_000_000} allocBytesTotal=$allocated intervalMs=${if (lastNs > 0) (now - lastNs) / 1_000_000 else -1} " +
                    "cpuMsD=${if (lastNs > 0) cpu - lastCpu else -1} gcCountD=${delta(gcCount, lastGcCount)} " +
                    "gcMsD=${delta(gcMs, lastGcMs)} blockingGcMsD=${delta(blockedMs, lastBlockedMs)} " +
                    "heapKiB=$heapKiB nativeKiB=${Debug.getNativeHeapAllocatedSize() / 1024} freqKHz=$frequency"
                val threadLines = ArrayList<String>()
                for ((tid, label) in registered) {
                    val sched = runCatching { File("/proc/self/task/$tid/schedstat").readText().trim().split(whitespace) }.getOrNull()
                    val runNs = sched?.getOrNull(0)?.toLongOrNull() ?: -1L
                    val waitNs = sched?.getOrNull(1)?.toLongOrNull() ?: -1L
                    val previous = schedBefore.put(tid, runNs to waitNs)
                    val runDelta = if (previous != null) delta(runNs, previous.first).let { if (it < 0) -1L else it / 1_000_000 } else -1L
                    val waitDelta = if (previous != null) delta(waitNs, previous.second).let { if (it < 0) -1L else it / 1_000_000 } else -1L
                    val state = runCatching {
                        File("/proc/self/task/$tid/stat").readText().substringAfterLast(") ").first().toString()
                    }.getOrDefault("gone")
                    threadLines.add("$label:$tid:$state/runMsD=$runDelta/waitMsD=$waitDelta")
                }
                // Emit pre-stall and nearest post-stall samples; sampling resolution is ~1 s.
                if (stalls.isNotEmpty()) {
                    lastStallReportNs = now
                    if (now - lastHistoryNs >= 5_000_000_000L) { history.forEach(report); lastHistoryNs = now }
                    stalls.forEach(report)
                    report(line)
                    threadLines.chunked(3).forEach { report("ThreadNear atMs=${now / 1_000_000} deltaMeaning=since-last-thread-sample ${it.joinToString(" ")}") }
                } else if (samples % 5 == 0) report(line)
                if (history.size == 2) history.removeFirst()
                history.addLast(line)
                samples++
                lastNs = now; lastCpu = cpu; lastGcCount = gcCount; lastGcMs = gcMs; lastBlockedMs = blockedMs
            }.onFailure { runCatching { report("RuntimePerf unavailable=${it.javaClass.simpleName}") } }
        }
    }
}
