// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.intents

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import java.util.concurrent.atomic.AtomicReference

/**
 * Launch on Main, serialized with display execution. The provider follows Activity
 * recreation. Close fences new launches from any thread and releases the provider.
 * The returned Activity is not cached.
 * Core owns URL policy. Strings are data, never serialized Android intents.
 */
class ActivityExternalIntentHost(
    activity: () -> ComponentActivity?,
) : AutoCloseable {
    private val provider = AtomicReference<(() -> ComponentActivity?)?>(activity)

    fun open(url: String) = launch { Intent(Intent.ACTION_VIEW, Uri.parse(url)) }

    fun share(url: String) =
        launch {
            val send =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun launch(intent: () -> Intent) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) throw ExternalIntentUnavailable()
            val source = provider.get() ?: throw ExternalIntentUnavailable()
            val owner = source() ?: throw ExternalIntentUnavailable()
            if (provider.get() == null || owner.isFinishing || owner.isDestroyed ||
                owner.lifecycle.currentState == Lifecycle.State.DESTROYED
            ) {
                throw ExternalIntentUnavailable()
            }
            owner.startActivity(intent())
        } catch (_: Exception) {
            throw ExternalIntentUnavailable()
        }
    }

    override fun close() {
        provider.set(null)
    }
}

private class ExternalIntentUnavailable : IllegalStateException("External intent hosting unavailable")
