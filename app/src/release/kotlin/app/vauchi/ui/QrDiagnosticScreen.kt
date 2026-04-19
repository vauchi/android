// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Release-variant no-op stub for [QrDiagnosticScreen].
 *
 * The real QR diagnostic screen lives in `app/src/debug/` and is only
 * compiled into debug APKs. Release builds see this stub — the
 * `Screen.QrDiagnostic` route is gated in `MainActivity` by
 * `BuildConfig.DEBUG` so this stub is tree-shaken unreachable by R8.
 * It exists only to keep `MainActivity` compiling in both variants.
 *
 * Per principle "diagnostics should only be in test/debug build,
 * never in production" (2026-04-19-diagnostics-out-of-production-plan.md).
 */
@Composable
fun QrDiagnosticScreen(onBack: () -> Unit) {
    Text("QR Diagnostic is only available in debug builds.")
}
