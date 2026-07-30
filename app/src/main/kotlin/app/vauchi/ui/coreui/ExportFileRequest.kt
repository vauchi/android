// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

data class ExportFileRequest(
    val suggestedName: String,
    val mimeType: String,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ExportFileRequest &&
            suggestedName == other.suggestedName &&
            mimeType == other.mimeType &&
            data.contentEquals(other.data)

    override fun hashCode(): Int =
        31 * (31 * suggestedName.hashCode() + mimeType.hashCode()) +
            data.contentHashCode()
}
