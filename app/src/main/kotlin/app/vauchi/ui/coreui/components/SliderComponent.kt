// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vauchi.ui.coreui.A11y
import app.vauchi.ui.coreui.UserAction
import kotlin.math.roundToInt

/**
 * Renders a core Slider component as a Material3 Slider.
 *
 * On value change, emits a [UserAction.SliderChanged] with the value scaled
 * to milli (value * 1000) as an integer, matching core's expected format.
 */
@Composable
fun SliderComponent(
    componentId: String,
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    minIcon: String?,
    maxIcon: String?,
    onAction: (UserAction) -> Unit,
    modifier: Modifier = Modifier,
    a11y: A11y? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = a11y?.label ?: label
                },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            minIcon?.let {
                Icon(
                    imageVector = resolveIcon(it),
                    contentDescription = "Minimum",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Calculate steps count for Material3 Slider
            val steps =
                if (step > 0f && max > min) {
                    ((max - min) / step).roundToInt() - 1
                } else {
                    0
                }

            Slider(
                value = value,
                onValueChange = { newValue ->
                    val valueMilli = (newValue * 1000f).roundToInt()
                    onAction(
                        UserAction.SliderChanged(
                            componentId = componentId,
                            valueMilli = valueMilli,
                        ),
                    )
                },
                valueRange = min..max,
                steps = steps.coerceAtLeast(0),
                modifier = Modifier.weight(1f),
            )

            maxIcon?.let {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = resolveIcon(it),
                    contentDescription = "Maximum",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
