package com.tony.appbooster.data.util

import com.tony.appbooster.domain.client.AdbShellDataSource
import com.tony.appbooster.domain.model.common.ShellCommandResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [CompilationInfoResolver]'s multi-step fallback strategy.
 *
 * The property that matters most here is convergence: a package must not be
 * reported as needing optimization on every single run when compiling it cannot
 * change what the system reports about it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompilationInfoResolverTest {

    private lateinit var shellDataSource: AdbShellDataSource
    private lateinit var logger: OptimizationLogger
    private lateinit var resolver: CompilationInfoResolver

    /** A package listed in the dexopt dump with no compiler filter of its own. */
    private val dumpWithUndetailedPackage = """
        Dexopt state:
          [com.example.undetailed]
          [com.example.other]
            compiler-filter=speed
    """.trimIndent()

    @Before
    fun setUp() {
        shellDataSource = mockk()
        logger = OptimizationLogger()
        resolver = CompilationInfoResolver(shellDataSource, logger)
    }

    private fun stubShell(dexoptDump: String, perPackageDump: String = "") {
        coEvery { shellDataSource.executeCommandDetailed("dumpsys package dexopt") } returns
            Result.success(ShellCommandResult(exitCode = 0, stdout = dexoptDump, stderr = ""))
        coEvery { shellDataSource.executeCommandDetailed(match { it.startsWith("cmd package compile --check") }) } returns
            Result.success(ShellCommandResult(exitCode = 1, stdout = "", stderr = "Unknown option --check"))
        coEvery { shellDataSource.executeCommand(match { it.startsWith("dumpsys package ") }) } returns
            Result.success(perPackageDump)
        coEvery { shellDataSource.executeCommand(match { it.startsWith("ls ") }) } returns
            Result.success("ls: No such file or directory")
    }

    // ── Regression: undetailed packages must not be queued forever ───────────

    @Test
    fun `given a package listed without a compiler filter then it is not queued for optimization`() = runTest {
        stubShell(dumpWithUndetailedPackage)

        val info = resolver.queryPackageCompilationInfo("com.example.undetailed", "speed")

        // Compiling it cannot change what the dump reports, so queueing it would
        // re-report it on every scan and the count would never converge.
        assertFalse(
            "package with no reported filter must not be queued",
            info.needsOptimization
        )
    }

    @Test
    fun `given a package listed without a compiler filter then the next package's filter is not borrowed`() = runTest {
        stubShell(dumpWithUndetailedPackage)

        val info = resolver.queryPackageCompilationInfo("com.example.undetailed", "speed")

        assertEquals(
            "must not report the neighbouring package's filter as its own",
            DexoptStatusParser.FILTER_PRESENT_NO_DETAIL,
            info.compilerFilter
        )
    }

    @Test
    fun `given an undetailed package when the per-package dump reports a filter then that filter wins`() = runTest {
        // "Present but no details" is inconclusive, so the later steps must still run.
        stubShell(
            dexoptDump = dumpWithUndetailedPackage,
            perPackageDump = "  compilerFilter=speed-profile\n  lastUpdateTime=2024-01-01 10:00:00"
        )

        val info = resolver.queryPackageCompilationInfo("com.example.undetailed", "speed-profile")

        assertEquals("speed-profile", info.compilerFilter)
        assertFalse(info.needsOptimization)
    }

    // ── Normal resolution still works ────────────────────────────────────────

    @Test
    fun `given a package with a reported filter then it resolves from the dexopt dump`() = runTest {
        stubShell(dumpWithUndetailedPackage)

        val info = resolver.queryPackageCompilationInfo("com.example.other", "speed")

        assertEquals("speed", info.compilerFilter)
        assertFalse("speed already satisfies a speed target", info.needsOptimization)
    }

    @Test
    fun `given a package absent from every source then it needs optimization`() = runTest {
        stubShell("Dexopt state:\n  [com.example.other]\n    compiler-filter=speed")

        val info = resolver.queryPackageCompilationInfo("com.example.missing", "speed")

        assertTrue("an unknown package is the one case worth compiling", info.needsOptimization)
    }

    @Test
    fun `given a verify filter on speed-profile then it is skipped as having no profile`() = runTest {
        stubShell("Dexopt state:\n  [com.example.app]\n    compiler-filter=verify")

        val info = resolver.queryPackageCompilationInfo("com.example.app", "speed-profile")

        assertFalse(info.needsOptimization)
        assertTrue(info.skipReason is com.tony.appbooster.domain.model.common.AppCompilationInfo.SkipReason.NoProfile)
    }

    // ── Session cache ────────────────────────────────────────────────────────

    @Test
    fun `given a package compiled this session then it is skipped without any shell call`() = runTest {
        resolver.markOptimized("com.example.app")

        val info = resolver.queryPackageCompilationInfo("com.example.app", "speed")

        assertFalse(info.needsOptimization)
    }

    // ── Dexopt dump is fetched at most once per run ──────────────────────────

    @Test
    fun `given a failing dexopt dump then it is not refetched for every package`() = runTest {
        var dumpCalls = 0
        coEvery { shellDataSource.executeCommandDetailed("dumpsys package dexopt") } answers {
            dumpCalls++
            Result.success(ShellCommandResult(exitCode = 1, stdout = "", stderr = "denied"))
        }
        coEvery { shellDataSource.executeCommandDetailed(match { it.startsWith("cmd package compile --check") }) } returns
            Result.success(ShellCommandResult(exitCode = 1, stdout = "", stderr = "unsupported"))
        coEvery { shellDataSource.executeCommand(any()) } returns Result.success("")

        repeat(5) { index ->
            resolver.queryPackageCompilationInfo("com.example.app$index", "speed")
        }

        assertEquals("the expensive global dump must run once per run", 1, dumpCalls)
    }
}
