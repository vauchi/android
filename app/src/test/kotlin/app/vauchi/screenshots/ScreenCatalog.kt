// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.screenshots

import app.vauchi.ui.presentation.PaneLayout
import app.vauchi.ui.presentation.PresentationProfile
import app.vauchi.ui.presentation.PresentationProtocol
import app.vauchi.ui.presentation.PresentationReducer
import app.vauchi.ui.presentation.PresentationState
import app.vauchi.ui.presentation.WindowClass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One app screen from Core's catalogue, reduced to the state the renderer composes from. */
data class ScreenCatalogEntry(
    val codeId: String,
    val title: String,
    val locale: String,
    val state: PresentationState,
) {
    val activeSurfaceId: String
        get() = checkNotNull(state.activeSurfaceId) { "$codeId reduced to no surface" }

    /**
     * A catalogue batch may omit `SetPresentationProfile` — Core sends the
     * profile once per session, not per screen — so the phone profile the
     * fixture is rendered at stands in.
     */
    val profile: PresentationProfile
        get() =
            state.profile
                ?: PresentationProfile(
                    windowClass = WindowClass.Compact,
                    paneLayout = PaneLayout.Single,
                    primarySurface = activeSurfaceId,
                    detailSurface = null,
                    activeSurface = activeSurfaceId,
                )
}

/**
 * Decodes `screen_catalog_v1.json`: `{"schema_version":1,"screens":[{code_id,
 * title, locale, commands:[<Command>...]}]}` where every command is in the
 * exact shape Core serialises for the app, so the production parser and
 * reducer are what turn it into state.
 */
object ScreenCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String): List<ScreenCatalogEntry> {
        val root = json.parseToJsonElement(text).jsonObject
        return root.getValue("screens").jsonArray.map { entry(it.jsonObject) }
    }

    private fun entry(value: JsonObject): ScreenCatalogEntry {
        val envelope = JsonObject(mapOf("commands" to value.getValue("commands")))
        val commands = PresentationProtocol.decodeEnvelope(envelope.toString()).commands
        return ScreenCatalogEntry(
            codeId = value.getValue("code_id").jsonPrimitive.content,
            title = value.getValue("title").jsonPrimitive.content,
            locale = value["locale"]?.jsonPrimitive?.content ?: "en",
            state = PresentationReducer.apply(PresentationState(), commands).state,
        )
    }
}
