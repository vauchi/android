// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Exchange links and QRs are `https://vauchi.app/x#…` (vauchi/private#277).
 * With the app installed they must open it directly, verified against
 * vauchi.app/.well-known/assetlinks.json, and the claim must stay on `/x`:
 * the rest of vauchi.app is the website.
 *
 * Reads the manifest file rather than `PackageManager`, like
 * [Api36BehaviourContractTest]: Robolectric does not run App Links
 * verification, and the file is what a developer edits to break this.
 */
class AppLinksContractTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test
    fun only_vauchi_app_x_is_a_verified_app_link() {
        val httpsFilters =
            intentFilters().filter { filter ->
                dataElements(filter).any { it.getAttributeNS(androidNs, "scheme") == "https" }
            }

        assertEquals("exactly one https intent filter", 1, httpsFilters.size)
        val filter = httpsFilters.single()
        assertEquals("true", filter.getAttributeNS(androidNs, "autoVerify"))
        assertEquals(
            setOf(
                "action:android.intent.action.VIEW",
                "category:android.intent.category.DEFAULT",
                "category:android.intent.category.BROWSABLE",
            ),
            children(filter, "action")
                .map { "action:" + it.getAttributeNS(androidNs, "name") }
                .plus(children(filter, "category").map { "category:" + it.getAttributeNS(androidNs, "name") })
                .toSet(),
        )
        assertEquals(
            listOf(
                mapOf("scheme" to "https"),
                mapOf("host" to "vauchi.app"),
                mapOf("path" to "/x"),
                mapOf("path" to "/x/"),
            ),
            dataElements(filter).map { data ->
                (0 until data.attributes.length)
                    .map { data.attributes.item(it) }
                    .filter { it.namespaceURI == androidNs }
                    .associate { it.localName to it.nodeValue }
            },
        )
    }

    private fun intentFilters(): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(manifest())
        val nodes = document.getElementsByTagName("intent-filter")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun dataElements(filter: Element) = children(filter, "data")

    private fun children(
        parent: Element,
        tag: String,
    ): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun manifest(): File {
        val candidates =
            listOf(
                File("src/main/AndroidManifest.xml"),
                File("app/src/main/AndroidManifest.xml"),
            )
        val found = candidates.firstOrNull { it.isFile }
        assertEquals(
            "expected the manifest at one of " + candidates.joinToString { it.absolutePath },
            true,
            found != null,
        )
        return found!!
    }
}
