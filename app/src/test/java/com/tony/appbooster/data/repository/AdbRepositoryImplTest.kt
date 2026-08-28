package com.tony.appbooster.data.repository

import com.tony.appbooster.data.util.CompilationInfoResolver
import com.tony.appbooster.data.util.OptimizationLogger
import com.tony.appbooster.data.util.PackageListQueryService
import com.tony.appbooster.domain.client.AdbShellDataSource
import com.tony.appbooster.domain.model.common.AppCompilationInfo
import com.tony.appbooster.domain.model.common.LogEntryType
import com.tony.appbooster.domain.model.common.LogMessageKey
import com.tony.appbooster.domain.model.common.OptimizationResult
import com.tony.appbooster.domain.model.common.Resource
import com.tony.appbooster.domain.model.settings.AppOptimizationType
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
 * Behavioural tests for [AdbRepositoryImpl].
 *
 * Focus is on run orchestration — how a user-requested stop is reported and how
 * log state survives the pre-flight analysis — rather than on shell parsing,
 * which is covered by the parser tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdbRepositoryImplTest {

    private lateinit var shellDataSource: AdbShellDataSource
    private lateinit var packageQuery: PackageListQueryService
    private lateinit var compilationResolver: CompilationInfoResolver
    private lateinit var logger: OptimizationLogger
    private lateinit var repository: AdbRepositoryImpl

    private val packages = listOf("com.example.one", "com.example.two", "com.example.three")

    @Before
    fun setUp() {
        shellDataSource = mockk(relaxed = true)
        packageQuery = mockk()
        compilationResolver = mockk(relaxed = true)
        logger = OptimizationLogger()

        coEvery { packageQuery.queryInstalledPackages() } returns packages
        coEvery { shellDataSource.executeCommand(any()) } returns Result.success("Success")

        repository = AdbRepositoryImpl(
            shellDataSource = shellDataSource,
            logger = logger,
            packageQuery = packageQuery,
            compilationResolver = compilationResolver
        )
    }

    private fun needsOptimization(packageName: String) = AppCompilationInfo(
        packageName = packageName,
        compilerFilter = "verify",
        lastCompilationTimeMs = null,
        lastUpdateTimeMs = null,
        oatFileExists = false,
        skipReason = null,
        needsOptimization = true
    )

    private fun stubResolverNeedingOptimization() {
        coEvery { compilationResolver.queryPackageCompilationInfo(any(), any()) } coAnswers {
            needsOptimization(firstArg())
        }
    }

    /** Stubs the resolver so that the very first package queried cancels the analysis. */
    private fun stubResolverCancellingOnFirstPackage() {
        coEvery { compilationResolver.queryPackageCompilationInfo(any(), any()) } coAnswers {
            repository.cancelAnalysis()
            needsOptimization(firstArg())
        }
    }

    // ── Analysis cancellation ────────────────────────────────────────────────

    @Test
    fun `given analysis cancelled mid-scan when analyzing then result is not an error`() = runTest {
        stubResolverCancellingOnFirstPackage()

        val result = repository.analyzeOptimizationStatus(AppOptimizationType.SPEED_PROFILE)

        assertTrue("stopping a scan is a user action, not a failure", result is Resource.Success)
    }

    @Test
    fun `given analysis cancelled mid-scan when analyzing then no failure entry is logged`() = runTest {
        stubResolverCancellingOnFirstPackage()

        repository.analyzeOptimizationStatus(AppOptimizationType.SPEED_PROFILE)

        val keys = logger.logEntries.value.map { it.messageKey }
        assertFalse(
            "cancelling must not surface as a red 'analysis failed' entry",
            keys.contains(LogMessageKey.ANALYSIS_FAILED)
        )
        assertTrue(keys.contains(LogMessageKey.ANALYSIS_CANCELLED))
    }

    @Test
    fun `given analysis cancelled mid-scan when analyzing then scanning state is cleared`() = runTest {
        stubResolverCancellingOnFirstPackage()

        repository.analyzeOptimizationStatus(AppOptimizationType.SPEED_PROFILE)

        val analysis = repository.optimizationAnalysis.value
        assertFalse(analysis.isScanning)
        assertEquals("", analysis.currentPackage)
    }

    @Test
    fun `given a real analysis failure when analyzing then an error is returned`() = runTest {
        coEvery { compilationResolver.queryPackageCompilationInfo(any(), any()) } throws
            IllegalStateException("dumpsys unavailable")

        val result = repository.analyzeOptimizationStatus(AppOptimizationType.SPEED_PROFILE)

        assertTrue(result is Resource.Error)
        assertTrue(logger.logEntries.value.any { it.messageKey == LogMessageKey.ANALYSIS_FAILED })
    }

    // ── Optimization run ─────────────────────────────────────────────────────

    @Test
    fun `given a fresh optimization run then the start entry survives the pre-flight analysis`() = runTest {
        stubResolverNeedingOptimization()

        repository.executeOptimizationCommand(AppOptimizationType.SPEED_PROFILE, forceOptimize = false)

        assertTrue(
            "the pre-flight scan must not wipe the run's own log",
            logger.logEntries.value.any { it.messageKey == LogMessageKey.STARTING_OPTIMIZATION }
        )
    }

    @Test
    fun `given an optimization run when it completes then progress reports completion`() = runTest {
        stubResolverNeedingOptimization()

        val result = repository.executeOptimizationCommand(
            AppOptimizationType.SPEED_PROFILE,
            forceOptimize = false
        )

        assertTrue(result is Resource.Success)
        val progress = repository.optimizationProgress.value
        assertFalse(progress.isRunning)
        assertEquals(OptimizationResult.Completed, progress.result)
        assertEquals(packages.size, progress.processedCount)
    }

    @Test
    fun `given a stop during the pre-flight analysis when optimizing then it is not reported as a failure`() = runTest {
        stubResolverCancellingOnFirstPackage()

        val result = repository.executeOptimizationCommand(
            AppOptimizationType.SPEED_PROFILE,
            forceOptimize = false
        )

        assertTrue("a stop before compilation began must not read as a failure", result is Resource.Success)
        assertFalse(
            logger.logEntries.value.any {
                it.type == LogEntryType.ERROR && it.messageKey == LogMessageKey.OPTIMIZATION_FAILED
            }
        )
        assertFalse(repository.optimizationProgress.value.isRunning)
    }

    @Test
    fun `given a stop during the pre-flight analysis when optimizing then no package is compiled`() = runTest {
        stubResolverCancellingOnFirstPackage()

        repository.executeOptimizationCommand(AppOptimizationType.SPEED_PROFILE, forceOptimize = false)

        assertFalse(
            "compiling an arbitrary subset after a stop would defeat the stop",
            logger.logEntries.value.any { it.messageKey == LogMessageKey.OPTIMIZING_APP }
        )
    }

    @Test
    fun `given force mode when optimizing then every package is compiled without analysis`() = runTest {
        val result = repository.executeOptimizationCommand(
            AppOptimizationType.FULL_OPTIMIZATION,
            forceOptimize = true
        )

        assertTrue(result is Resource.Success)
        assertEquals(packages.size, repository.optimizationProgress.value.processedCount)
        assertEquals(0, repository.optimizationProgress.value.skippedCount)
    }
}
