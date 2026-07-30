// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

internal enum class ShortcutGesture {
    Back,
    Navigation,
    Primary,
    Secondary,
    Undo,
}

internal fun contextualShortcut(
    bar: ContextBar?,
    gesture: ShortcutGesture,
): ActionSpec? {
    val action =
        when (gesture) {
            ShortcutGesture.Back -> {
                bar?.back
            }

            ShortcutGesture.Navigation -> {
                bar?.navigation
            }

            ShortcutGesture.Primary -> {
                bar?.primary?.takeUnless { it.shortcut == "undo" }
            }

            ShortcutGesture.Secondary -> {
                bar?.secondary
            }

            ShortcutGesture.Undo -> {
                bar?.primary?.takeIf { it.shortcut == "undo" }
            }
        }
    return action?.takeIf(ActionSpec::enabled)
}

internal fun rememberFocusedBinding(
    current: String?,
    bindingId: String,
    focused: Boolean,
): String? = if (focused) bindingId else current

internal fun shouldRestoreFocus(
    rememberedBindingId: String?,
    bindingId: String,
): Boolean = rememberedBindingId == bindingId
