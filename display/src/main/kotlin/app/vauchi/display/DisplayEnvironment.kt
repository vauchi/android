// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import android.content.res.Configuration
import android.view.InputDevice
import app.vauchi.ui.presentation.InputMode

internal fun presentationInputModes(configuration: Configuration): List<InputMode> =
    buildList {
        add(InputMode.Touch)
        if (configuration.keyboard != Configuration.KEYBOARD_NOKEYS) add(InputMode.Keyboard)
        if (
            InputDevice.getDeviceIds().asSequence().mapNotNull(InputDevice::getDevice).any {
                it.supportsSource(InputDevice.SOURCE_MOUSE) || it.supportsSource(InputDevice.SOURCE_TOUCHPAD)
            }
        ) {
            add(InputMode.Pointer)
        }
    }
