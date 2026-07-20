package com.tony.appbooster.presentation.viewmodel.settings

import androidx.lifecycle.viewModelScope
import com.tony.appbooster.presentation.navigation.interfaces.NavigationManager
import com.tony.appbooster.domain.model.common.Resource
import com.tony.appbooster.domain.model.settings.AppOptimizationType
import com.tony.appbooster.domain.usecase.appinfo.GetAppInfoUseCase
import com.tony.appbooster.domain.usecase.settings.ObserveAppOptimizationTypeUseCase
import com.tony.appbooster.domain.usecase.settings.SetAppOptimizationTypeUseCase
import com.tony.appbooster.domain.usecase.shizuku.ObserveShizukuStateUseCase
import com.tony.appbooster.presentation.screen.settings.model.AppInfo
import com.tony.appbooster.presentation.viewmodel.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject


/**
 * ViewModel coordinating Settings behavior by combining optimization
 * preferences, Shizuku status and static app metadata into
 * a single UI state consumed by the Settings screen.
 *
 * @param navigationManager Interface to dispatch navigation commands.
 * @param observeAppOptimizationTypeUseCase Stream of optimization type changes.
 * @param setAppOptimizationTypeUseCase Command to persist optimization changes.
 * @param getAppInfoUseCase Use case that exposes app version name and code.
 * @param observeShizukuStateUseCase Stream of Shizuku state changes.
 * @return [SettingsUiState] exposed as a StateFlow through [BaseViewModel].
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    navigationManager: NavigationManager,
    private val observeAppOptimizationTypeUseCase: ObserveAppOptimizationTypeUseCase,
    private val setAppOptimizationTypeUseCase: SetAppOptimizationTypeUseCase,
    private val getAppInfoUseCase: GetAppInfoUseCase,
    private val observeShizukuStateUseCase: ObserveShizukuStateUseCase
) : BaseViewModel<SettingsUiState, SettingsUiEvent, SettingsUiEffect>(navigationManager) {

    override val LOG_TAG: String = "SettingsViewModel"

    init {
        observeOptimizationType()
        observeShizukuState()
        loadAppInfo()
    }

    /**
     * Subscribes to optimization type changes and updates the UI state while
     * surfacing DataStore or repository errors through the base error channel.
     *
     * @return Unit, result is propagated via [uiState].
     */
    private fun observeOptimizationType() {
        viewModelScope.launch(exceptionHandler) {
            observeAppOptimizationTypeUseCase()
                .collectLatest { resource ->
                    when (resource) {
                        is Resource.Success -> {
                            updateUiData(
                                currentUiData().copy(
                                    appOptimizationType = resource.data
                                )
                            )
                        }

                        is Resource.Error -> {
                            handleError(resource)
                        }
                    }
                }
        }
    }

    /**
     * Observes Shizuku state changes to keep the UI state in sync
     * with the current Shizuku service and permission status.
     *
     * @return Unit, resulting values are merged into [uiState].
     */
    private fun observeShizukuState() {
        viewModelScope.launch(exceptionHandler) {
            observeShizukuStateUseCase()
                .collectLatest { shizukuState ->
                    updateUiData(
                        currentUiData().copy(
                            shizukuState = shizukuState
                        )
                    )
                }
        }
    }

    /**
     * Loads app version name and code and merges them into the existing
     * Settings UI snapshot, allowing the screen to show dynamic build info.
     *
     * @return Unit, the resulting state or error is pushed to [uiState].
     */
    private fun loadAppInfo() {
        executeAsync {
            when (val result = getAppInfoUseCase()) {
                is Resource.Success -> {
                    val appInfo: AppInfo = result.data
                    updateUiData(
                        currentUiData().copy(
                            appVersionName = appInfo.versionName,
                            appVersionCode = appInfo.versionCode
                        )
                    )
                }

                is Resource.Error -> {
                    handleError(result)
                }
            }
        }
    }

    override fun handleEvent(event: SettingsUiEvent) {
        when (event) {
            is SettingsUiEvent.OnOptimizationTypeSelected -> persistOptimizationType(event.type)
        }
    }

    /**
     * Backwards-compatible entrypoint used by the current Settings screen.
     *
     * Once the UI is migrated, it should call [onEvent] directly instead.
     */
    fun onOptimizationTypeSelected(type: AppOptimizationType) {
        onEvent(SettingsUiEvent.OnOptimizationTypeSelected(type))
    }

    private fun persistOptimizationType(type: AppOptimizationType) {
        executeAsync {
            when (val result = setAppOptimizationTypeUseCase(type)) {
                is Resource.Success -> {
                    updateUiData(
                        currentUiData().copy(
                            appOptimizationType = type
                        )
                    )
                }

                is Resource.Error -> {
                    handleError(result)
                }
            }
        }
    }

    /**
     * Retrieves the current [SettingsUiState] from the base UI container,
     * falling back to a default instance when no data has been emitted.
     *
     * @return The latest non-null [SettingsUiState] snapshot.
     */
    private fun currentUiData(): SettingsUiState {
        return uiState.value.data ?: SettingsUiState()
    }
}
