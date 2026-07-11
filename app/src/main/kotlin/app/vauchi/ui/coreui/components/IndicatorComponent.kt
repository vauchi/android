// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.A11y
import app.vauchi.ui.coreui.IndicatorKind
import app.vauchi.ui.coreui.UserAction
import app.vauchi.ui.theme.LocalStatusColors

/**
 * Renders a core [Component.Indicator][app.vauchi.ui.coreui.Component.Indicator]
 * as a compact chrome chip — kind-driven Material icon + label + semantic
 * color. Distinct from [StatusIndicatorComponent] (body-positioned).
 *
 * Tappable when [actionId] is non-null; otherwise display-only.
 *
 * Per shell-purity investigation 2026-05-28 (core 0.51.21 / core!990).
 */
@Composable
fun IndicatorComponent(
    label: String,
    kind: IndicatorKind,
    actionId: String?,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
    a11y: A11y? = null,
) {
    val (tintColor, backgroundColor) = colorsForKind(kind)

    val baseModifier =
        modifier
            .semantics {
                contentDescription = a11y?.label ?: label
                a11y?.hint?.let { stateDescription = it }
                if (actionId != null) {
                    role = Role.Button
                }
            }

    val rowModifier =
        if (actionId != null) {
            baseModifier.clickable {
                onAction(UserAction.ActionPressed(actionId = actionId))
            }
        } else {
            baseModifier
        }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        modifier = rowModifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            KindIcon(kind = kind, tint = tintColor)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = tintColor,
            )
        }
    }
}

@Composable
private fun KindIcon(
    kind: IndicatorKind,
    tint: Color,
) {
    when (kind) {
        IndicatorKind.Active -> {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        }

        IndicatorKind.Error -> {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        }

        IndicatorKind.Neutral -> {
            Icon(
                imageVector = Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        }

        IndicatorKind.Busy -> {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = tint,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * Returns (foreground, background) for the given [kind]. Active /
 * Error map to semantic Material colors with a low-alpha background;
 * Neutral / Busy fall through to the surface variant palette.
 */
@Composable
private fun colorsForKind(kind: IndicatorKind): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (kind) {
        IndicatorKind.Active -> {
            val active = LocalStatusColors.current.success
            active to active.copy(alpha = 0.12f)
        }

        IndicatorKind.Error -> {
            scheme.error to scheme.error.copy(alpha = 0.12f)
        }

        IndicatorKind.Neutral -> {
            scheme.onSurfaceVariant to scheme.surfaceVariant
        }

        IndicatorKind.Busy -> {
            scheme.primary to scheme.surfaceVariant
        }
    }
}
