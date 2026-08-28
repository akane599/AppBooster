package com.tony.appbooster.data.util

import com.tony.appbooster.domain.model.common.LogEntryType
import com.tony.appbooster.domain.model.common.LogMessageKey
import com.tony.appbooster.domain.model.common.OptimizationLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralised logger for optimization and analysis workflows.
 *
 * Owns the two observable log streams — raw text lines and structured
 * [OptimizationLogEntry] items — so that repository classes can
 * delegate all logging concerns without growing in size.
 *
 * @constructor Creates an empty logger ready to receive entries.
 */
@Singleton
class OptimizationLogger @Inject constructor() {

    companion object {
        /** Maximum number of structured log entries retained in memory. */
        private const val MAX_LOG_ENTRIES = 100

        /** Maximum number of raw output lines retained in memory. */
        private const val MAX_LOG_LINES = 500
    }

    private val _commandOutput = MutableStateFlow<List<String>>(emptyList())

    /** Chronological list of raw shell output lines for terminal-like rendering. */
    val commandOutput: StateFlow<List<String>> = _commandOutput.asStateFlow()

    private val _logEntries = MutableStateFlow<List<OptimizationLogEntry>>(emptyList())

    /** Structured log entries for rich UI rendering. */
    val logEntries: StateFlow<List<OptimizationLogEntry>> = _logEntries.asStateFlow()

    /**
     * Appends a raw text line to the command output history.
     *
     * Lines are capped at [MAX_LOG_LINES] for the same reason entries are:
     * a single run over a few hundred packages emits thousands of lines, and
     * this logger is a [Singleton] that outlives every run.
     *
     * @param line Single textual log entry to append in execution order.
     */
    fun addLog(line: String) {
        _commandOutput.value = (_commandOutput.value + line).takeLastCapped(MAX_LOG_LINES)
    }

    /**
     * Appends a structured log entry for beautiful UI rendering.
     *
     * Entries are capped at [MAX_LOG_ENTRIES] to prevent memory bloat.
     *
     * @param type The type of log entry for visual differentiation.
     * @param message Human-readable message.
     * @param packageName Optional package name this entry relates to.
     * @param detail Optional additional detail text.
     */
    fun addLogEntry(
        type: LogEntryType,
        message: String = "",
        messageKey: LogMessageKey? = null,
        packageName: String? = null,
        detail: String? = null
    ) {
        val entry = OptimizationLogEntry(
            id = System.nanoTime(),
            timestamp = System.currentTimeMillis(),
            type = type,
            packageName = packageName,
            messageKey = messageKey,
            message = message,
            detail = detail
        )
        _logEntries.value = (_logEntries.value + entry).takeLastCapped(MAX_LOG_ENTRIES)
    }

    /**
     * Clears both log streams. Called when starting a new run so that the feed
     * shows only the current run and memory does not accumulate across runs.
     */
    fun clearLogs() {
        _logEntries.value = emptyList()
        _commandOutput.value = emptyList()
    }

    /** Returns the last [max] elements, or the receiver itself when already within budget. */
    private fun <T> List<T>.takeLastCapped(max: Int): List<T> =
        if (size > max) takeLast(max) else this
}

