// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.Field
import app.vauchi.ui.coreui.InfoItem
import app.vauchi.ui.coreui.InputType
import app.vauchi.ui.coreui.PreviewVariant
import app.vauchi.ui.coreui.TextStyle
import app.vauchi.ui.coreui.ToggleItem
import app.vauchi.ui.coreui.UiFieldVisibility
import app.vauchi.ui.coreui.UserAction
import app.vauchi.ui.coreui.VisibilityMode
import app.vauchi.ui.coreui.components.EditableTextComponent
import app.vauchi.ui.coreui.components.FieldListComponent
import app.vauchi.ui.coreui.components.InfoPanelComponent
import app.vauchi.ui.coreui.components.InlineConfirmComponent
import app.vauchi.ui.coreui.components.PreviewComponent
import app.vauchi.ui.coreui.components.TextComponent
import app.vauchi.ui.coreui.components.TextInputComponent
import app.vauchi.ui.coreui.components.ToggleListComponent
import app.vauchi.ui.theme.VauchiTheme
import com.android.tools.screenshot.PreviewTest

// VRT device spec: 360dp wide, 800dp tall, xhdpi (2x) = 720×1600 px.
private const val VRT_DEVICE = "spec:width=360dp,height=800dp,dpi=320"

// =============================================================
// TextInputComponent
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun TextInputComponentEmptyScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextInputComponent(
                    componentId = "display_name",
                    label = "Display Name",
                    value = "",
                    placeholder = "Enter your name",
                    maxLength = 50,
                    validationError = null,
                    inputType = InputType.Text,
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun TextInputComponentFilledScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextInputComponent(
                    componentId = "display_name",
                    label = "Display Name",
                    value = "Alice",
                    placeholder = "Enter your name",
                    maxLength = 50,
                    validationError = null,
                    inputType = InputType.Text,
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun TextInputComponentErrorScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextInputComponent(
                    componentId = "display_name",
                    label = "Display Name",
                    value = "",
                    placeholder = "Enter your name",
                    maxLength = 50,
                    validationError = "Name is required",
                    inputType = InputType.Text,
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun TextInputComponentEmailScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextInputComponent(
                    componentId = "email",
                    label = "Email",
                    value = "alice@example.com",
                    placeholder = "your@email.com",
                    maxLength = null,
                    validationError = null,
                    inputType = InputType.Email,
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun TextInputComponentPhoneScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextInputComponent(
                    componentId = "phone",
                    label = "Phone",
                    value = "+41 79 123 45 67",
                    placeholder = "+41...",
                    maxLength = null,
                    validationError = null,
                    inputType = InputType.Phone,
                    onAction = {},
                )
            }
        }
    }
}

// =============================================================
// ToggleListComponent
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun ToggleListComponentMixedScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                ToggleListComponent(
                    componentId = "groups",
                    label = "Select Groups",
                    items =
                        listOf(
                            ToggleItem(id = "family", label = "Family", selected = true, subtitle = "Close relatives"),
                            ToggleItem(id = "friends", label = "Friends", selected = false, subtitle = "Personal contacts"),
                            ToggleItem(id = "coworkers", label = "Coworkers", selected = true, subtitle = null),
                            ToggleItem(id = "business", label = "Business", selected = false, subtitle = null),
                        ),
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun ToggleListComponentAllSelectedScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                ToggleListComponent(
                    componentId = "groups",
                    label = "Default Groups",
                    items =
                        listOf(
                            ToggleItem(id = "family", label = "Family", selected = true),
                            ToggleItem(id = "friends", label = "Friends", selected = true),
                            ToggleItem(id = "coworkers", label = "Coworkers", selected = true),
                            ToggleItem(id = "business", label = "Business", selected = true),
                        ),
                    onAction = {},
                )
            }
        }
    }
}

// =============================================================
// FieldListComponent — ShowHide mode
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun FieldListComponentShowHideScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                FieldListComponent(
                    fields =
                        listOf(
                            Field(
                                id = "field-1",
                                fieldType = "email",
                                label = "Email",
                                value = "alice@example.com",
                                visibility = UiFieldVisibility.Shown,
                            ),
                            Field(
                                id = "field-2",
                                fieldType = "phone",
                                label = "Phone",
                                value = "+41 79 123 45 67",
                                visibility = UiFieldVisibility.Hidden,
                            ),
                            Field(
                                id = "field-3",
                                fieldType = "social",
                                label = "GitHub",
                                value = "alice",
                                visibility = UiFieldVisibility.Shown,
                            ),
                        ),
                    visibilityMode = VisibilityMode.ShowHide,
                    availableGroups = emptyList(),
                    onAction = {},
                )
            }
        }
    }
}

// =============================================================
// FieldListComponent — PerGroup mode
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun FieldListComponentPerGroupScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                FieldListComponent(
                    fields =
                        listOf(
                            Field(
                                id = "field-1",
                                fieldType = "email",
                                label = "Email",
                                value = "alice@example.com",
                                visibility = UiFieldVisibility.Scopes(listOf("Family", "Friends")),
                            ),
                            Field(
                                id = "field-2",
                                fieldType = "phone",
                                label = "Phone",
                                value = "+41 79 123 45 67",
                                visibility = UiFieldVisibility.Scopes(listOf("Family")),
                            ),
                        ),
                    visibilityMode = VisibilityMode.PerGroup,
                    availableGroups = listOf("Family", "Friends", "Work"),
                    onAction = {},
                )
            }
        }
    }
}

// =============================================================
// PreviewComponent
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun PreviewComponentScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                val testFields =
                    listOf(
                        Field(
                            id = "field-1",
                            fieldType = "email",
                            label = "Email",
                            value = "alice@example.com",
                            visibility = UiFieldVisibility.Shown,
                        ),
                        Field(
                            id = "field-2",
                            fieldType = "phone",
                            label = "Phone",
                            value = "+41 79 123 45 67",
                            visibility = UiFieldVisibility.Shown,
                        ),
                        Field(
                            id = "field-3",
                            fieldType = "social",
                            label = "GitHub",
                            value = "alice",
                            visibility = UiFieldVisibility.Shown,
                        ),
                    )
                PreviewComponent(
                    name = "Alice",
                    initials = "A",
                    fields = testFields,
                    variants = emptyList(),
                    selectedVariant = null,
                    visibleFields = testFields,
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun PreviewComponentWithGroupsScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                val testFields =
                    listOf(
                        Field(
                            id = "field-1",
                            fieldType = "email",
                            label = "Email",
                            value = "alice@example.com",
                            visibility = UiFieldVisibility.Shown,
                        ),
                        Field(
                            id = "field-2",
                            fieldType = "phone",
                            label = "Phone",
                            value = "+41 79 123 45 67",
                            visibility = UiFieldVisibility.Shown,
                        ),
                    )
                PreviewComponent(
                    name = "Alice",
                    initials = "A",
                    fields = testFields,
                    variants =
                        listOf(
                            PreviewVariant(
                                variantId = "Family",
                                displayName = "Alice",
                                visibleFields =
                                    listOf(
                                        Field(
                                            id = "field-1",
                                            fieldType = "email",
                                            label = "Email",
                                            value = "alice@example.com",
                                            visibility = UiFieldVisibility.Shown,
                                        ),
                                        Field(
                                            id = "field-2",
                                            fieldType = "phone",
                                            label = "Phone",
                                            value = "+41 79 123 45 67",
                                            visibility = UiFieldVisibility.Shown,
                                        ),
                                    ),
                            ),
                            PreviewVariant(
                                variantId = "Work",
                                displayName = "Alice M.",
                                visibleFields =
                                    listOf(
                                        Field(
                                            id = "field-1",
                                            fieldType = "email",
                                            label = "Email",
                                            value = "alice@example.com",
                                            visibility = UiFieldVisibility.Shown,
                                        ),
                                    ),
                            ),
                        ),
                    selectedVariant = null,
                    visibleFields = testFields,
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun PreviewComponentEmptyScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                PreviewComponent(
                    name = "Alice",
                    initials = "A",
                    fields = emptyList(),
                    variants = emptyList(),
                    selectedVariant = null,
                    visibleFields = emptyList(),
                    onAction = {},
                )
            }
        }
    }
}

// =============================================================
// InfoPanelComponent
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun InfoPanelComponentScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoPanelComponent(
                    icon = "shield",
                    title = "Security Information",
                    items =
                        listOf(
                            InfoItem(
                                icon = "lock",
                                title = "End-to-End Encrypted",
                                detail = "Your data is encrypted on your device before being sent.",
                            ),
                            InfoItem(
                                icon = "visibility_off",
                                title = "No Central Server",
                                detail = "Updates are delivered directly via relays. No one else can read them.",
                            ),
                            InfoItem(
                                icon = "key",
                                title = "You Own Your Keys",
                                detail = "Your cryptographic identity never leaves your device.",
                            ),
                        ),
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun InfoPanelComponentNoIconScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoPanelComponent(
                    icon = null,
                    title = "Getting Started",
                    items =
                        listOf(
                            InfoItem(
                                icon = null,
                                title = "Create your identity",
                                detail = "Choose a display name that your contacts will see.",
                            ),
                            InfoItem(
                                icon = null,
                                title = "Add contact information",
                                detail = "Add phone, email, or social handles to share.",
                            ),
                        ),
                )
            }
        }
    }
}

// =============================================================
// TextComponent
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun TextComponentStylesScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextComponent(content = "Title Style", style = TextStyle.Title)
                Spacer(modifier = Modifier.height(8.dp))
                TextComponent(content = "Subtitle Style", style = TextStyle.Subtitle)
                Spacer(modifier = Modifier.height(8.dp))
                TextComponent(content = "Body Style — This is the main text content.", style = TextStyle.Body)
                Spacer(modifier = Modifier.height(8.dp))
                TextComponent(content = "Caption Style — Small secondary text.", style = TextStyle.Caption)
            }
        }
    }
}

// =============================================================
// Dark Mode Variants
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun TextInputComponentFilledDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextInputComponent(
                    componentId = "display_name",
                    label = "Display Name",
                    value = "Alice",
                    placeholder = "Enter your name",
                    maxLength = 50,
                    validationError = null,
                    inputType = InputType.Text,
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun ToggleListComponentMixedDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                ToggleListComponent(
                    componentId = "groups",
                    label = "Select Groups",
                    items =
                        listOf(
                            ToggleItem(id = "family", label = "Family", selected = true, subtitle = "Close relatives"),
                            ToggleItem(id = "friends", label = "Friends", selected = false, subtitle = "Personal contacts"),
                            ToggleItem(id = "coworkers", label = "Coworkers", selected = true, subtitle = null),
                            ToggleItem(id = "business", label = "Business", selected = false, subtitle = null),
                        ),
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun FieldListComponentShowHideDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                FieldListComponent(
                    fields =
                        listOf(
                            Field(
                                id = "field-1",
                                fieldType = "email",
                                label = "Email",
                                value = "alice@example.com",
                                visibility = UiFieldVisibility.Shown,
                            ),
                            Field(
                                id = "field-2",
                                fieldType = "phone",
                                label = "Phone",
                                value = "+41 79 123 45 67",
                                visibility = UiFieldVisibility.Hidden,
                            ),
                        ),
                    visibilityMode = VisibilityMode.ShowHide,
                    availableGroups = emptyList(),
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun PreviewComponentDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                val testFields =
                    listOf(
                        Field(
                            id = "field-1",
                            fieldType = "email",
                            label = "Email",
                            value = "alice@example.com",
                            visibility = UiFieldVisibility.Shown,
                        ),
                        Field(
                            id = "field-2",
                            fieldType = "phone",
                            label = "Phone",
                            value = "+41 79 123 45 67",
                            visibility = UiFieldVisibility.Shown,
                        ),
                    )
                PreviewComponent(
                    name = "Alice",
                    initials = "A",
                    fields = testFields,
                    variants = emptyList(),
                    selectedVariant = null,
                    visibleFields = testFields,
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun InfoPanelComponentDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoPanelComponent(
                    icon = "shield",
                    title = "Security Information",
                    items =
                        listOf(
                            InfoItem(
                                icon = "lock",
                                title = "End-to-End Encrypted",
                                detail = "Your data is encrypted on your device before being sent.",
                            ),
                            InfoItem(
                                icon = "visibility_off",
                                title = "No Central Server",
                                detail = "Updates are delivered directly via relays. No one else can read them.",
                            ),
                        ),
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun TextComponentStylesDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextComponent(content = "Title Style", style = TextStyle.Title)
                Spacer(modifier = Modifier.height(8.dp))
                TextComponent(content = "Subtitle Style", style = TextStyle.Subtitle)
                Spacer(modifier = Modifier.height(8.dp))
                TextComponent(content = "Body Style — This is the main text content.", style = TextStyle.Body)
                Spacer(modifier = Modifier.height(8.dp))
                TextComponent(content = "Caption Style — Small secondary text.", style = TextStyle.Caption)
            }
        }
    }
}

// =============================================================
// InlineConfirmComponent
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun InlineConfirmComponentDestructiveScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InlineConfirmComponent(
                    componentId = "confirm-delete",
                    warning = "Are you sure you want to delete this contact?",
                    confirmText = "Delete",
                    cancelText = "Cancel",
                    destructive = true,
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun InlineConfirmComponentNonDestructiveScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InlineConfirmComponent(
                    componentId = "confirm-merge",
                    warning = "Merge these two contacts?",
                    confirmText = "Merge",
                    cancelText = "Keep Separate",
                    destructive = false,
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun InlineConfirmComponentDestructiveDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                InlineConfirmComponent(
                    componentId = "confirm-delete",
                    warning = "Are you sure you want to delete this contact?",
                    confirmText = "Delete",
                    cancelText = "Cancel",
                    destructive = true,
                    onAction = {},
                )
            }
        }
    }
}

// =============================================================
// EditableTextComponent
// =============================================================

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun EditableTextComponentDisplayScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                EditableTextComponent(
                    componentId = "display-name",
                    label = "Display Name",
                    value = "Alice",
                    editing = false,
                    validationError = null,
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun EditableTextComponentEditingScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                EditableTextComponent(
                    componentId = "display-name",
                    label = "Display Name",
                    value = "Alice",
                    editing = true,
                    validationError = null,
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun EditableTextComponentErrorScreenshot() {
    VauchiTheme(dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                EditableTextComponent(
                    componentId = "display-name",
                    label = "Display Name",
                    value = "",
                    editing = true,
                    validationError = "Name cannot be empty",
                    onAction = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(showSystemUi = true, device = VRT_DEVICE)
@Composable
fun EditableTextComponentDisplayDarkScreenshot() {
    VauchiTheme(darkTheme = true, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                EditableTextComponent(
                    componentId = "display-name",
                    label = "Display Name",
                    value = "Alice",
                    editing = false,
                    validationError = null,
                    onAction = {},
                )
            }
        }
    }
}
