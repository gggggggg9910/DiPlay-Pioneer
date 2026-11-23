package com.shilapi.xcertplay.media

import android.os.Process
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One shared low-priority timer. Snapshots read cached scalars, never AudioTrack or renderer locks. */
internal object AudioWatchdog {
    private val lock = Any()
    private data class Entry(val snapshot: () -> String, val report: (String) -> Unit)
    private val entries = LinkedHashMap<Any, Entry>()
    private var executor: ScheduledExecutorService? = null

    fun observe(snapshot: () -> String, report: (String) -> Unit): Closeable {
        val owner = Any()
        synchronized(lock) {
            entries[owner] = Entry(snapshot, report)
            if (executor == null) executor = Executors.newSingleThreadScheduledExecutor { work ->
                Thread(work, "audio-watchdog").apply { isDaemon = true }
            }.also { it.scheduleWithFixedDelay(::sample, 5, 5, TimeUnit.SECONDS) }
        }
        return Closeable {
            synchronized(lock) {
                entries.remove(owner)
                if (entries.isEmpty()) { executor?.shutdownNow(); executor = null }
            }
        }
    }

    private fun sample() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        val current = synchronized(lock) { entries.values.toList() }
        current.forEach { runCatching { it.report(it.snapshot()) } }
    }
}
