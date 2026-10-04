package com.liveaireply.app.util

import com.liveaireply.app.engine.EngineLogEntry
import com.liveaireply.app.engine.LogSeverity
import com.liveaireply.app.security.LogRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Date
import java.util.Locale

/**
 * Ring buffer of diagnostics shown on the Logs screen.
 *
 * Every line passes through [LogRedactor] before it is stored, and message content is
 * only included when debug logging is switched on. The buffer is bounded and lives in
 * memory only - nothing is written to disk or uploaded.
 */
class EventLog(private val capacity: Int = 400) {

    private val entries = ArrayDeque<EngineLogEntry>()
    private val _flow = MutableStateFlow<List<EngineLogEntry>>(emptyList())
    val flow: StateFlow<List<EngineLogEntry>> = _flow

    @Volatile
    var debugEnabled: Boolean = false

    fun log(message: String, tag: String = "app", severity: LogSeverity = LogSeverity.INFO) {
        record(EngineLogEntry(System.currentTimeMillis(), severity, message, tag))
    }

    fun record(entry: EngineLogEntry) {
        val redacted = entry.copy(
            message = if (entry.severity == LogSeverity.DEBUG && !debugEnabled) {
                return
            } else {
                LogRedactor.redact(entry.message)
            }
        )
        synchronized(entries) {
            entries.addLast(redacted)
            while (entries.size > capacity) entries.removeFirst()
            _flow.value = entries.toList()
        }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
            _flow.value = emptyList<EngineLogEntry>()
        }
    }

    fun snapshot(): List<EngineLogEntry> = synchronized(entries) { entries.toList() }

    companion object {
        private val timeFormat = ThreadLocal.withInitial {
            java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        }

        fun formatTime(ms: Long): String =
            timeFormat.get()?.format(Date(ms)) ?: ms.toString()

        fun formatLine(entry: EngineLogEntry): String =
            "${formatTime(entry.atMs)} ${entry.message}"

        fun formatForSharing(entries: List<EngineLogEntry>): String =
            entries.joinToString("\n") { formatLine(it) }
                .ifBlank { "No log entries." }

        fun locale(): Locale = Locale.getDefault()
    }
}
