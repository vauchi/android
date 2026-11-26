// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display

import app.vauchi.contract.Command
import app.vauchi.contract.ContractJson
import app.vauchi.contract.PaneLayout
import app.vauchi.contract.PresentationProfile
import app.vauchi.contract.WindowClass

internal fun displaySurface(
    revision: ULong = 1u,
    title: String = "Prepared title",
    nodes: String = "[]",
): Command =
    ContractJson.decodeCommand(
        """{"ReplaceSurface":{"surface":{"surface_id":"opaque-surface","revision":$revision,"title":"$title",
        "subtitle":null,"accessibility_label":"Prepared surface","layout":"scroll","nodes":$nodes,
        "tokens":{"spacing_small":4,"spacing_medium":8,"spacing_large":16,"corner_radius":8,"minimum_target_size":48,
        "colors":{"accent":"#123456","background":"#ffffff","border":"#aaaaaa","error":"#ff0000","success":"#008800",
        "surface":"#eeeeee","text":"#000000","text_muted":"#666666","warning":"#ffaa00"}}}}}""",
    )

internal fun prepareDisplay(
    owner: AndroidDisplayExecutor,
    nodes: String = "[]",
) {
    check(owner.execute(displaySurface(nodes = nodes)))
    check(
        owner.execute(
            Command.SetPresentationProfile(
                PresentationProfile("opaque-surface", null, PaneLayout.Single, "opaque-surface", WindowClass.Compact, null),
            ),
        ),
    )
    check(
        owner.execute(
            ContractJson.decodeCommand(
                """{"SetContextBar":{"surface_id":"opaque-surface","revision":1,"bar":{"back":null,"navigation":null,
                "primary":{"interaction_id":"opaque-action","label":"Prepared action","accessibility_label":"Prepared action",
                "enabled":true,"icon_token":null,"shortcut":null},"secondary":null}}}""",
            ),
        ),
    )
}
