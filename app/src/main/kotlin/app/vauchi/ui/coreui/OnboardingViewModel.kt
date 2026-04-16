// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import uniffi.vauchi_platform.MobileOnboardingWorkflow

/**
 * ViewModel that bridges the core onboarding workflow to Compose UI.
 *
 * The core [MobileOnboardingWorkflow] manages onboarding state and emits
 * screens as JSON. This ViewModel deserializes them into [ScreenModel]
 * instances and exposes them as [StateFlow] for the Compose layer.
 *
 * User interactions are serialized back to JSON and forwarded to core.
 */
class OnboardingViewModel : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val workflow = MobileOnboardingWorkflow()

    private val _screen = MutableStateFlow<ScreenModel?>(null)
    val screen: StateFlow<ScreenModel?> = _screen.asStateFlow()

    private val _isComplete = MutableStateFlow(false)
    val isComplete: StateFlow<Boolean> = _isComplete.asStateFlow()

    private val _displayName = MutableStateFlow<String?>(null)
    val displayName: StateFlow<String?> = _displayName.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _toastUndoActionId = MutableStateFlow<String?>(null)
    val toastUndoActionId: StateFlow<String?> = _toastUndoActionId.asStateFlow()

    fun dismissToast() {
        _toastMessage.value = null
        _toastUndoActionId.value = null
    }

    init {
        loadCurrentScreen()
    }

    /**
     * Loads the current screen from the core workflow.
     */
    fun loadCurrentScreen() {
        viewModelScope.launch {
            try {
                val screenJson =
                    withContext(Dispatchers.IO) {
                        workflow.currentScreenJson()
                    }
                _screen.value = json.decodeFromString<ScreenModel>(screenJson)
                _error.value = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load screen", e)
                _error.value = "Failed to load screen: ${e.message}"
            }
        }
    }

    /**
     * Forwards a user action to core and processes the result.
     */
    fun handleAction(action: UserAction) {
        viewModelScope.launch {
            try {
                val actionJson = json.encodeToString(UserAction.serializer(), action)
                val resultJson =
                    withContext(Dispatchers.IO) {
                        workflow.handleActionJson(actionJson)
                    }
                val result = json.decodeFromString<ActionResult>(resultJson)
                processResult(result)
                _error.value = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle action", e)
                _error.value = "Failed to handle action: ${e.message}"
            }
        }
    }

    /**
     * Returns the onboarding data as JSON when complete.
     * Callers can use this to persist the data.
     */
    suspend fun getOnboardingDataJson(): String? =
        try {
            withContext(Dispatchers.IO) {
                workflow.onboardingDataJson()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get onboarding data", e)
            null
        }

    private fun processResult(result: ActionResult) {
        when (result) {
            is ActionResult.UpdateScreen -> {
                _screen.value = result.screen
            }

            is ActionResult.NavigateTo -> {
                _screen.value = result.screen
            }

            is ActionResult.ValidationError -> {
                // Update the current screen's component with the validation error.
                // The core should send an UpdateScreen with the error already set,
                // but we handle this as a fallback.
                val currentScreen = _screen.value ?: return
                val updatedComponents =
                    currentScreen.components.map { component ->
                        if (component is Component.TextInput && component.id == result.componentId) {
                            component.copy(validationError = result.message)
                        } else {
                            component
                        }
                    }
                _screen.value = currentScreen.copy(components = updatedComponents)
            }

            is ActionResult.Complete -> {
                viewModelScope.launch {
                    val dataJson = getOnboardingDataJson()
                    if (dataJson != null) {
                        try {
                            val data = json.decodeFromString<OnboardingData>(dataJson)
                            _displayName.value = data.displayName
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse onboarding data", e)
                        }
                    }
                    _isComplete.value = true
                }
            }

            is ActionResult.ShowToast -> {
                _toastMessage.value = result.message
                _toastUndoActionId.value = result.undoActionId
                val message = result.message
                viewModelScope.launch {
                    delay(TOAST_DURATION_MS)
                    if (_toastMessage.value == message) {
                        dismissToast()
                    }
                }
            }

            is ActionResult.StartDeviceLink,
            is ActionResult.StartBackupImport,
            is ActionResult.BackupExportComplete,
            is ActionResult.OpenContact,
            is ActionResult.EditContact,
            is ActionResult.OpenUrl,
            is ActionResult.ShowAlert,
            is ActionResult.OpenEntryDetail,
            is ActionResult.RequestCamera,
            is ActionResult.WipeComplete,
            is ActionResult.ExchangeCommands,
            is ActionResult.ShowFormDialog,
            is ActionResult.PreviewAs,
            is ActionResult.Unknown,
            -> {
                // These results are not expected during onboarding.
                Log.w(TAG, "Unexpected ActionResult during onboarding: ${result::class.simpleName}")
            }
        }
    }

    companion object {
        private const val TAG = "OnboardingViewModel"
        private const val TOAST_DURATION_MS = 5000L
    }
}
