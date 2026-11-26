// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.files

import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.vauchi.contract.Command
import app.vauchi.contract.FilePickFailure
import app.vauchi.contract.FilePickOutcome
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class NativeFilePick(
    private var owner: ComponentActivity?,
    val command: Command.PickFile,
    private val io: ExecutorService,
    private val cleanup: ExecutorService,
    private var onFinished: ((FilePickOutcome) -> Unit)?,
) : Binder(),
    AutoCloseable,
    LifecycleEventObserver {
    private val main = Handler(Looper.getMainLooper())
    private val canceled = AtomicBoolean(false)
    private val input = AtomicReference<InputStream?>(null)
    private var picker: DocumentPickerActivity? = null
    private var reading = false
    private var future: Future<*>? = null

    fun attach(activity: DocumentPickerActivity): Boolean {
        if (canceled.get() || picker != null || reading) return false
        picker = activity
        return true
    }

    fun selected(uri: Uri?) {
        if (canceled.get() || reading) return
        if (uri == null) {
            finish(FilePickOutcome.Cancelled)
            return
        }
        reading = true
        val resolver =
            owner?.applicationContext?.contentResolver ?: run {
                finish(FilePickOutcome.Failed(FilePickFailure.ReadFailed))
                return
            }
        try {
            future =
                io.submit {
                    val result =
                        try {
                            PickedDocumentReader(resolver).read(
                                uri,
                                command.maxBytes,
                                onOpened = { stream ->
                                    input.set(stream)
                                    if (canceled.get()) input.getAndSet(null)?.close()
                                },
                                isCancelled = canceled::get,
                            )
                        } finally {
                            input.set(null)
                        }
                    if (!canceled.get()) {
                        main.postAtTime({ finish(result) }, this, SystemClock.uptimeMillis())
                        // Close may race between the first check and posting a payload-owning callback.
                        if (canceled.get()) main.removeCallbacksAndMessages(this)
                    }
                }
        } catch (_: Exception) {
            finish(FilePickOutcome.Failed(FilePickFailure.ReadFailed))
        }
    }

    fun detached(activity: DocumentPickerActivity) {
        if (picker !== activity) return
        picker = null
        if (!canceled.get() && !activity.isChangingConfigurations && !reading) {
            finish(FilePickOutcome.Failed(FilePickFailure.ReadFailed))
        }
    }

    fun finish(result: FilePickOutcome) {
        if (canceled.get()) return
        val notify = onFinished
        close()
        notify?.invoke(result)
    }

    override fun onStateChanged(
        source: LifecycleOwner,
        event: Lifecycle.Event,
    ) {
        if (event == Lifecycle.Event.ON_DESTROY) finish(FilePickOutcome.Failed(FilePickFailure.ReadFailed))
    }

    override fun close() {
        if (!canceled.compareAndSet(false, true)) return
        onFinished = null
        owner?.lifecycle?.removeObserver(this)
        owner = null
        future?.cancel(true)
        future = null
        main.removeCallbacksAndMessages(this)
        val activity = picker
        picker = null
        val stream = input.getAndSet(null)
        try {
            activity?.dismissChild()
        } catch (_: Exception) {
            // A disappearing native owner must not retain the read or completion callback.
        } finally {
            if (stream != null) {
                try {
                    cleanup.execute {
                        try {
                            stream.close()
                        } catch (_: Exception) {
                            // The IO worker still owns the stream's final use/close path.
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    // Quarantine an unresponsive provider; never queue unbounded disposal work.
                }
            }
        }
    }
}
