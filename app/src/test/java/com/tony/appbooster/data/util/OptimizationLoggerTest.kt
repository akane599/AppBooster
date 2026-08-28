package com.tony.appbooster.data.util

import com.tony.appbooster.domain.model.common.LogEntryType
import com.tony.appbooster.domain.model.common.LogMessageKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OptimizationLogger].
 *
 * The logger is a singleton shared by every run, so retention limits and the
 * per-run reset are what keep memory bounded on devices with many packages.
 */
class OptimizationLoggerTest {

    private val logger = OptimizationLogger()

    @Test
    fun `given fewer lines than the cap when addLog then all lines are retained in order`() {
        logger.addLog("first")
        logger.addLog("second")

        assertEquals(listOf("first", "second"), logger.commandOutput.value)
    }

    @Test
    fun `given more lines than the cap when addLog then output is bounded and keeps the newest`() {
        repeat(1_200) { index -> logger.addLog("line $index") }

        val output = logger.commandOutput.value
        assertTrue("expected the raw log to be capped, was ${output.size}", output.size <= 500)
        assertEquals("line 1199", output.last())
    }

    @Test
    fun `given more entries than the cap when addLogEntry then entries are bounded and keep the newest`() {
        repeat(250) { index ->
            logger.addLogEntry(LogEntryType.INFO, message = "entry $index")
        }

        val entries = logger.logEntries.value
        assertTrue("expected entries to be capped, was ${entries.size}", entries.size <= 100)
        assertEquals("entry 249", entries.last().message)
    }

    @Test
    fun `given both streams populated when clearLogs then both are emptied`() {
        logger.addLog("raw line")
        logger.addLogEntry(LogEntryType.START, messageKey = LogMessageKey.STARTING_ANALYSIS)

        logger.clearLogs()

        assertEquals(emptyList<String>(), logger.commandOutput.value)
        assertTrue(logger.logEntries.value.isEmpty())
    }
}
