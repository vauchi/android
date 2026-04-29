// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import uniffi.vauchi_platform.DomainCommand
import uniffi.vauchi_platform.DomainCommandResult
import uniffi.vauchi_platform.MobileAhaMoment
import uniffi.vauchi_platform.MobileAhaMomentType
import uniffi.vauchi_platform.MobileApplyResult
import uniffi.vauchi_platform.MobileDemoContact
import uniffi.vauchi_platform.MobileDemoContactState
import uniffi.vauchi_platform.MobileException
import uniffi.vauchi_platform.MobileSocialNetwork
import uniffi.vauchi_platform.MobileUpdateStatus
import uniffi.vauchi_platform.PlatformAppEngine

// Typed wrappers around `PlatformAppEngine.dispatchDomainCommand` for
// the C8-partial migration (Aha Moments, Demo Contact, Social Networks,
// Content Updates, Certificate Pinning). Mirrors the iOS
// `PlatformAppEngine+DomainDispatch.swift` extension; keeps repository
// call sites readable while the long-tail UniFFI surface collapses
// onto `dispatch_domain_command` per
// `_private/docs/problems/2026-04-28-collapse-vauchi-platform-into-app-engine/`.

private fun unexpectedResult(name: String): Nothing = throw MobileException.Other(detail = "$name: unexpected result variant")

// ── Aha Moments (B7 batch 5) ──

fun PlatformAppEngine.hasSeenAhaMoment(momentType: MobileAhaMomentType): Boolean {
    val result = dispatchDomainCommand(DomainCommand.HasSeenAhaMoment(momentType))
    return (result as? DomainCommandResult.Bool)?.value ?: unexpectedResult("HasSeenAhaMoment")
}

fun PlatformAppEngine.tryTriggerAhaMoment(momentType: MobileAhaMomentType): MobileAhaMoment? {
    val result = dispatchDomainCommand(DomainCommand.TryTriggerAhaMoment(momentType))
    return (result as? DomainCommandResult.AhaMomentOpt)?.moment ?: unexpectedResult("TryTriggerAhaMoment")
}

fun PlatformAppEngine.tryTriggerAhaMomentWithContext(
    momentType: MobileAhaMomentType,
    context: String,
): MobileAhaMoment? {
    val result =
        dispatchDomainCommand(
            DomainCommand.TryTriggerAhaMomentWithContext(momentType, context),
        )
    return (result as? DomainCommandResult.AhaMomentOpt)?.moment
        ?: unexpectedResult("TryTriggerAhaMomentWithContext")
}

fun PlatformAppEngine.ahaMomentsSeenCount(): UInt {
    val result = dispatchDomainCommand(DomainCommand.AhaMomentsSeenCount)
    return (result as? DomainCommandResult.Count)?.value ?: unexpectedResult("AhaMomentsSeenCount")
}

fun PlatformAppEngine.ahaMomentsTotalCount(): UInt {
    val result = dispatchDomainCommand(DomainCommand.AhaMomentsTotalCount)
    return (result as? DomainCommandResult.Count)?.value ?: unexpectedResult("AhaMomentsTotalCount")
}

fun PlatformAppEngine.resetAhaMoments() {
    dispatchDomainCommand(DomainCommand.ResetAhaMoments)
}

// ── Demo Contact (B7 batch 5) ──

fun PlatformAppEngine.initDemoContactIfNeeded(): MobileDemoContact? {
    val result = dispatchDomainCommand(DomainCommand.InitDemoContactIfNeeded)
    return (result as? DomainCommandResult.DemoContactOpt)?.contact
        ?: unexpectedResult("InitDemoContactIfNeeded")
}

fun PlatformAppEngine.getDemoContact(): MobileDemoContact? {
    val result = dispatchDomainCommand(DomainCommand.GetDemoContact)
    return (result as? DomainCommandResult.DemoContactOpt)?.contact ?: unexpectedResult("GetDemoContact")
}

fun PlatformAppEngine.getDemoContactState(): MobileDemoContactState {
    val result = dispatchDomainCommand(DomainCommand.GetDemoContactState)
    return (result as? DomainCommandResult.DemoContactState)?.state
        ?: unexpectedResult("GetDemoContactState")
}

fun PlatformAppEngine.isDemoUpdateAvailable(): Boolean {
    val result = dispatchDomainCommand(DomainCommand.IsDemoUpdateAvailable)
    return (result as? DomainCommandResult.Bool)?.value ?: unexpectedResult("IsDemoUpdateAvailable")
}

fun PlatformAppEngine.triggerDemoUpdate(): MobileDemoContact? {
    val result = dispatchDomainCommand(DomainCommand.TriggerDemoUpdate)
    return (result as? DomainCommandResult.DemoContactOpt)?.contact
        ?: unexpectedResult("TriggerDemoUpdate")
}

fun PlatformAppEngine.dismissDemoContact() {
    dispatchDomainCommand(DomainCommand.DismissDemoContact)
}

fun PlatformAppEngine.autoRemoveDemoContact(): Boolean {
    val result = dispatchDomainCommand(DomainCommand.AutoRemoveDemoContact)
    return (result as? DomainCommandResult.Bool)?.value ?: unexpectedResult("AutoRemoveDemoContact")
}

fun PlatformAppEngine.restoreDemoContact(): MobileDemoContact? {
    val result = dispatchDomainCommand(DomainCommand.RestoreDemoContact)
    return (result as? DomainCommandResult.DemoContactOpt)?.contact
        ?: unexpectedResult("RestoreDemoContact")
}

// ── Social Networks (B7 batch 2 / 19) ──

fun PlatformAppEngine.listSocialNetworks(): List<MobileSocialNetwork> {
    val result = dispatchDomainCommand(DomainCommand.ListSocialNetworks)
    return (result as? DomainCommandResult.SocialNetworks)?.networks
        ?: unexpectedResult("ListSocialNetworks")
}

fun PlatformAppEngine.searchSocialNetworks(query: String): List<MobileSocialNetwork> {
    val result = dispatchDomainCommand(DomainCommand.SearchSocialNetworks(query))
    return (result as? DomainCommandResult.SocialNetworks)?.networks
        ?: unexpectedResult("SearchSocialNetworks")
}

fun PlatformAppEngine.getProfileUrl(
    networkId: String,
    username: String,
): String? {
    val result = dispatchDomainCommand(DomainCommand.GetProfileUrl(networkId, username))
    return (result as? DomainCommandResult.StringOpt)?.value ?: unexpectedResult("GetProfileUrl")
}

fun PlatformAppEngine.reloadSocialNetworks(): List<MobileSocialNetwork> {
    val result = dispatchDomainCommand(DomainCommand.ReloadSocialNetworks)
    return (result as? DomainCommandResult.SocialNetworks)?.networks
        ?: unexpectedResult("ReloadSocialNetworks")
}

// ── Content Updates (B7 batch 2) ──

fun PlatformAppEngine.isContentUpdatesSupported(): Boolean {
    val result = dispatchDomainCommand(DomainCommand.IsContentUpdatesSupported)
    return (result as? DomainCommandResult.Bool)?.value ?: unexpectedResult("IsContentUpdatesSupported")
}

fun PlatformAppEngine.checkContentUpdates(): MobileUpdateStatus {
    val result = dispatchDomainCommand(DomainCommand.CheckContentUpdates)
    return (result as? DomainCommandResult.UpdateStatus)?.status ?: unexpectedResult("CheckContentUpdates")
}

fun PlatformAppEngine.applyContentUpdates(): MobileApplyResult {
    val result = dispatchDomainCommand(DomainCommand.ApplyContentUpdates)
    return (result as? DomainCommandResult.ApplyResult)?.result ?: unexpectedResult("ApplyContentUpdates")
}

// ── Certificate Pinning (B7 batch 21) ──

fun PlatformAppEngine.isCertificatePinningEnabled(): Boolean {
    val result = dispatchDomainCommand(DomainCommand.IsCertificatePinningEnabled)
    return (result as? DomainCommandResult.Bool)?.value
        ?: unexpectedResult("IsCertificatePinningEnabled")
}

fun PlatformAppEngine.setPinnedCertificate(certPem: String) {
    dispatchDomainCommand(DomainCommand.SetPinnedCertificate(certPem))
}
