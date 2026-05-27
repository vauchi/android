// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.components.ActionListComponent
import app.vauchi.ui.coreui.components.AvatarPreviewComponent
import app.vauchi.ui.coreui.components.BannerComponent
import app.vauchi.ui.coreui.components.ConfirmationDialogComponent
import app.vauchi.ui.coreui.components.DividerComponent
import app.vauchi.ui.coreui.components.DropdownComponent
import app.vauchi.ui.coreui.components.EditableTextComponent
import app.vauchi.ui.coreui.components.FieldListComponent
import app.vauchi.ui.coreui.components.InfoPanelComponent
import app.vauchi.ui.coreui.components.InlineConfirmComponent
import app.vauchi.ui.coreui.components.ListComponent
import app.vauchi.ui.coreui.components.PinInputComponent
import app.vauchi.ui.coreui.components.PreviewComponent
import app.vauchi.ui.coreui.components.QrCodeComponent
import app.vauchi.ui.coreui.components.SettingsGroupComponent
import app.vauchi.ui.coreui.components.SliderComponent
import app.vauchi.ui.coreui.components.StatusIndicatorComponent
import app.vauchi.ui.coreui.components.TextComponent
import app.vauchi.ui.coreui.components.TextInputComponent
import app.vauchi.ui.coreui.components.ToastOverlay
import app.vauchi.ui.coreui.components.ToggleListComponent

/**
 * Generic screen renderer that maps a core [ScreenModel] to Compose UI.
 *
 * This is the main integration point between core-driven UI and Android.
 * Core describes *what* to render via [ScreenModel], and this composable
 * decides *how* to render it using Material3 components.
 *
 * User interactions are forwarded back to core via [onAction].
 */
@Composable
fun ScreenRenderer(
    screen: ScreenModel,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
    toastMessage: String? = null,
    toastUndoActionId: String? = null,
    onToastDismiss: () -> Unit = {},
) {
    Box(modifier = modifier) {
        // Outer non-scrolling column splits the viewport into a
        // scrolling content region (weight 1f) and a sticky action
        // footer. The previous single-scroll layout put actions inside
        // the verticalScroll, so on small viewports (e.g. Pixel 3a at
        // the Groups onboarding step) "Skip" fell below the visible
        // fold beneath "Continue" — the user had to scroll to find
        // it. Sticky footer keeps every action above the fold without
        // shrinking the content density. Repro:
        // _private/docs/problems/2026-05-21-mobile-onboarding-final-step-
        // and-skip-fold G1.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
            ) {
                // Progress indicator
                screen.progress?.let { progress ->
                    LinearProgressIndicator(
                        progress = {
                            if (progress.totalSteps > 0) {
                                progress.currentStep.toFloat() / progress.totalSteps.toFloat()
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    progress.label?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Title
                Text(
                    text = screen.title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )

                // Subtitle
                screen.subtitle?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Components — key by id so Compose preserves slot identity
                // (and any AndroidView state, like the QrScanner camera
                // binding) across ScreenModel re-emissions. Without this,
                // a sibling component whose data churns (e.g. the multipart
                // QR's Display peer cycling every ~300 ms) tears down the
                // scanner's PreviewView between recompositions and the
                // camera surface goes black.
                screen.components.forEachIndexed { index, component ->
                    key(componentSlotKey(component, index)) {
                        ComponentRenderer(component = component, onAction = onAction)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }

            // Sticky action footer — outside the verticalScroll above.
            screen.actions.forEach { action ->
                ActionButton(action = action, onAction = onAction)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Toast overlay (top-aligned, above scroll content)
        ToastOverlay(
            message = toastMessage ?: "",
            visible = toastMessage != null,
            undoLabel = if (toastUndoActionId != null) "Undo" else null,
            onUndo =
                toastUndoActionId?.let { actionId ->
                    { onAction(UserAction.UndoPressed(actionId = actionId)) }
                },
            onDismiss = onToastDismiss,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * Dispatches rendering to the appropriate component composable.
 */
@Composable
/**
 * Stable key for a component slot. Most variants carry an `id` field
 * (set by core for action routing); the two id-less singletons
 * (`Divider`, `Unknown`) fall back to the variant tag plus the list
 * index so two adjacent dividers don't collide.
 */
private fun componentSlotKey(
    component: Component,
    index: Int,
): String =
    when (component) {
        is Component.ActionList -> "action_list:${component.id}"
        is Component.AvatarPreview -> "avatar:${component.id}"
        is Component.Banner -> "banner@$index"
        is Component.ConfirmationDialog -> "confirm:${component.id}"
        is Component.Dropdown -> "dropdown:${component.id}"
        is Component.EditableText -> "editable:${component.id}"
        is Component.FieldList -> "field_list:${component.id}"
        is Component.InfoPanel -> "info:${component.id}"
        is Component.InlineConfirm -> "inline_confirm:${component.id}"
        is Component.List -> "list:${component.id}"
        is Component.PinInput -> "pin:${component.id}"
        is Component.Preview -> "preview:${component.name}"
        is Component.QrCode -> "qr:${component.id}"
        is Component.SettingsGroup -> "settings:${component.id}"
        is Component.ShowToast -> "toast:${component.id}"
        is Component.Slider -> "slider:${component.id}"
        is Component.StatusIndicator -> "status:${component.id}"
        is Component.Text -> "text:${component.id}"
        is Component.TextInput -> "text_input:${component.id}"
        is Component.ToggleList -> "toggle:${component.id}"
        Component.Divider -> "divider@$index"
        Component.Unknown -> "unknown@$index"
    }

@Composable
fun ComponentRenderer(
    component: Component,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (component) {
        is Component.Text -> {
            TextComponent(
                content = component.content,
                style = component.style,
                modifier = modifier,
            )
        }

        is Component.TextInput -> {
            TextInputComponent(
                componentId = component.id,
                label = component.label,
                value = component.value,
                placeholder = component.placeholder,
                maxLength = component.maxLength,
                validationError = component.validationError,
                inputType = component.inputType,
                onAction = onAction,
                modifier = modifier,
            )
        }

        is Component.ToggleList -> {
            ToggleListComponent(
                componentId = component.id,
                label = component.label,
                items = component.items,
                onAction = onAction,
                modifier = modifier,
                a11y = component.a11y,
            )
        }

        is Component.FieldList -> {
            FieldListComponent(
                fields = component.fields,
                visibilityMode = component.visibilityMode,
                availableGroups = component.availableGroups,
                onAction = onAction,
                modifier = modifier,
                a11y = component.a11y,
            )
        }

        is Component.Preview -> {
            PreviewComponent(
                name = component.name,
                initials = component.initials,
                fields = component.fields,
                variants = component.variants,
                selectedVariant = component.selectedVariant,
                visibleFields = component.visibleFields,
                onAction = onAction,
                modifier = modifier,
                avatarData = component.avatarData,
                a11y = component.a11y,
            )
        }

        is Component.InfoPanel -> {
            InfoPanelComponent(
                icon = component.icon,
                title = component.title,
                items = component.items,
                modifier = modifier,
                a11y = component.a11y,
            )
        }

        is Component.List -> {
            ListComponent(
                componentId = component.id,
                items = component.items,
                searchable = component.searchable,
                onAction = onAction,
                modifier = modifier,
            )
        }

        is Component.SettingsGroup -> {
            SettingsGroupComponent(
                componentId = component.id,
                label = component.label,
                items = component.items,
                onAction = onAction,
                modifier = modifier,
            )
        }

        is Component.ActionList -> {
            ActionListComponent(
                componentId = component.id,
                items = component.items,
                onAction = onAction,
                modifier = modifier,
            )
        }

        is Component.StatusIndicator -> {
            StatusIndicatorComponent(
                icon = component.icon,
                title = component.title,
                detail = component.detail,
                status = component.status,
                a11y = component.a11y,
                modifier = modifier,
            )
        }

        is Component.PinInput -> {
            PinInputComponent(
                componentId = component.id,
                label = component.label,
                length = component.length,
                masked = component.masked,
                validationError = component.validationError,
                onAction = onAction,
                modifier = modifier,
            )
        }

        is Component.QrCode -> {
            QrCodeComponent(
                componentId = component.id,
                data = component.data,
                mode = component.mode,
                label = component.label,
                onAction = onAction,
                modifier = modifier,
            )
        }

        is Component.ConfirmationDialog -> {
            ConfirmationDialogComponent(
                componentId = component.id,
                title = component.title,
                message = component.message,
                confirmText = component.confirmText,
                destructive = component.destructive,
                onAction = onAction,
                modifier = modifier,
            )
        }

        is Component.ShowToast -> {
            // Core never emits Component.ShowToast (only ActionResult.ShowToast).
            // If core adds this variant, wire it to ToastOverlay here.
        }

        is Component.InlineConfirm -> {
            InlineConfirmComponent(
                componentId = component.id,
                warning = component.warning,
                confirmText = component.confirmText,
                cancelText = component.cancelText,
                destructive = component.destructive,
                onAction = onAction,
                modifier = modifier,
            )
        }

        is Component.EditableText -> {
            EditableTextComponent(
                componentId = component.id,
                label = component.label,
                value = component.value,
                editing = component.editing,
                validationError = component.validationError,
                onAction = onAction,
                modifier = modifier,
            )
        }

        is Component.Banner -> {
            BannerComponent(
                text = component.text,
                actionLabel = component.actionLabel,
                actionId = component.actionId,
                onAction = onAction,
                modifier = modifier,
                a11y = component.a11y,
            )
        }

        is Component.Dropdown -> {
            DropdownComponent(
                componentId = component.id,
                label = component.label,
                selected = component.selected,
                options = component.options,
                onAction = onAction,
                modifier = modifier,
            )
        }

        is Component.AvatarPreview -> {
            AvatarPreviewComponent(
                id = component.id,
                imageData = component.imageData,
                initials = component.initials,
                bgColor = component.bgColor,
                brightness = component.brightness,
                editable = component.editable,
                onAction = onAction,
                modifier = modifier,
                a11y = component.a11y,
            )
        }

        is Component.Slider -> {
            SliderComponent(
                componentId = component.id,
                label = component.label,
                value = component.value,
                min = component.min,
                max = component.max,
                step = component.step,
                minIcon = component.minIcon,
                maxIcon = component.maxIcon,
                onAction = onAction,
                modifier = modifier,
                a11y = component.a11y,
            )
        }

        is Component.Divider -> {
            DividerComponent(modifier = modifier)
        }

        is Component.Unknown -> {
            // Core sent a component this shell doesn't know about.
            // Render nothing — screen still works, just missing one component.
        }
    }
}

/**
 * Renders a screen action as the appropriate button style.
 */
@Composable
private fun ActionButton(
    action: ScreenAction,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (action.style) {
        ActionStyle.Primary -> {
            Button(
                onClick = { onAction(UserAction.ActionPressed(actionId = action.id)) },
                enabled = action.enabled,
                modifier = modifier.fillMaxWidth(),
            ) {
                Text(action.label)
            }
        }

        ActionStyle.Secondary -> {
            OutlinedButton(
                onClick = { onAction(UserAction.ActionPressed(actionId = action.id)) },
                enabled = action.enabled,
                modifier = modifier.fillMaxWidth(),
            ) {
                Text(action.label)
            }
        }

        ActionStyle.Destructive -> {
            TextButton(
                onClick = { onAction(UserAction.ActionPressed(actionId = action.id)) },
                enabled = action.enabled,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                modifier = modifier.fillMaxWidth(),
            ) {
                Text(action.label)
            }
        }
    }
}
