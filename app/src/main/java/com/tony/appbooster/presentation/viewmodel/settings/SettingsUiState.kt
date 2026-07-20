package com.tony.appbooster.presentation.viewmodel.settings

import com.tony.appbooster.domain.model.settings.AppOptimizationType
import com.tony.appbooster.domain.model.shizuku.ShizukuState

/**
 * UI-specific representation of settings data consumed by the Settings screen,
 * including optimization mode, Shizuku status, and static app metadata.
 *
 * @param appOptimizationType Current optimization mode selected by the user.
 * @param appVersionName Human-readable version name displayed in the App info section.
 * @param appVersionCode Gradle version code displayed in the About section.
 * @param shizukuState Current state of the Shizuku service and permissions.
 * @return Immutable UI state snapshot for settings presentation logic.
 */
data class SettingsUiState(
    val appOptimizationType: AppOptimizationType = AppOptimizationType.SPEED_PROFILE,
    val appVersionName: String = "",
    val appVersionCode: String = "",
    val shizukuState: ShizukuState = ShizukuState.NotRunning
)
