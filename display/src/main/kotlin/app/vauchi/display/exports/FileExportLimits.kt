// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.exports

import app.vauchi.contract.ExportFileSpec

data class FileExportLimits(
    val maxFileBytes: Int = 128 * 1024 * 1024,
    val maxCacheBytes: Long = 256L * 1024 * 1024,
    val maxFiles: Int = 64,
    val retentionMillis: Long = 24L * 60 * 60 * 1000,
) {
    init {
        require(maxFileBytes >= 0 && maxCacheBytes > 0 && maxCacheBytes >= maxFileBytes) { "Invalid export byte limits" }
        require(maxFiles in 1..1024 && retentionMillis > 0) { "Invalid export cache limits" }
    }
}

internal data class ExportMetadata(
    val name: String,
    val mime: String,
) {
    companion object {
        private val mimePattern = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+/[A-Za-z0-9!#$%&'*+.^_`|~-]+")

        fun isMime(value: String): Boolean = value.length <= 255 && mimePattern.matches(value)

        fun from(
            file: ExportFileSpec,
            limits: FileExportLimits,
        ): ExportMetadata {
            if (file.data.size < 0 || file.data.size > limits.maxFileBytes) throw FileExportFailure("file_export_too_large")
            if (!isMime(file.mimeType) || file.suggestedName.length > 1024 || file.suggestedName.toByteArray(Charsets.UTF_8).size > 1024) {
                throw FileExportFailure("file_export_invalid_request")
            }
            val name =
                file.suggestedName
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .filterNot { Character.isISOControl(it) }
            return ExportMetadata(name.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "vauchi-export", file.mimeType)
        }
    }
}

internal class FileExportFailure(
    val code: String,
) : Exception()
