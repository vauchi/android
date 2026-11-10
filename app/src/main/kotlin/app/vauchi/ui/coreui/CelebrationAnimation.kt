// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

/** One beat: long enough to register, short enough never to outlive the moment. */
const val CELEBRATION_BEAT_MILLIS = 600L

/**
 * Whether the one-beat flash plays for the animation intent Core names in
 * `Command::Celebrate`. Only `cards_meet` is drawn, and only while the OS
 * allows motion; the haptic and the milestone toast carry the moment
 * otherwise.
 */
fun celebrationAnimationPlays(
    animation: String,
    reducedMotion: Boolean,
): Boolean = animation == "cards_meet" && !reducedMotion
