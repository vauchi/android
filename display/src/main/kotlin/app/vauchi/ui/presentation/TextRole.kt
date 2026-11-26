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
 * Deliberately has no `else` branch, so adding a variant to [TextRole]
 * fails compilation here rather than degrading silently — which is how
 * `Monospace` and `Muted` came to render as plain body text.
 *
 * That guard is weaker than it looks, and the gap is worth naming:
 * [TextRole] is a hand-maintained mirror of Core's `PresentationTextStyle`
 * (`core/vauchi-core/src/platform/presentation/surface/nodes.rs`). A
 * variant added in Rust does **not** break this build. It breaks only
 * once someone adds the Kotlin variant, and until then
 * [parseTextRole] returns null and the text renders as body. Closing
 * that properly needs the enum generated from the Rust source rather
 * than mirrored by hand.
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
 * Decode the wire value Core serialises for `PresentationTextStyle`,
 * or null if this shell does not know it.
 *
 * Returning null rather than throwing is deliberate. An earlier version
 * threw, citing ADR-066's unknown-input rule — but that rule
 * (ADR-066:105) governs **events**, shell to Core, and exists to stop a
 * misbehaving shell corrupting Core state. It does not apply in the
 * command direction. Applying it here inverted its intent: a newer Core
 * could not drive an older shell, which is the opposite of what
 * `#[non_exhaustive]` on the Rust enum exists to allow.
 *
 * Failing closed is right for actions and state transitions. For a font
 * distinction it is disproportionate — it would abandon an entire
 * surface over cosmetics. The caller falls back to [TextRole.Body] and
 * logs, so the text still reaches the user and the skew stays visible.
 */
fun parseTextRole(wire: String): TextRole? =
    when (wire) {
        "heading" -> TextRole.Heading
        "body" -> TextRole.Body
        "caption" -> TextRole.Caption
        "monospace" -> TextRole.Monospace
        "muted" -> TextRole.Muted
        else -> null
    }
