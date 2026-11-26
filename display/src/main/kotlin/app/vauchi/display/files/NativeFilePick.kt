// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.files

import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.vauchi.contract.Command
import app.vauchi.contract.FilePickFailure
import app.vauchi.contract.FilePickOutcome
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

internal class NativeFilePick(
    private var owner: ComponentActivity?,
    val command: Command.PickFile,
    private val io: ExecutorService,
    private var onFinished: ((FilePickOutcome) -> Unit)?,
) : Binder(),
    AutoCloseable,
    LifecycleEventObserver {
    private val main = Handler(Looper.getMainLooper())
    private val canceled = AtomicBoolean(false)
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
                    val result = PickedDocumentReader(resolver).read(uri, command.maxBytes, canceled::get)
                    main.post { finish(result) }
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
        val activity = picker
        picker = null
        activity?.dismissChild()
    }
}
