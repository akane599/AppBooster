package com.tony.appbooster.data.client

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.tony.appbooster.domain.model.shizuku.ShizukuState
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import rikka.shizuku.Shizuku

/**
 * Unit tests for the state-detection logic of [ShizukuShellClientImpl].
 *
 * The central regression these tests lock in: a live Shizuku binder must be treated as
 * authoritative proof that Shizuku is installed and running, even when a PackageManager
 * lookup is blocked by Android 11+ package-visibility filtering (the missing `<queries>`
 * bug). Before the fix, [ShizukuShellClientImpl] checked the package first and short-circuited
 * to [ShizukuState.NotInstalled], stranding users who had Shizuku installed and running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShizukuShellClientImplTest {

    private val ioDispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context
    private lateinit var packageManager: PackageManager

    @Before
    fun setUp() {
        mockkStatic(Shizuku::class)
        justRun { Shizuku.addBinderReceivedListenerSticky(any()) }
        justRun { Shizuku.addBinderDeadListener(any()) }
        justRun { Shizuku.addRequestPermissionResultListener(any()) }
        justRun { Shizuku.bindUserService(any(), any()) }
        justRun { Shizuku.unbindUserService(any(), any(), any()) }
        every { Shizuku.isPreV11() } returns false

        packageManager = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.packageManager } returns packageManager
    }

    @After
    fun tearDown() {
        unmockkStatic(Shizuku::class)
    }

    private fun createClient() = ShizukuShellClientImpl(context, ioDispatcher)

    @Test
    fun `binder alive with permission granted but package not visible then Ready not NotInstalled`() {
        // Simulate package-visibility filtering: the query fails even though Shizuku is installed.
        every { packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) } throws PackageManager.NameNotFoundException()
        every { Shizuku.pingBinder() } returns true
        every { Shizuku.checkSelfPermission() } returns PackageManager.PERMISSION_GRANTED

        assertEquals(ShizukuState.Ready, createClient().state.value)
    }

    @Test
    fun `binder alive with permission denied but package not visible then PermissionRequired`() {
        every { packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) } throws PackageManager.NameNotFoundException()
        every { Shizuku.pingBinder() } returns true
        every { Shizuku.checkSelfPermission() } returns PackageManager.PERMISSION_DENIED

        assertEquals(ShizukuState.PermissionRequired, createClient().state.value)
    }

    @Test
    fun `binder dead and package present then NotRunning`() {
        every { Shizuku.pingBinder() } returns false
        every { packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) } returns PackageInfo()

        assertEquals(ShizukuState.NotRunning, createClient().state.value)
    }

    @Test
    fun `binder dead and package absent then NotInstalled`() {
        every { Shizuku.pingBinder() } returns false
        every { packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) } throws PackageManager.NameNotFoundException()

        assertEquals(ShizukuState.NotInstalled, createClient().state.value)
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
