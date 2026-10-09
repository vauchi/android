// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import app.vauchi.BuildConfig

/**
 * Whether Keystore keys need the person's authentication: always in release
 * builds; in debug builds only with `-PrequireUserAuth`, so the device rig
 * can drive Core's locked start (vauchi/private#580).
 */
object UserAuthPolicy {
    const val VALIDITY_SECONDS = 300

    val required: Boolean
        get() = !BuildConfig.DEBUG || BuildConfig.REQUIRE_USER_AUTH
}
