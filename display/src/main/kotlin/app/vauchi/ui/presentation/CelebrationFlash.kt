// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

const val CELEBRATION_FLASH_TAG = "celebration_flash"

/**
 * The exchange ceremony's one beat: a check mark that scales in over the
 * screen and fades. Decorative only, so it carries no accessibility copy;
 * the haptic and Core's milestone toast are what a screen reader gets.
 */
@Composable
fun CelebrationFlash(
    visible: Boolean,
    tokens: PresentationTokens,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = scaleIn(initialScale = 0.6f) + fadeIn(),
            exit = fadeOut(),
        ) {
            val disc = MaterialTheme.colorScheme.primary
            val mark = MaterialTheme.colorScheme.onPrimary
            Canvas(
                modifier =
                    Modifier
                        .size((tokens.minimumTargetSize * 2).dp)
                        .testTag(CELEBRATION_FLASH_TAG),
            ) {
                drawCircle(color = disc)
                val stroke = size.minDimension / 12f
                val check =
                    Path().apply {
                        moveTo(size.width * 0.28f, size.height * 0.52f)
                        lineTo(size.width * 0.44f, size.height * 0.68f)
                        lineTo(size.width * 0.72f, size.height * 0.36f)
                    }
                drawPath(check, color = mark, style = Stroke(width = stroke, cap = StrokeCap.Round))
                drawCircle(color = mark, radius = stroke / 2f, center = Offset(size.width * 0.28f, size.height * 0.52f))
            }
        }
    }
}
