// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import uniffi.vauchi_platform.DomainCommand
import uniffi.vauchi_platform.DomainCommandResult
import uniffi.vauchi_platform.MobileAhaMoment
import uniffi.vauchi_platform.MobileAhaMomentType
import uniffi.vauchi_platform.MobileApplyResult
import uniffi.vauchi_platform.MobileContact
import uniffi.vauchi_platform.MobileContactCard
import uniffi.vauchi_platform.MobileDemoContact
import uniffi.vauchi_platform.MobileDemoContactState
import uniffi.vauchi_platform.MobileException
import uniffi.vauchi_platform.MobileFieldNote
import uniffi.vauchi_platform.MobileFieldType
import uniffi.vauchi_platform.MobileSocialNetwork
import uniffi.vauchi_platform.MobileUpdateStatus
import uniffi.vauchi_platform.PlatformAppEngine

// Typed wrappers around `PlatformAppEngine.dispatchDomainCommand` for
// the collapse-vauchi-platform migration. Mirrors the iOS
// `PlatformAppEngine+DomainDispatch.swift` extension; keeps repository
// call sites readable while the long-tail UniFFI surface collapses
// onto `dispatch_domain_command` per
// `_private/docs/problems/2026-04-28-collapse-vauchi-platform-into-app-engine/`.

private fun unexpectedResult(name: String): Nothing = throw MobileException.Other(detail = "$name: unexpected result variant")

// ── Identity / Bootstrap (C1) ──

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

// ── Contact Field Mutation (C1) ──

fun PlatformAppEngine.getOwnCard(): MobileContactCard {
    val result = dispatchDomainCommand(DomainCommand.GetOwnCard)
    return (result as? DomainCommandResult.ContactCardPayload)?.card ?: unexpectedResult("GetOwnCard")
}

fun PlatformAppEngine.addField(
    fieldType: MobileFieldType,
    label: String,
    value: String,
) {
    dispatchDomainCommand(DomainCommand.AddField(fieldType, label, value))
}

fun PlatformAppEngine.updateField(
    label: String,
    newValue: String,
) {
    dispatchDomainCommand(DomainCommand.UpdateField(label, newValue))
}

fun PlatformAppEngine.removeField(label: String): Boolean {
    val result = dispatchDomainCommand(DomainCommand.RemoveField(label))
    return (result as? DomainCommandResult.Bool)?.value ?: unexpectedResult("RemoveField")
}

fun PlatformAppEngine.setDisplayName(name: String) {
    dispatchDomainCommand(DomainCommand.SetDisplayName(name))
}

// ── Backup (C5) ──

fun PlatformAppEngine.exportBackup(password: String): String {
    val result = dispatchDomainCommand(DomainCommand.ExportBackup(password))
    return (result as? DomainCommandResult.Text)?.value ?: unexpectedResult("ExportBackup")
}

fun PlatformAppEngine.importBackup(
    backupData: String,
    password: String,
) {
    dispatchDomainCommand(DomainCommand.ImportBackup(backupData, password))
}

// ── Aha Moments (B7 batch 5) ──

fun PlatformAppEngine.hasSeenAhaMoment(momentType: MobileAhaMomentType): Boolean {
    val result = dispatchDomainCommand(DomainCommand.HasSeenAhaMoment(momentType))
    return (result as? DomainCommandResult.Bool)?.value ?: unexpectedResult("HasSeenAhaMoment")
}

fun PlatformAppEngine.tryTriggerAhaMoment(momentType: MobileAhaMomentType): MobileAhaMoment? {
    val result = dispatchDomainCommand(DomainCommand.TryTriggerAhaMoment(momentType))
    val opt = result as? DomainCommandResult.AhaMomentOpt ?: unexpectedResult("TryTriggerAhaMoment")
    return opt.moment
}

fun PlatformAppEngine.tryTriggerAhaMomentWithContext(
    momentType: MobileAhaMomentType,
    context: String,
): MobileAhaMoment? {
    val result =
        dispatchDomainCommand(
            DomainCommand.TryTriggerAhaMomentWithContext(momentType, context),
        )
    val opt =
        result as? DomainCommandResult.AhaMomentOpt
            ?: unexpectedResult("TryTriggerAhaMomentWithContext")
    return opt.moment
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
    val opt =
        result as? DomainCommandResult.DemoContactOpt
            ?: unexpectedResult("InitDemoContactIfNeeded")
    return opt.contact
}

fun PlatformAppEngine.getDemoContact(): MobileDemoContact? {
    val result = dispatchDomainCommand(DomainCommand.GetDemoContact)
    val opt = result as? DomainCommandResult.DemoContactOpt ?: unexpectedResult("GetDemoContact")
    return opt.contact
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
    val opt = result as? DomainCommandResult.DemoContactOpt ?: unexpectedResult("TriggerDemoUpdate")
    return opt.contact
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
    val opt = result as? DomainCommandResult.DemoContactOpt ?: unexpectedResult("RestoreDemoContact")
    return opt.contact
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
    val opt = result as? DomainCommandResult.StringOpt ?: unexpectedResult("GetProfileUrl")
    return opt.value
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

// ── Contact CRUD (C2) ──

fun PlatformAppEngine.listContacts(): List<MobileContact> {
    val result = dispatchDomainCommand(DomainCommand.ListContacts)
    return (result as? DomainCommandResult.Contacts)?.contacts ?: unexpectedResult("ListContacts")
}

fun PlatformAppEngine.listContactsPaginated(
    offset: UInt,
    limit: UInt,
): List<MobileContact> {
    val result = dispatchDomainCommand(DomainCommand.ListContactsPaginated(offset, limit))
    return (result as? DomainCommandResult.Contacts)?.contacts
        ?: unexpectedResult("ListContactsPaginated")
}

fun PlatformAppEngine.getContact(id: String): MobileContact? {
    val result = dispatchDomainCommand(DomainCommand.GetContact(id))
    val opt = result as? DomainCommandResult.ContactOpt ?: unexpectedResult("GetContact")
    return opt.contact
}

fun PlatformAppEngine.searchContacts(query: String): List<MobileContact> {
    val result = dispatchDomainCommand(DomainCommand.SearchContacts(query))
    return (result as? DomainCommandResult.Contacts)?.contacts ?: unexpectedResult("SearchContacts")
}

fun PlatformAppEngine.contactCount(): UInt {
    val result = dispatchDomainCommand(DomainCommand.ContactCount)
    return (result as? DomainCommandResult.Count)?.value ?: unexpectedResult("ContactCount")
}

fun PlatformAppEngine.removeContact(id: String): Boolean {
    val result = dispatchDomainCommand(DomainCommand.RemoveContact(id))
    return (result as? DomainCommandResult.Bool)?.value ?: unexpectedResult("RemoveContact")
}

fun PlatformAppEngine.softDeleteImportedContact(id: String) {
    dispatchDomainCommand(DomainCommand.SoftDeleteImportedContact(id))
}

fun PlatformAppEngine.undoDeleteImportedContact(id: String) {
    dispatchDomainCommand(DomainCommand.UndoDeleteImportedContact(id))
}

fun PlatformAppEngine.hardDeleteImportedContact(id: String) {
    dispatchDomainCommand(DomainCommand.HardDeleteImportedContact(id))
}

fun PlatformAppEngine.archiveContact(id: String) {
    dispatchDomainCommand(DomainCommand.ArchiveContact(id))
}

fun PlatformAppEngine.unarchiveContact(id: String) {
    dispatchDomainCommand(DomainCommand.UnarchiveContact(id))
}

fun PlatformAppEngine.listArchivedContacts(): List<MobileContact> {
    val result = dispatchDomainCommand(DomainCommand.ListArchivedContacts)
    return (result as? DomainCommandResult.Contacts)?.contacts
        ?: unexpectedResult("ListArchivedContacts")
}

fun PlatformAppEngine.hideContact(contactId: String) {
    dispatchDomainCommand(DomainCommand.HideContact(contactId))
}

fun PlatformAppEngine.unhideContact(contactId: String) {
    dispatchDomainCommand(DomainCommand.UnhideContact(contactId))
}

// ── Contact Verification (C2) ──

fun PlatformAppEngine.verifyContact(id: String) {
    dispatchDomainCommand(DomainCommand.VerifyContact(id))
}

fun PlatformAppEngine.setProposalTrusted(
    contactId: String,
    trusted: Boolean,
) {
    dispatchDomainCommand(DomainCommand.SetProposalTrusted(contactId, trusted))
}

fun PlatformAppEngine.getOwnFingerprint(): String {
    val result = dispatchDomainCommand(DomainCommand.GetOwnFingerprint)
    return (result as? DomainCommandResult.Text)?.value ?: unexpectedResult("GetOwnFingerprint")
}

// ── Contact Notes (C2) ──

fun PlatformAppEngine.setContactNote(
    contactId: String,
    note: String,
) {
    dispatchDomainCommand(DomainCommand.SetContactNote(contactId, note))
}

fun PlatformAppEngine.getContactNote(contactId: String): String? {
    val result = dispatchDomainCommand(DomainCommand.GetContactNote(contactId))
    val opt = result as? DomainCommandResult.StringOpt ?: unexpectedResult("GetContactNote")
    return opt.value
}

fun PlatformAppEngine.deleteContactNote(contactId: String) {
    dispatchDomainCommand(DomainCommand.DeleteContactNote(contactId))
}

fun PlatformAppEngine.setContactFieldNote(
    contactId: String,
    fieldId: String,
    note: String,
) {
    dispatchDomainCommand(DomainCommand.SetContactFieldNote(contactId, fieldId, note))
}

fun PlatformAppEngine.getContactFieldNotes(contactId: String): List<MobileFieldNote> {
    val result = dispatchDomainCommand(DomainCommand.GetContactFieldNotes(contactId))
    return (result as? DomainCommandResult.FieldNotes)?.notes
        ?: unexpectedResult("GetContactFieldNotes")
}

fun PlatformAppEngine.deleteContactFieldNote(
    contactId: String,
    fieldId: String,
) {
    dispatchDomainCommand(DomainCommand.DeleteContactFieldNote(contactId, fieldId))
}
