// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import uniffi.vauchi_platform.MobileExchangeHardwareEvent
import uniffi.vauchi_platform.PlatformAppEngine

/**
 * ViewModel that bridges [PlatformAppEngine] to Compose UI for core-driven screens.
 *
 * This is the Android equivalent of iOS/macOS AppViewModel. A single instance
 * is shared across all core-driven tabs — one engine, one DB connection.
 *
 * Core describes what to render via [ScreenModel]. User interactions flow back
 * as [UserAction] JSON. Core returns [ActionResult] directing the UI.
 */
class CoreAppViewModel(
    private val appEngine: PlatformAppEngine,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }

    private val _screen = MutableStateFlow<ScreenModel?>(null)
    val screen: StateFlow<ScreenModel?> = _screen.asStateFlow()

    private val _availableScreens = MutableStateFlow<List<String>>(emptyList())
    val availableScreens: StateFlow<List<String>> = _availableScreens.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _toastUndoActionId = MutableStateFlow<String?>(null)
    val toastUndoActionId: StateFlow<String?> = _toastUndoActionId.asStateFlow()

    private val _alertMessage = MutableStateFlow<Pair<String, String>?>(null)
    val alertMessage: StateFlow<Pair<String, String>?> = _alertMessage.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _openUrlEvent = MutableStateFlow<String?>(null)
    val openUrlEvent: StateFlow<String?> = _openUrlEvent.asStateFlow()

    fun consumeOpenUrlEvent() {
        _openUrlEvent.value = null
    }

    /**
     * Image picking events emitted by core via ExchangeCommands.
     * Values: "library", "camera", null (consumed).
     */
    private val _imagePickEvent = MutableStateFlow<String?>(null)
    val imagePickEvent: StateFlow<String?> = _imagePickEvent.asStateFlow()

    fun consumeImagePickEvent() {
        _imagePickEvent.value = null
    }

    /**
     * Called by the Activity/Composable when the user picks or captures an image.
     * Sends the image bytes back to core as an ImageReceived hardware event.
     */
    fun handleImageReceived(imageBytes: ByteArray) {
        sendHardwareEvent(MobileExchangeHardwareEvent.ImageReceived(data = imageBytes))
    }

    /**
     * Called when the user cancels the image picker.
     */
    fun handleImagePickCancelled() {
        sendHardwareEvent(MobileExchangeHardwareEvent.ImagePickCancelled)
    }

    private fun sendHardwareEvent(event: MobileExchangeHardwareEvent) {
        viewModelScope.launch {
            try {
                val resultJson =
                    withContext(Dispatchers.IO) {
                        appEngine.handleHardwareEvent(event = event)
                    }
                if (resultJson != null) {
                    val result = json.decodeFromString<ActionResult>(resultJson)
                    applyResult(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send hardware event", e)
            }
        }
    }

    init {
        loadAvailableScreens()
        loadScreen()
    }

    fun loadAvailableScreens() {
        viewModelScope.launch {
            try {
                val screensJson =
                    withContext(Dispatchers.IO) {
                        appEngine.availableScreensJson()
                    }
                _availableScreens.value = json.decodeFromString(screensJson)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load available screens", e)
            }
        }
    }

    fun loadScreen() {
        viewModelScope.launch {
            try {
                val screenJson =
                    withContext(Dispatchers.IO) {
                        appEngine.currentScreenJson()
                    }
                _screen.value = json.decodeFromString<ScreenModel>(screenJson)
                _error.value = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load screen", e)
                _error.value = "Failed to load screen: ${e.message}"
            }
        }
    }

    fun handleAction(action: UserAction) {
        viewModelScope.launch {
            try {
                val actionJson = json.encodeToString(UserAction.serializer(), action)
                val resultJson =
                    withContext(Dispatchers.IO) {
                        appEngine.handleActionJson(actionJson = actionJson)
                    }
                val result = json.decodeFromString<ActionResult>(resultJson)
                applyResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle action", e)
                _error.value = "Action failed: ${e.message}"
            }
        }
    }

    fun navigateTo(screenName: String) {
        viewModelScope.launch {
            try {
                val screenJson =
                    withContext(Dispatchers.IO) {
                        appEngine.navigateToJson(screenJson = "\"$screenName\"")
                    }
                _screen.value = json.decodeFromString<ScreenModel>(screenJson)
                loadAvailableScreens()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to navigate to $screenName", e)
            }
        }
    }

    fun navigateBack() {
        viewModelScope.launch {
            try {
                val screenJson =
                    withContext(Dispatchers.IO) {
                        appEngine.navigateBackJson()
                    }
                _screen.value = json.decodeFromString<ScreenModel>(screenJson)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to navigate back", e)
            }
        }
    }

    fun invalidateAll() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { appEngine.invalidateAll() }
                loadAvailableScreens()
                loadScreen()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to invalidate", e)
            }
        }
    }

    fun dismissToast() {
        _toastMessage.value = null
        _toastUndoActionId.value = null
    }

    fun dismissAlert() {
        _alertMessage.value = null
    }

    private fun applyResult(result: ActionResult) {
        when (result) {
            is ActionResult.UpdateScreen -> {
                _screen.value = result.screen
            }

            is ActionResult.NavigateTo -> {
                _screen.value = result.screen
            }

            is ActionResult.ValidationError -> {
                // Core patches validation into screen components via AppEngine
                loadScreen()
            }

            is ActionResult.Complete, is ActionResult.WipeComplete -> {
                loadScreen()
            }

            is ActionResult.ShowToast -> {
                _toastMessage.value = result.message
                _toastUndoActionId.value = result.undoActionId
            }

            is ActionResult.ShowAlert -> {
                _alertMessage.value = Pair(result.title, result.message)
            }

            is ActionResult.OpenContact -> {
                navigateTo("ContactDetail")
            }

            is ActionResult.EditContact -> {
                navigateTo("ContactEdit")
            }

            is ActionResult.OpenEntryDetail -> {
                navigateTo("EntryDetail")
            }

            is ActionResult.OpenUrl -> {
                _openUrlEvent.value = result.url
            }

            is ActionResult.ExchangeCommands -> {
                // ADR-031: hardware exchange commands handled by exchange session.
                // Image picking commands are dispatched to the UI layer.
                for (cmd in result.commands) {
                    when (cmd) {
                        is ExchangeCommandDTO.ImagePickFromLibrary -> {
                            _imagePickEvent.value = "library"
                        }

                        is ExchangeCommandDTO.ImageCaptureFromCamera -> {
                            _imagePickEvent.value = "camera"
                        }

                        is ExchangeCommandDTO.ImagePickFromFile -> {
                            // File picking not supported on Android — report unavailable
                            sendHardwareEvent(
                                MobileExchangeHardwareEvent.HardwareUnavailable("ImagePickFromFile"),
                            )
                        }

                        else -> {
                            // Other exchange commands handled by exchange session
                        }
                    }
                }
            }

            is ActionResult.ShowFormDialog -> {
                // Dialog presentation handled by NavigateTo — no separate action needed
            }

            is ActionResult.PreviewAs -> {
                // Card preview handled by NavigateTo — no separate action needed
            }

            is ActionResult.StartDeviceLink, is ActionResult.StartBackupImport -> {
                // Handled by native Android flows
            }

            is ActionResult.RequestCamera -> {
                loadScreen()
            }

            is ActionResult.Unknown -> { /* no-op */ }
        }
    }

    companion object {
        private const val TAG = "CoreAppVM"
    }
}
