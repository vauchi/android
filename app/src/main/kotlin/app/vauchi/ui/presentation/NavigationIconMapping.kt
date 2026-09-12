// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Support
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
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

/**
 * Resolves the icon token Core puts on a status row
 * (`PresentationNode::Status { icon_token }`) to a Material glyph, or
 * `null` when the token is unknown. A status row already carries a
 * title, so an unknown token is better left blank than drawn as a
 * neutral placeholder beside it. Same SF Symbol spelling and same
 * filled-only commitment as [navigationIcon]; the two tables overlap on
 * purpose (`qrcode`, `folder`, `devices`, `people`) because Core reuses
 * those names on both surfaces.
 */
fun statusIcon(token: String): ImageVector? =
    when (token) {
        "lock" -> Icons.Filled.Lock

        "warning", "exclamationmark.triangle" -> Icons.Filled.Warning

        "info" -> Icons.Filled.Info

        "people" -> Icons.Filled.People

        "person" -> Icons.Filled.Person

        "shield" -> Icons.Filled.Shield

        "checkmark.shield" -> Icons.Filled.VerifiedUser

        "exclamationmark.shield" -> Icons.Filled.GppMaybe

        "checkmark.seal" -> Icons.Filled.Verified

        "checkmark" -> Icons.Filled.Check

        "checkmark.circle", "checkmark.circle.fill" -> Icons.Filled.CheckCircle

        "xmark" -> Icons.Filled.Close

        "xmark.circle" -> Icons.Filled.Cancel

        "devices" -> Icons.Filled.Devices

        "swap", "arrow.left.arrow.right" -> Icons.Filled.SwapHoriz

        "qrcode", "qr" -> Icons.Filled.QrCode2

        "key" -> Icons.Filled.Key

        "folder" -> Icons.Filled.Folder

        "eye" -> Icons.Filled.Visibility

        "delete", "trash" -> Icons.Filled.Delete

        "link" -> Icons.Filled.Link

        "lifebuoy" -> Icons.Filled.Support

        "sparkles" -> Icons.Filled.AutoAwesome

        "camera" -> Icons.Filled.CameraAlt

        "camera.slash" -> Icons.Filled.NoPhotography

        "photo" -> Icons.Filled.Photo

        "wifi" -> Icons.Filled.Wifi

        "dot.radiowaves.left.and.right" -> Icons.Filled.Sensors

        "cloud" -> Icons.Filled.Cloud

        "drive" -> Icons.Filled.Storage

        "clock" -> Icons.Filled.Schedule

        "clock.arrow.circlepath" -> Icons.Filled.History

        "textformat" -> Icons.Filled.TextFormat

        "sun.min" -> Icons.Filled.BrightnessLow

        "sun.max" -> Icons.Filled.BrightnessHigh

        "id_card" -> Icons.Filled.Badge

        "heart" -> Icons.Filled.Favorite

        "more" -> Icons.Filled.MoreHoriz

        "github" -> Icons.Filled.Code

        "liberapay" -> Icons.Filled.VolunteerActivism

        else -> null
    }
