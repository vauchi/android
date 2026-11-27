// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.exports

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.FileNotFoundException

class ExportFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? = uri.getQueryParameter(MIME)?.takeIf(ExportMetadata::isMime) ?: super.getType(uri)

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Export is read only")
        return try {
            super.openFile(uri, mode) ?: throw FileNotFoundException("Export unavailable")
        } catch (_: Exception) {
            throw FileNotFoundException("Export unavailable")
        }
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Export is read only")

    internal companion object {
        const val MIME = "vauchi_mime"
    }
}
