// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.permissions

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivity

internal fun Activity.answerPermissions(request: ShadowActivity.PermissionsRequest) {
    // AOSP dispatch clears native prompt ownership before delivering the public callback.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/app/Activity.java
    shadowOf(this).internalCallDispatchActivityResult(
        "@android:requestPermissions:",
        request.requestCode,
        Activity.RESULT_OK,
        Intent()
            .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES", request.requestedPermissions)
            .putExtra(
                "android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS",
                IntArray(request.requestedPermissions.size) {
                    PackageManager.PERMISSION_GRANTED
                },
            ),
    )
}
