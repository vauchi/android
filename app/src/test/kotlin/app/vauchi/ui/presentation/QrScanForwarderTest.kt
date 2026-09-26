// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Core re-issues the scan binding id on every surface revision
 * (`surface.4.binding.0`, then `surface.6.binding.0`, …) and rejects events
 * for a binding that is no longer active. The camera analyzer is built once
 * per bind, so the callback it holds must reach the binding of the latest
 * render — on the Hover rig every decode after the first revision was
 * rejected (vauchi/private#9, run E3).
 */
@RunWith(RobolectricTestRunner::class)
class QrScanForwarderTest {
    @get:Rule
    val composeRule = createComposeRule()

    // @scenario: generic_presentation_protocol.feature :: User interaction returns as an opaque event
    @Test
    fun `a forwarder captured at bind time reports to the binding of the latest render`() {
        val delivered = mutableListOf<String>()
        var binding by mutableStateOf("surface.4.binding.0")
        var capturedAtBind: ((String) -> Unit)? = null
        composeRule.setContent {
            val renderedBinding = binding
            val forwarder = rememberScanForwarder { code -> delivered += "$renderedBinding:$code" }
            if (capturedAtBind == null) capturedAtBind = forwarder
        }

        binding = "surface.6.binding.0"
        composeRule.waitForIdle()
        capturedAtBind!!("DATA")

        assertEquals(listOf("surface.6.binding.0:DATA"), delivered)
    }
}
