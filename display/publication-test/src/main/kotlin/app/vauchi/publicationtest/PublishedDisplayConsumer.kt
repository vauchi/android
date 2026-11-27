// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.publicationtest

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.vauchi.contract.Command
import app.vauchi.display.AndroidDisplayExecutor
import app.vauchi.display.DisplayContent
import app.vauchi.display.DisplaySession
import app.vauchi.display.DisplaySnapshot
import app.vauchi.ui.presentation.PresentationMedia
import kotlinx.coroutines.flow.StateFlow

fun applyPublishedCommands(
    session: DisplaySession,
    commands: List<Command>,
): Set<Int> = session.apply(commands)

fun publishedState(session: DisplaySession): StateFlow<DisplaySnapshot> = session.state

@Composable
fun PublishedDisplayContent(
    owner: AndroidDisplayExecutor,
    media: PresentationMedia,
) {
    DisplayContent(owner, media, Modifier)
}
