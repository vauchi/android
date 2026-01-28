// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.data

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/**
 * Context wrapper that redirects filesDir to a custom directory for testing.
 * This allows tests to use isolated data directories without interfering with each other.
 */
class TestContextWrapper(
    base: Context,
    private val testFilesDir: File
) : ContextWrapper(base) {

    override fun getFilesDir(): File = testFilesDir
}
