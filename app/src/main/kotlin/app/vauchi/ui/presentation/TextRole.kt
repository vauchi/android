// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

/**
 * Shell-side mirror of Core's `PresentationTextStyle`
 * (`core/vauchi-core/src/platform/presentation/surface/nodes.rs`).
 *
 * Core sends a semantic role rather than a size so each shell can resolve
 * it to a native text style and inherit that platform's font scaling and
 * accessibility behaviour (ADR-021, ADR-066).
 */
enum class TextRole {
    Heading,
    Body,
    Caption,
    Monospace,
    Muted,
}

/** Native typography step a role maps onto. */
enum class TextTypography {
    TitleLarge,
    BodyLarge,
    BodySmall,
}

/**
 * What a role asks the shell to apply, expressed without Compose types so
 * the mapping stays unit-testable outside a composition.
 */
data class TextRoleStyle(
    val typography: TextTypography,
    val monospaced: Boolean,
    val muted: Boolean,
)

/**
 * Resolve a role to its presentation.
 *
 * Deliberately has no `else` branch: adding a variant to Core must break
 * this build rather than degrade silently, which is how `Monospace` and
 * `Muted` were previously rendered as plain body text.
 */
fun textRoleStyle(role: TextRole): TextRoleStyle =
    when (role) {
        TextRole.Heading -> TextRoleStyle(TextTypography.TitleLarge, monospaced = false, muted = false)
        TextRole.Body -> TextRoleStyle(TextTypography.BodyLarge, monospaced = false, muted = false)
        TextRole.Caption -> TextRoleStyle(TextTypography.BodySmall, monospaced = false, muted = false)
        TextRole.Monospace -> TextRoleStyle(TextTypography.BodyLarge, monospaced = true, muted = false)
        TextRole.Muted -> TextRoleStyle(TextTypography.BodyLarge, monospaced = false, muted = true)
    }

/**
 * Decode the wire value Core serialises for `PresentationTextStyle`.
 *
 * Rejects unknown values instead of defaulting: a value this shell does
 * not understand means Core and shell disagree about the protocol, and
 * failing closed surfaces that at the binding bump rather than as
 * silently wrong text (ADR-066 unknown-input rule).
 */
fun parseTextRole(wire: String): TextRole =
    when (wire) {
        "heading" -> TextRole.Heading
        "body" -> TextRole.Body
        "caption" -> TextRole.Caption
        "monospace" -> TextRole.Monospace
        "muted" -> TextRole.Muted
        else -> throw PresentationProtocolException("unknown text style '$wire'")
    }
