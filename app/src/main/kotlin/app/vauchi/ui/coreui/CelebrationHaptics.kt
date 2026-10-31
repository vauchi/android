// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import android.os.Build
import android.view.HapticFeedbackConstants

/**
 * The [HapticFeedbackConstants] value for the haptic intent Core names in
 * `Command::Celebrate`, or null when it asks for none or names one this
 * build does not know.
 */
fun celebrationHapticFeedback(
    haptic: String,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Int? =
    when (haptic) {
        "success" -> {
            if (sdkInt >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        }

        "light" -> {
            HapticFeedbackConstants.CLOCK_TICK
        }

        else -> {
            null
        }
    }
