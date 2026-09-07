// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Resolves a core navigation icon token to a native Material glyph.
 *
 * Core names each destination with a platform-neutral token spelled as
 * an SF Symbol (`tab_metadata` in
 * `core/vauchi-app/src/ui/app_engine/navigation.rs`) and ships it to
 * the shell as `ActionSpec.icon_token`. The token is a *name*, not an
 * asset: every frontend maps it to its own icon set, which is why this
 * table lives here and not in core.
 *
 * Filled variants throughout: outline strokes thin out at nav-row size
 * and for low-vision users, so the whole table commits to the filled
 * set. `NavigationIconMappingTest` enforces that, along with every
 * token resolving and no two destinations sharing a glyph.
 *
 * Total by construction — an unrecognised token yields [Icons.Filled.Apps]
 * rather than throwing or resolving to nothing, so a destination core
 * adds after this shell ships still renders. The fallback is
 * deliberately neutral: reusing another destination's glyph would put a
 * confident, wrong picture next to the label.
 */
fun navigationIcon(token: String): ImageVector =
    when (token) {
        "person.crop.rectangle" -> Icons.Filled.ContactPage

        "person.2" -> Icons.Filled.People

        "qrcode" -> Icons.Filled.QrCode2

        "folder" -> Icons.Filled.Folder

        "tag" -> Icons.AutoMirrored.Filled.Label

        "mappin.and.ellipse" -> Icons.Filled.Place

        "person.badge.plus" -> Icons.Filled.PersonAdd

        "gearshape" -> Icons.Filled.Settings

        "questionmark.circle" -> Icons.AutoMirrored.Filled.Help

        "key.horizontal" -> Icons.Filled.VpnKey

        "laptopcomputer" -> Icons.Filled.Devices

        // A drive, not a cloud: backup here is guardian-held and local,
        // and Material's `Backup` glyph is a cloud with an up-arrow.
        "externaldrive" -> Icons.Filled.Storage

        "hand.raised" -> Icons.Filled.PrivacyTip

        "bubble.left.and.bubble.right" -> Icons.Filled.Forum

        "list.bullet.rectangle" -> Icons.AutoMirrored.Filled.ListAlt

        "house" -> Icons.Filled.Home

        else -> Icons.Filled.Apps
    }
