// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.files

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import app.vauchi.contract.Command
import app.vauchi.contract.FilePickFailure
import app.vauchi.contract.FilePickOutcome
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class ActivityFilePickerHost internal constructor(
    private val activity: () -> ComponentActivity?,
    private val io: ExecutorService,
) : AutoCloseable {
    constructor(activity: () -> ComponentActivity?) : this(
        activity,
        ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, SynchronousQueue()) { task ->
            Thread(task, "vauchi-document-read").apply { isDaemon = true }
        },
    )

    private var active: NativeFilePick? = null
    private var closed = false

    fun pick(
        command: Command.PickFile,
        onFinished: (FilePickOutcome) -> Unit,
    ): AutoCloseable {
        requireMainThread()
        val owner = activity()
        if (closed || active != null || owner == null || owner.isFinishing || owner.isDestroyed ||
            !owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        ) {
            onFinished(FilePickOutcome.Failed(FilePickFailure.ReadFailed))
            return AutoCloseable {}
        }
        val snapshot =
            command.copy(
                acceptedMimeTypes = command.acceptedMimeTypes.toList(),
                acceptedExtensions = command.acceptedExtensions.toList(),
            )
        val request =
            NativeFilePick(owner, snapshot, io) { result ->
                active = null
                onFinished(result)
            }
        active = request
        try {
            owner.lifecycle.addObserver(request)
            owner.startActivity(
                Intent(owner, DocumentPickerActivity::class.java).putExtras(
                    Bundle().apply { putBinder(DocumentPickerActivity.REQUEST, request) },
                ),
            )
        } catch (_: Exception) {
            request.finish(FilePickOutcome.Failed(FilePickFailure.ReadFailed))
        }
        return AutoCloseable {
            requireMainThread()
            request.close()
            if (active === request) active = null
        }
    }

    override fun close() {
        requireMainThread()
        if (closed) return
        closed = true
        try {
            active?.close()
        } finally {
            active = null
            io.shutdownNow()
        }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "File picker requires main thread" }
    }
}
