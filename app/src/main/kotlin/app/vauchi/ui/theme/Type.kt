// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.vauchi.R

/**
 * Bricolage Grotesque (variable: opsz, wdth, wght) — the brand's display
 * face, used for display/headline/title styles. Ramp per `tokens.json`
 * `font_weight` (400-800); see `themes/fonts/README.md`.
 */
@OptIn(ExperimentalTextApi::class)
val BricolageGrotesque =
    FontFamily(
        Font(
            R.font.bricolage_grotesque,
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        Font(
            R.font.bricolage_grotesque,
            weight = FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500)),
        ),
        Font(
            R.font.bricolage_grotesque,
            weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600)),
        ),
        Font(
            R.font.bricolage_grotesque,
            weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700)),
        ),
        Font(
            R.font.bricolage_grotesque,
            weight = FontWeight.ExtraBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(800)),
        ),
    )

/**
 * Hanken Grotesk (variable: wght) — the brand's body face, used for
 * body/label styles. Ramp per `tokens.json` `font_weight` (400-600).
 */
@OptIn(ExperimentalTextApi::class)
val HankenGrotesk =
    FontFamily(
        Font(
            R.font.hanken_grotesk,
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        Font(
            R.font.hanken_grotesk,
            weight = FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500)),
        ),
        Font(
            R.font.hanken_grotesk,
            weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600)),
        ),
        Font(
            R.font.hanken_grotesk_italic,
            weight = FontWeight.Normal,
            style = FontStyle.Italic,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        Font(
            R.font.hanken_grotesk_italic,
            weight = FontWeight.Medium,
            style = FontStyle.Italic,
            variationSettings = FontVariation.Settings(FontVariation.weight(500)),
        ),
        Font(
            R.font.hanken_grotesk_italic,
            weight = FontWeight.SemiBold,
            style = FontStyle.Italic,
            variationSettings = FontVariation.Settings(FontVariation.weight(600)),
        ),
    )

/**
 * JetBrains Mono (variable: wght) — the brand's monospace face, used
 * wherever [app.vauchi.ui.presentation.TextRole.Monospace] resolves.
 */
@OptIn(ExperimentalTextApi::class)
val JetBrainsMono =
    FontFamily(
        Font(
            R.font.jetbrains_mono,
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        Font(
            R.font.jetbrains_mono_italic,
            weight = FontWeight.Normal,
            style = FontStyle.Italic,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
    )

/**
 * The family [app.vauchi.ui.presentation.PresentationNodeRenderer] swaps
 * in for [app.vauchi.ui.presentation.TextRoleStyle.monospaced], in place
 * of the platform default `FontFamily.Monospace`.
 */
val MonospaceFontFamily = JetBrainsMono

private val base = Typography()

val Typography =
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = BricolageGrotesque, fontWeight = FontWeight.ExtraBold),
        displayMedium = base.displayMedium.copy(fontFamily = BricolageGrotesque, fontWeight = FontWeight.ExtraBold),
        displaySmall = base.displaySmall.copy(fontFamily = BricolageGrotesque, fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontFamily = BricolageGrotesque, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = BricolageGrotesque, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = BricolageGrotesque, fontWeight = FontWeight.SemiBold),
        titleLarge =
            TextStyle(
                fontFamily = BricolageGrotesque,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.sp,
            ),
        titleMedium = base.titleMedium.copy(fontFamily = BricolageGrotesque, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = BricolageGrotesque, fontWeight = FontWeight.Medium),
        bodyLarge =
            TextStyle(
                fontFamily = HankenGrotesk,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp,
            ),
        bodyMedium = base.bodyMedium.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.Normal),
        bodySmall = base.bodySmall.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.Normal),
        labelLarge = base.labelLarge.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium),
        labelSmall =
            TextStyle(
                fontFamily = HankenGrotesk,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
    )
