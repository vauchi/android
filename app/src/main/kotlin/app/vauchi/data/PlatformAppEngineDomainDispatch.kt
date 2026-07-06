// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import uniffi.vauchi_platform.DomainCommand
import uniffi.vauchi_platform.DomainCommandResult
import uniffi.vauchi_platform.MobileAuthMode
import uniffi.vauchi_platform.MobileContactCard
import uniffi.vauchi_platform.MobileContentCycleOutcome
import uniffi.vauchi_platform.MobileDemoContact
import uniffi.vauchi_platform.MobileException
import uniffi.vauchi_platform.MobileSyncResult
import uniffi.vauchi_platform.PlatformAppEngine

// Typed wrappers around `PlatformAppEngine.dispatchDomainCommand` for
// the collapse-vauchi-platform migration. After retiring the test-only
// domain-CRUD repository wrappers this file only contains the small set
// of dispatch helpers still called by the Android repository shell
// (identity/bootstrap, auth, sync, content-update cycle, and demo-contact
// init). All domain CRUD has moved into core-driven screens.

private fun unexpectedResult(name: String): Nothing = throw MobileException.Other(detail = "$name: unexpected result variant")

fun PlatformAppEngine.createIdentity(displayName: String) {
    dispatchDomainCommand(DomainCommand.CreateIdentity(displayName))
}

fun PlatformAppEngine.getPublicId(): String {
    val result = dispatchDomainCommand(DomainCommand.GetPublicId)
    return (result as? DomainCommandResult.Text)?.value ?: unexpectedResult("GetPublicId")
}

fun PlatformAppEngine.getDisplayName(): String {
    val result = dispatchDomainCommand(DomainCommand.GetDisplayName)
    return (result as? DomainCommandResult.Text)?.value ?: unexpectedResult("GetDisplayName")
}

fun PlatformAppEngine.getOwnCard(): MobileContactCard {
    val result = dispatchDomainCommand(DomainCommand.GetOwnCard)
    return (result as? DomainCommandResult.ContactCardPayload)?.card ?: unexpectedResult("GetOwnCard")
}

fun PlatformAppEngine.contactCount(): UInt {
    val result = dispatchDomainCommand(DomainCommand.ContactCount)
    return (result as? DomainCommandResult.Count)?.value ?: unexpectedResult("ContactCount")
}

fun PlatformAppEngine.importBackup(
    backupData: String,
    password: String,
) {
    dispatchDomainCommand(DomainCommand.ImportBackup(backupData, password))
}

fun PlatformAppEngine.initDemoContactIfNeeded(): MobileDemoContact? {
    val result = dispatchDomainCommand(DomainCommand.InitDemoContactIfNeeded)
    val opt =
        result as? DomainCommandResult.DemoContactOpt
            ?: unexpectedResult("InitDemoContactIfNeeded")
    return opt.contact
}

fun PlatformAppEngine.runContentUpdateCycle(): MobileContentCycleOutcome {
    val result = dispatchDomainCommand(DomainCommand.RunContentUpdateCycle)
    return (result as? DomainCommandResult.ContentUpdateCycle)?.outcome
        ?: unexpectedResult("RunContentUpdateCycle")
}

fun PlatformAppEngine.authenticate(password: String): MobileAuthMode {
    val result = dispatchDomainCommand(DomainCommand.Authenticate(password))
    return (result as? DomainCommandResult.AuthMode)?.mode ?: unexpectedResult("Authenticate")
}

// User-initiated relay sync (collapse-vauchi-platform G1). The engine owns
// the connect lifecycle and honors the C1/C2 timing throttle: a throttled
// (TooSoon) call comes back as a benign no-change MobileSyncResult.
fun PlatformAppEngine.sync(): MobileSyncResult {
    val result = dispatchDomainCommand(DomainCommand.Sync)
    return (result as? DomainCommandResult.SyncResult)?.result ?: unexpectedResult("Sync")
}
