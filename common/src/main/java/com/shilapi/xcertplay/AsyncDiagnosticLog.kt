package com.shilapi.xcertplay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Queued entries retain only their exact file target and a redacted, bounded metadata string. */
internal object AsyncDiagnosticLog {
    private data class Entry(val target: SessionLogFile, val message: String, val nowMillis: Long)
    // Used exclusively by the single writer; never published to callers.
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val writer = BoundedDiagnosticWriter<Entry> {
        val safe = DiagnosticRedactor.redact(it.message)
        if (safe != null) it.target.appendNow("${timestampFormat.format(Date(it.nowMillis))}  $safe")
    }

    fun append(target: SessionLogFile?, message: String, nowMillis: Long = System.currentTimeMillis()) {
        if (target == null) return
        // Queue retains bounded strings in memory only; redaction happens before any disk write.
        runCatching { writer.enqueue(Entry(target, message.take(2048), nowMillis)) }
    }

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)
}
