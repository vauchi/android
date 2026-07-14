// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.A11y
import app.vauchi.ui.coreui.UserAction

/**
 * Renders a core ImageCircle component.
 *
 * Shows either an image (decoded from byte array) or a fallback initials circle.
 * Applies brightness adjustment via ColorMatrix.
 * If editable, shows a camera overlay icon and emits ActionPressed with the
 * core-supplied [editActionId] on click.
 */
@Composable
fun ImageCircleComponent(
    id: String,
    imageData: List<Int>?,
    initials: String,
    bgColor: List<Int>?,
    brightness: Float,
    editable: Boolean,
    editActionId: String? = null,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
    a11y: A11y? = null,
) {
    val bgColorValue =
        bgColor?.let { colorFromIntList(it) }
            ?: MaterialTheme.colorScheme.primary

    // Brightness ColorMatrix: offset RGB channels
    val brightnessMatrix =
        remember(brightness) {
            val offset = brightness * 255f
            ColorMatrix(
                floatArrayOf(
                    1f,
                    0f,
                    0f,
                    0f,
                    offset,
                    0f,
                    1f,
                    0f,
                    0f,
                    offset,
                    0f,
                    0f,
                    1f,
                    0f,
                    offset,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                ),
            )
        }

    Box(
        modifier =
            modifier
                .size(120.dp)
                .clip(CircleShape)
                .then(
                    if (editable && editActionId != null) {
                        Modifier.clickable {
                            onAction(UserAction.ActionPressed(actionId = editActionId))
                        }
                    } else {
                        Modifier
                    },
                ).semantics {
                    contentDescription = a11y?.label ?: initials
                },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap =
            remember(imageData) {
                imageData?.let { data ->
                    val bytes = ByteArray(data.size) { data[it].toByte() }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = a11y?.label ?: initials,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(brightnessMatrix),
            )
        } else {
            // Initials fallback
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(bgColorValue, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                )
            }
        }

        // Editable camera overlay
        if (editable) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Color.Black.copy(alpha = 0.3f),
                            CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

/**
 * Converts a list of [r, g, b] or [r, g, b, a] ints (0-255) to a Compose [Color].
 */
private fun colorFromIntList(values: List<Int>): Color {
    val r = values.getOrElse(0) { 0 }
    val g = values.getOrElse(1) { 0 }
    val b = values.getOrElse(2) { 0 }
    val a = values.getOrElse(3) { 255 }
    return Color(r, g, b, a)
}
