package com.tony.appbooster.presentation.viewmodel.base

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDirections
import androidx.navigation.NavOptions
import com.tony.appbooster.presentation.navigation.interfaces.NavigationManager
import com.tony.appbooster.R
import com.tony.appbooster.domain.model.common.Resource
import com.tony.appbooster.domain.model.common.ResourceError
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Base ViewModel class providing common functionality for UI state management,
 * error handling, session observation, navigation and a UDF-style event pipeline.
 *
 * This base supports two streams:
 * - A persistent [uiState] stream for rendering.
 * - A one-shot [uiEffect] stream for transient actions (snackbars, navigation hints, etc.).
 *
 * @param UI_TYPE Type of the immutable UI model held in [uiState].
 * @param UI_EVENT Type of the UI intent/event dispatched by the UI layer.
 * @param UI_EFFECT Type of one-shot effects emitted towards the UI layer.
 * @property navigationManager Manager responsible for dispatching navigation commands.
 */
abstract class BaseViewModel<UI_TYPE, UI_EVENT, UI_EFFECT>(
    private val navigationManager: NavigationManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UIState<UI_TYPE>())
    val uiState: StateFlow<UIState<UI_TYPE>> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<UI_EFFECT>(extraBufferCapacity = 1)

    /**
     * Hot stream of transient effects.
     *
     * Effects are one-shot by design: the UI should collect and react once.
     */
    val uiEffect: SharedFlow<UI_EFFECT> = _uiEffect.asSharedFlow()

    open val LOG_TAG: String = this::class.java.simpleName
    open val logErrorMessage = "The ViewModel caught an error"



    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        viewModelScope.launch {
            handleError(throwable)
            Log.e(LOG_TAG, "$logErrorMessage in exceptionHandler", throwable)
        }
    }

    /**
     * Resolves a string resource into a localized String.
     *
     * Business purpose: keeps user-visible strings centralized in resources while allowing
     * the base ViewModel (which isn't a Composable) to build UI errors.
     */
    protected open fun resolveString(@StringRes id: Int, vararg args: Any): String {
        return try {
            AppBoosterStringProvider.get(id, *args)
        } catch (_: Throwable) {
            // Fallback for unit tests or edge-cases where provider isn't initialized.
            id.toString()
        }
    }

    /**
     * Handles various types of errors by updating the UI state with a [UIError].
     * Subclasses can override this method to provide specific error handling logic.
     *
     * @param errorObject The object representing the error, which can be a [Resource.Error],
     * a [Throwable], or any other object.
     * @param retryAction An optional lambda that can be executed to retry the failed operation.
     * @param processUiAfterError An optional lambda to process the UI state after an error.
     * @param processUIError An optional lambda to transform the [UIError] before updating UI state,
     * allowing for partial UI updates or specific data handling.
     */
    protected open fun handleError(
        errorObject: Any,
        retryAction: (() -> Unit)? = null,
        processUiAfterError: ((UIError) -> UI_TYPE?)? = null,
        processUIError: ((UIError) -> UIError)? = null
    ) {
        val errorUiState = when (errorObject) {
            is Resource.Error -> processErrorResource(errorObject.data, retryAction)
            is Throwable -> UIError(
                title = resolveString(R.string.error_unexpected_title),
                message = errorObject.message ?: resolveString(R.string.error_unknown_fallback_message),
                type = errorObject,
                retryAction = retryAction
            )
            else -> UIError(
                title = resolveString(R.string.error_unknown_title),
                message = resolveString(R.string.error_unknown_message),
                type = errorObject,
                retryAction = retryAction
            )
        }

        val processedError = processUIError?.invoke(errorUiState) ?: errorUiState

        _uiState.update { currentState ->
            val newData = processUiAfterError?.invoke(processedError)
            val shouldShowErrorDialog = processUiAfterError == null
            currentState.copy(
                status = UIStatus.ERROR,
                error = processedError,
                data = newData ?: currentState.data,
                showErrorDialog = shouldShowErrorDialog
            )
        }
    }

    /**
     * Processes a [ResourceError] into a [UIError] for display in the UI.
     *
     * @param resource The [ResourceError] to process.
     * @param retryAction An optional lambda for retrying the operation.
     * @return A [UIError] object representing the processed error.
     */
    protected open fun processErrorResource(
        resource: ResourceError?,
        retryAction: (() -> Unit)? = null
    ): UIError {
        return when (resource) {
            is ResourceError.LogicError -> UIError(
                title = resolveString(R.string.error_generic_title),
                message = resource.errorMessage ?: resolveString(R.string.error_generic_fallback_message),
                type = resource,
                retryAction = retryAction
            )

            is ResourceError.NetworkError -> UIError(
                title = resolveString(R.string.error_network_title),
                message = resolveString(R.string.error_network_message),
                type = resource,
                retryAction = retryAction
            )

            ResourceError.UnknownError -> UIError(
                title = resolveString(R.string.error_unknown_title),
                message = resolveString(R.string.error_unknown_message),
                type = resource,
                retryAction = retryAction
            )

            ResourceError.SSLError -> UIError(
                title = resolveString(R.string.error_security_title),
                message = resolveString(R.string.error_security_message),
                type = resource,
                retryAction = retryAction
            )

            null -> UIError(
                title = resolveString(R.string.error_unknown_title),
                message = resolveString(R.string.error_unknown_message),
                retryAction = retryAction
            )

            is ResourceError.DatabaseError -> UIError(
                title = resolveString(R.string.error_database_title),
                message = resource.message,
                type = resource,
                retryAction = retryAction
            )
        }
    }

    /**
     * Launches a coroutine to perform a data fetch operation and updates the UI state
     * based on the result. This function handles loading, success, and error states.
     *
     * @param RESOURCE The type of the data returned by the successful data fetch operation.
     * @param retryAction An optional lambda to retry the operation if it fails.
     * @param dataFetchBlock A suspend lambda that performs the data fetching operation
     * and returns a [Resource].
     * @param processSuccess A lambda that transforms the successful resource data into
     * the desired [UI_TYPE] for the UI state.
     * @param updateUiAfterError An optional lambda to process the UI state after an error,
     * allowing for partial UI updates or specific data handling.
     * @param invokeOnCompletion An optional lambda that is invoked when the launched coroutine
     * completes, indicating whether the operation was successful.
     * @param skipLoading If true, the UI status will not be set to [UIStatus.LOADING] before
     * the data fetch operation.
     */
    protected fun <RESOURCE> launchUiStateUpdate(
        retryAction: (() -> Unit)? = null,
        dataFetchBlock: suspend () -> Resource<RESOURCE>,
        processSuccess: (RESOURCE) -> UI_TYPE,
        updateUiAfterError: ((UIError) -> UI_TYPE?)? = null,
        invokeOnCompletion: ((success: Boolean) -> Unit)? = null,
        skipLoading: Boolean = false
    ) {
        // Guards against a double callback: the completion handler below must still fire
        // when the coroutine dies before reaching either branch, but not a second time.
        // Atomic because that handler runs on whichever thread completes the job.
        val completionNotified = AtomicBoolean(false)
        fun notifyCompletion(success: Boolean) {
            if (completionNotified.compareAndSet(false, true)) {
                invokeOnCompletion?.invoke(success)
            }
        }

        viewModelScope.launch(exceptionHandler) {
            if (!skipLoading) {
                setLoadingState()
            }
            when (val resource = dataFetchBlock()) {
                is Resource.Success -> {
                    val newData = processSuccess(resource.data)
                    _uiState.update {
                        it.copy(
                            status = UIStatus.SUCCESS,
                            data = newData,
                            error = null
                        )
                    }

                    notifyCompletion(true)
                }
                else -> {
                    handleError(
                        resource,
                        retryAction,
                        updateUiAfterError
                    )
                    // A domain error completes the coroutine normally, so the handler
                    // below never sees it. Callers still need to unwind their
                    // "in progress" flags, otherwise the UI stays stuck on loading.
                    notifyCompletion(false)
                }
            }
        }.invokeOnCompletion {
            if (it != null) {
                Log.e(LOG_TAG, "Coroutine completed with error", it)
            }
            notifyCompletion(it == null)
        }
    }

    /**
     * Called when the ViewModel is no longer used and will be destroyed.
     */
    override fun onCleared() {
        super.onCleared()
        Log.d(LOG_TAG, "onCleared called.")
    }


    /**
     * Controls the visibility of an error dialog in the UI.
     *
     * @param value A boolean indicating whether to show (true) or hide (false) the error dialog.
     * @return The current data held in the UI state, which might be useful for the caller.
     */
    fun showErrorPopup(value: Boolean): UI_TYPE? {
        _uiState.update { it.copy(showErrorDialog = value) }
        return uiState.value.data
    }

    /**
     * Updates the data held in the UI state. This can be used to manually update
     * the UI with new data without changing the [UIStatus].
     *
     * @param newData The new data of type [UI_TYPE] to set in the UI state.
     */
    fun updateUiData(newData: UI_TYPE?) {
        _uiState.update { it.copy(data = newData) }
    }

    /**
     * Executes a given suspend block asynchronously within the [viewModelScope]
     * and handles any exceptions using the [exceptionHandler].
     *
     * @param block The suspend lambda to execute.
     */
    inline fun executeAsync(crossinline block: suspend () -> Unit) {
        viewModelScope.launch(exceptionHandler) {
            block()
        }
    }

    // --- Navigation Methods ---
    /**
     * Sends a command to trigger navigation to a specific route.
     *
     * @param route The destination route as a string.
     * @param navOptions Optional [NavOptions] to customize the navigation behavior (e.g., pop up to, single top).
     */
    fun navigateToRoute(route: String, navOptions: NavOptions? = null) {
        navigationManager.navigate(route, navOptions)
    }

    /**
     * Sends a command to trigger navigation using [NavDirections].
     * Note: This requires a custom implementation in your UI layer to handle NavDirections.
     *
     * @param directions The [NavDirections] object defining the navigation action.
     */
    fun navigateToDirections(directions: NavDirections) {
        // The base NavigationManager doesn't support NavDirections directly.
        // You can add a new command or handle it by navigating to its actionId and arguments.
        // For now, we will navigate using the route from the actionId.
        navigationManager.navigate(directions.actionId.toString())
    }

    /**
     * Sends a command to trigger navigation up the back stack.
     */
    fun navigateUp() {
        navigationManager.navigateUp()
    }

    /**
     * Sends a command to pop the current destination or a specific
     * destination from the back stack.
     *
     * @param route The route to pop up to (inclusive) or null to pop the current destination.
     * @param inclusive Whether the specified route should also be popped from the back stack.
     */
    fun popBackStack(route: String? = null, inclusive: Boolean = false) {
        navigationManager.popBackStack(route, inclusive)
    }

    /**
     * Sends a command to navigate to a destination
     * and clear the back stack up to a specified route.
     *
     * @param route The destination route to navigate to.
     * @param popUpToRoute The route to pop up to. If null, the entire back stack is cleared.
     * @param inclusive Whether the `popUpToRoute` should also be popped from the back stack.
     */
    fun navigateAndClearBackstackTo(
        route: String,
        popUpToRoute: String? = null,
        inclusive: Boolean = true
    ) {
        navigationManager.navigateAndClearBackStack(route, popUpToRoute, inclusive)
    }

    /**
     * Sets the UI state to [UIStatus.LOADING], clearing any previous error.
     * This is a useful for indicating the start of an asynchronous operation.
     */
    protected fun setLoadingState() {
        _uiState.update { it.copy(status = UIStatus.LOADING, error = null, showErrorDialog = false) }
    }

    /**
     * Single entrypoint for all user intents emitted by the UI.
     *
     * This enforces Unidirectional Data Flow (UDF): UI emits [UI_EVENT] → ViewModel processes
     * it → state/effects update → UI renders/reacts.
     *
     * @param event User intent coming from the presentation layer.
     */
    fun onEvent(event: UI_EVENT) {
        handleEvent(event)
    }

    /**
     * Processes a UI intent.
     *
     * Implementations should be side-effect free where possible and coordinate work via
     * use cases/repositories, updating [uiState] and emitting [uiEffect] as needed.
     *
     * @param event User intent coming from the UI.
     */
    protected abstract fun handleEvent(event: UI_EVENT)

    /**
     * Emits a transient effect towards the UI.
     *
     * This uses a buffered [MutableSharedFlow] to avoid suspending the caller.
     * Callers should prefer emitting effects for snackbars/toasts/navigation prompts,
     * not for persistent rendering state.
     *
     * @param effect One-shot UI effect.
     */
    protected fun emitEffect(effect: UI_EFFECT) {
        _uiEffect.tryEmit(effect)
    }
}