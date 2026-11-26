// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.files

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** A private proxy owns a native request code so cancellation can dismiss its child. */
class DocumentPickerActivity : Activity() {
    private var request: NativeFilePick? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pending = intent.extras?.getBinder(REQUEST) as? NativeFilePick
        if (pending == null || !pending.attach(this)) {
            finish()
            return
        }
        request = pending
        if (savedInstanceState != null) return
        val types = pending.command.acceptedMimeTypes.ifEmpty { listOf("*/*") }
        try {
            // The explicit request code is required by Activity.finishActivity on cancellation.
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(types.singleOrNull() ?: "*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                    .putExtra(Intent.EXTRA_TITLE, pending.command.title),
                DOCUMENT,
            )
        } catch (_: Exception) {
            pending.finish(
                app.vauchi.contract.FilePickOutcome
                    .Failed(app.vauchi.contract.FilePickFailure.ReadFailed),
            )
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DOCUMENT) request?.selected(if (resultCode == RESULT_OK) data?.data else null)
    }

    internal fun dismissChild() {
        finishActivity(DOCUMENT)
        finish()
    }

    override fun onDestroy() {
        request?.detached(this)
        request = null
        super.onDestroy()
    }

    internal companion object {
        const val REQUEST = "app.vauchi.display.file_pick"
        private const val DOCUMENT = 4701
    }
}
