// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui

import android.content.Intent
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.vauchi.MainActivity

/**
 * Launches [MainActivity] with the `reset_for_testing` seed and returns only
 * once the app has actually reached [UiState.Ready].
 *
 * Waits on the state the seed commits, not on rendered text.
 * `seedTestIdentityIfNeeded()` delegates to `createIdentity`, which runs on
 * `viewModelScope`, so the first composition resolves before the identity
 * exists. Polling the semantics tree for the seeded display name watches that
 * transition several hops downstream and cannot tell "still seeding" apart
 * from `AuthRequired`, `Error` or a genuine render regression — so every
 * failure arrived as the same opaque timeout, and on a device still busy from
 * an install it timed out routinely
 * (`2026-08-12-instrumented-before-gate-fails-on-a-busy-device`).
 *
 * The budget matches what the callers already spent (6 × 5 s of recreate-and-
 * retry); what changes is the observable, not the number. Recreating the
 * activity is gone with it: the seeding `LaunchedEffect` fires from
 * `Onboarding` as well as `Ready`, so a single launch does reach `Ready` on a
 * fresh install — the old loop was compensating for watching the wrong thing.
 *
 * Assertions still go through Compose semantics. Only the arrange phase reads
 * state, so a test can still fail on what the shell renders.
 */
internal fun launchSeededApp(
    composeTestRule: ComposeTestRule,
    timeoutMs: Long = SEED_TIMEOUT_MS,
): ActivityScenario<MainActivity> {
    val intent =
        Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java,
        ).apply {
            putExtra("reset_for_testing", true)
        }

    val scenario = ActivityScenario.launch<MainActivity>(intent)

    var lastState: UiState? = null
    try {
        composeTestRule.waitUntil(timeoutMs) {
            scenario.onActivity { activity ->
                lastState = ViewModelProvider(activity)[MainViewModel::class.java].uiState.value
            }
            lastState is UiState.Ready
        }
    } catch (timeout: ComposeTimeoutException) {
        scenario.close()
        // Name the state, never its payload: `Ready` carries the public id and
        // card contents, which must not reach CI output (logging-rules.md).
        throw AssertionError(
            "App never reached Ready within $timeoutMs ms — last state was " +
                (lastState?.let { it::class.simpleName } ?: "none observed"),
            timeout,
        )
    }
    return scenario
}

private const val SEED_TIMEOUT_MS = 30_000L
