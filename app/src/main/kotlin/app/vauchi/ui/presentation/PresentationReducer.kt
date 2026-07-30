// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.presentation

object PresentationReducer {
    fun apply(
        current: PresentationState,
        commands: List<PresentationCommand>,
    ): ApplyPresentationResult {
        val surfaces = current.surfaces.toMutableMap()
        val bars = current.bars.toMutableMap()
        var profile = current.profile
        var overlay = current.overlay
        val effects = mutableListOf<PresentationCommand.Effect>()

        for (command in commands) {
            when (command) {
                is PresentationCommand.ReplaceSurface -> {
                    val surface = command.surface
                    val previous = surfaces[surface.surfaceId]
                    if (previous != null && surface.revision <= previous.revision) {
                        throw PresentationProtocolException(
                            "stale surface revision for ${surface.surfaceId}",
                        )
                    }
                    surfaces[surface.surfaceId] = surface
                    bars.remove(surface.surfaceId)
                    if (overlay?.surfaceId == surface.surfaceId) {
                        overlay = null
                    }
                }

                is PresentationCommand.SetContextBar -> {
                    val surface = surfaces[command.surfaceId]
                    if (surface?.revision != command.revision) {
                        throw PresentationProtocolException(
                            "context bar revision does not match ${command.surfaceId}",
                        )
                    }
                    bars[command.surfaceId] =
                        RevisionedBar(command.revision, command.bar)
                }

                is PresentationCommand.PresentOverlay -> {
                    val surface = surfaces[command.surfaceId]
                    if (surface?.revision != command.revision) {
                        throw PresentationProtocolException(
                            "overlay revision does not match ${command.surfaceId}",
                        )
                    }
                    overlay =
                        RevisionedOverlay(
                            command.surfaceId,
                            command.revision,
                            command.overlay,
                        )
                }

                is PresentationCommand.SetProfile -> {
                    profile = command.profile
                }

                is PresentationCommand.Effect -> {
                    effects += command
                }
            }
        }

        profile?.let { value ->
            val referenced =
                listOfNotNull(
                    value.primarySurface,
                    value.activeSurface,
                    value.detailSurface,
                )
            val missing = referenced.firstOrNull { it !in surfaces }
            if (missing != null) {
                throw PresentationProtocolException(
                    "presentation profile references unknown surface $missing",
                )
            }
        }

        return ApplyPresentationResult(
            state =
                PresentationState(
                    surfaces = surfaces,
                    bars = bars,
                    profile = profile,
                    overlay = overlay,
                ),
            effects = effects,
        )
    }
}
