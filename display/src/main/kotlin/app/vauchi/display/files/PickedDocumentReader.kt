// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalUnsignedTypes::class)

package app.vauchi.display.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import app.vauchi.contract.FilePickFailure
import app.vauchi.contract.FilePickOutcome
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Runs on the picker host's bounded IO worker, never its Activity callback. */
class PickedDocumentReader(
    private val resolver: ContentResolver,
) {
    fun read(
        uri: Uri,
        maxBytes: ULong,
        isCancelled: () -> Boolean = { false },
    ): FilePickOutcome {
        if (isCancelled()) return FilePickOutcome.Cancelled
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return failed(FilePickFailure.UnsupportedFile)
        return try {
            val name = displayName(uri)
            if (name.length > 1024 || name.toByteArray(Charsets.UTF_8).size > 1024 || '\u0000' in name) {
                return failed(FilePickFailure.UnsupportedFile)
            }
            if (isCancelled()) return FilePickOutcome.Cancelled
            val stream = resolver.openInputStream(uri) ?: return failed(FilePickFailure.ReadFailed)
            stream.use { readBounded(it, name, maxBytes, isCancelled) }
        } catch (_: Exception) {
            if (isCancelled()) FilePickOutcome.Cancelled else failed(FilePickFailure.ReadFailed)
        }
    }

    private fun displayName(uri: Uri): String =
        resolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }?.takeIf { it.isNotEmpty() } ?: "file"

    private fun readBounded(
        input: InputStream,
        name: String,
        maxBytes: ULong,
        isCancelled: () -> Boolean,
    ): FilePickOutcome {
        // SDK event_json.rs also caps selected bytes at 32 MiB, regardless of command limit.
        val limit = minOf(maxBytes, 32uL * 1024uL * 1024uL).toInt()
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val chunk = ByteArray(8192)
        while (true) {
            if (isCancelled()) return FilePickOutcome.Cancelled
            val count = input.read(chunk, 0, minOf(chunk.size, limit - output.size() + 1))
            if (isCancelled()) return FilePickOutcome.Cancelled
            if (count < 0) break
            if (count == 0) {
                val byte = input.read()
                if (isCancelled()) return FilePickOutcome.Cancelled
                if (byte < 0) break
                if (output.size() == limit) return failed(FilePickFailure.TooLarge)
                output.write(byte)
            } else {
                if (count > limit - output.size()) return failed(FilePickFailure.TooLarge)
                output.write(chunk, 0, count)
            }
        }
        // The unsigned view avoids allocating one boxed object per imported byte.
        return FilePickOutcome.Selected(output.toByteArray().asUByteArray().asList(), name)
    }

    private fun failed(code: FilePickFailure) = FilePickOutcome.Failed(code)
}
