// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.window

import app.vauchi.contract.Orientation

/** Main-thread native presentation changes and restoration, owned by one display. */
interface WindowControlHost : AutoCloseable {
    fun setBrightness(level: Float?)

    fun setIdleTimerDisabled(disabled: Boolean)

    fun setOrientation(orientation: Orientation?)
}

internal class WindowHostUnavailable : IllegalStateException("window host unavailable")
