// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.data

import uniffi.vauchi_platform.DomainCommand
import uniffi.vauchi_platform.DomainCommandResult
import uniffi.vauchi_platform.MobileApplyResult
import uniffi.vauchi_platform.MobileAuthMode
import uniffi.vauchi_platform.MobileConsentRecord
import uniffi.vauchi_platform.MobileConsentStatus
import uniffi.vauchi_platform.MobileConsentType
import uniffi.vauchi_platform.MobileContact
import uniffi.vauchi_platform.MobileContactCard
import uniffi.vauchi_platform.MobileContactDetailViewState
import uniffi.vauchi_platform.MobileDeletionInfo
import uniffi.vauchi_platform.MobileDeliveryRecord
import uniffi.vauchi_platform.MobileDeliverySummary
import uniffi.vauchi_platform.MobileDemoContact
import uniffi.vauchi_platform.MobileDemoContactState
import uniffi.vauchi_platform.MobileDuressSettings
import uniffi.vauchi_platform.MobileException
import uniffi.vauchi_platform.MobileFieldNote
import uniffi.vauchi_platform.MobileFieldType
import uniffi.vauchi_platform.MobileGdprExport
import uniffi.vauchi_platform.MobileRecoveryVerification
import uniffi.vauchi_platform.MobileRetryEntry
import uniffi.vauchi_platform.MobileSocialNetwork
import uniffi.vauchi_platform.MobileSyncResult
import uniffi.vauchi_platform.MobileUpdateStatus
import uniffi.vauchi_platform.MobileVisibilityLabel
import uniffi.vauchi_platform.MobileVisibilityLabelDetail
import uniffi.vauchi_platform.PlatformAppEngine

// Typed wrappers around `PlatformAppEngine.dispatchDomainCommand` for
// the collapse-vauchi-platform migration. Mirrors the iOS
// `PlatformAppEngine+DomainDispatch.swift` extension; keeps repository
// call sites readable while the long-tail UniFFI surface collapses
// onto `dispatch_domain_command` per
// `_private/docs/problems/2026-04-28-collapse-vauchi-platform-into-app-engine/`.

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

fun PlatformAppEngine.isCertificatePinningEnabled(): Boolean {
    val result = dispatchDomainCommand(DomainCommand.IsCertificatePinningEnabled)
    return (result as? DomainCommandResult.Bool)?.value
        ?: unexpectedResult("IsCertificatePinningEnabled")
}

fun PlatformAppEngine.setPinnedCertificate(certPem: String) {
    dispatchDomainCommand(DomainCommand.SetPinnedCertificate(certPem))
}

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

fun PlatformAppEngine.hideFieldFromContact(
    contactId: String,
    fieldLabel: String,
) {
    dispatchDomainCommand(DomainCommand.HideFieldFromContact(contactId, fieldLabel))
}

fun PlatformAppEngine.showFieldToContact(
    contactId: String,
    fieldLabel: String,
) {
    dispatchDomainCommand(DomainCommand.ShowFieldToContact(contactId, fieldLabel))
}

fun PlatformAppEngine.isFieldVisibleToContact(
    contactId: String,
    fieldLabel: String,
): Boolean {
    val result = dispatchDomainCommand(DomainCommand.IsFieldVisibleToContact(contactId, fieldLabel))
    return (result as? DomainCommandResult.Bool)?.value
        ?: unexpectedResult("IsFieldVisibleToContact")
}

fun PlatformAppEngine.listLabels(): List<MobileVisibilityLabel> {
    val result = dispatchDomainCommand(DomainCommand.ListLabels)
    return (result as? DomainCommandResult.Labels)?.labels ?: unexpectedResult("ListLabels")
}

fun PlatformAppEngine.createLabel(name: String): MobileVisibilityLabel {
    val result = dispatchDomainCommand(DomainCommand.CreateLabel(name))
    return (result as? DomainCommandResult.Label)?.label ?: unexpectedResult("CreateLabel")
}

fun PlatformAppEngine.getLabel(labelId: String): MobileVisibilityLabelDetail {
    val result = dispatchDomainCommand(DomainCommand.GetLabel(labelId))
    return (result as? DomainCommandResult.LabelDetail)?.detail ?: unexpectedResult("GetLabel")
}

fun PlatformAppEngine.renameLabel(
    labelId: String,
    newName: String,
) {
    dispatchDomainCommand(DomainCommand.RenameLabel(labelId, newName))
}

fun PlatformAppEngine.deleteLabel(labelId: String) {
    dispatchDomainCommand(DomainCommand.DeleteLabel(labelId))
}

fun PlatformAppEngine.addContactToGroup(
    labelId: String,
    contactId: String,
) {
    dispatchDomainCommand(DomainCommand.AddContactToGroup(labelId, contactId))
}

fun PlatformAppEngine.removeContactFromGroup(
    labelId: String,
    contactId: String,
) {
    dispatchDomainCommand(DomainCommand.RemoveContactFromGroup(labelId, contactId))
}

fun PlatformAppEngine.getGroupsForContact(contactId: String): List<MobileVisibilityLabel> {
    val result = dispatchDomainCommand(DomainCommand.GetGroupsForContact(contactId))
    return (result as? DomainCommandResult.Labels)?.labels
        ?: unexpectedResult("GetGroupsForContact")
}

fun PlatformAppEngine.setGroupFieldVisibility(
    labelId: String,
    fieldLabel: String,
    isVisible: Boolean,
) {
    dispatchDomainCommand(DomainCommand.SetGroupFieldVisibility(labelId, fieldLabel, isVisible))
}

fun PlatformAppEngine.getSuggestedLabels(): List<String> {
    val result = dispatchDomainCommand(DomainCommand.GetSuggestedLabels)
    return (result as? DomainCommandResult.Strings)?.values ?: unexpectedResult("GetSuggestedLabels")
}

fun PlatformAppEngine.getDeliveryRecordsForContact(recipientId: String): List<MobileDeliveryRecord> {
    val result = dispatchDomainCommand(DomainCommand.GetDeliveryRecordsForContact(recipientId))
    return (result as? DomainCommandResult.DeliveryRecords)?.records
        ?: unexpectedResult("GetDeliveryRecordsForContact")
}

fun PlatformAppEngine.getDeliverySummary(messageId: String): MobileDeliverySummary {
    val result = dispatchDomainCommand(DomainCommand.GetDeliverySummary(messageId))
    return (result as? DomainCommandResult.DeliverySummary)?.summary
        ?: unexpectedResult("GetDeliverySummary")
}

fun PlatformAppEngine.pendingUpdateCount(): UInt {
    val result = dispatchDomainCommand(DomainCommand.PendingUpdateCount)
    return (result as? DomainCommandResult.Count)?.value
        ?: unexpectedResult("PendingUpdateCount")
}

fun PlatformAppEngine.listHiddenContacts(): List<MobileContact> {
    val result = dispatchDomainCommand(DomainCommand.ListHiddenContacts)
    return (result as? DomainCommandResult.Contacts)?.contacts
        ?: unexpectedResult("ListHiddenContacts")
}

fun PlatformAppEngine.verifyRecoveryProof(proofB64: String): MobileRecoveryVerification {
    val result = dispatchDomainCommand(DomainCommand.VerifyRecoveryProof(proofB64))
    return (result as? DomainCommandResult.RecoveryVerification)?.verification
        ?: unexpectedResult("VerifyRecoveryProof")
}

fun PlatformAppEngine.authenticate(password: String): MobileAuthMode {
    val result = dispatchDomainCommand(DomainCommand.Authenticate(password))
    return (result as? DomainCommandResult.AuthMode)?.mode ?: unexpectedResult("Authenticate")
}

fun PlatformAppEngine.setupAppPassword(password: String) {
    dispatchDomainCommand(DomainCommand.SetupAppPassword(password))
}

fun PlatformAppEngine.isPasswordEnabled(): Boolean {
    val result = dispatchDomainCommand(DomainCommand.IsPasswordEnabled)
    return (result as? DomainCommandResult.Bool)?.value ?: unexpectedResult("IsPasswordEnabled")
}

fun PlatformAppEngine.isDuressEnabled(): Boolean {
    val result = dispatchDomainCommand(DomainCommand.IsDuressEnabled)
    return (result as? DomainCommandResult.Bool)?.value ?: unexpectedResult("IsDuressEnabled")
}

fun PlatformAppEngine.setupDuressPassword(duressPassword: String) {
    dispatchDomainCommand(DomainCommand.SetupDuressPassword(duressPassword))
}

fun PlatformAppEngine.disableDuress() {
    dispatchDomainCommand(DomainCommand.DisableDuress)
}

fun PlatformAppEngine.configureDuressAlerts(
    contactIds: List<String>,
    message: String,
) {
    dispatchDomainCommand(DomainCommand.ConfigureDuressAlerts(contactIds, message))
}

fun PlatformAppEngine.getDuressSettings(): MobileDuressSettings? {
    val result = dispatchDomainCommand(DomainCommand.GetDuressSettings)
    val opt = result as? DomainCommandResult.DuressSettingsOpt ?: unexpectedResult("GetDuressSettings")
    return opt.settings
}

fun PlatformAppEngine.exportGdprData(): MobileGdprExport {
    val result = dispatchDomainCommand(DomainCommand.ExportGdprData)
    return (result as? DomainCommandResult.GdprExport)?.export ?: unexpectedResult("ExportGdprData")
}

fun PlatformAppEngine.scheduleIdentityDeletion(): MobileDeletionInfo {
    val result = dispatchDomainCommand(DomainCommand.ScheduleIdentityDeletion)
    return (result as? DomainCommandResult.DeletionInfo)?.info
        ?: unexpectedResult("ScheduleIdentityDeletion")
}

fun PlatformAppEngine.cancelIdentityDeletion() {
    dispatchDomainCommand(DomainCommand.CancelIdentityDeletion)
}

fun PlatformAppEngine.getDeletionState(): MobileDeletionInfo {
    val result = dispatchDomainCommand(DomainCommand.GetDeletionState)
    return (result as? DomainCommandResult.DeletionInfo)?.info ?: unexpectedResult("GetDeletionState")
}

fun PlatformAppEngine.grantConsent(consentType: MobileConsentType) {
    dispatchDomainCommand(DomainCommand.GrantConsent(consentType))
}

fun PlatformAppEngine.revokeConsent(consentType: MobileConsentType) {
    dispatchDomainCommand(DomainCommand.RevokeConsent(consentType))
}

fun PlatformAppEngine.getConsentRecords(): List<MobileConsentRecord> {
    val result = dispatchDomainCommand(DomainCommand.GetConsentRecords)
    return (result as? DomainCommandResult.ConsentRecords)?.records
        ?: unexpectedResult("GetConsentRecords")
}

// The direct `pub fn trust_contact_for_recovery` / `untrust_contact_for_recovery`
// methods on `impl PlatformAppEngine` retired in core 0.51.2
// (`refactor: route recovery-trust through DomainCommand`, `513ab6f8`).
// These wrappers route through the typed dispatch path. iOS still
// calls `vauchi.trustContactForRecovery` (the legacy `VauchiPlatform`
// surface, which stays under Phase B7 hybrid R3) — different
// path, same semantics.

fun PlatformAppEngine.trustContactForRecovery(contactId: String) {
    dispatchDomainCommand(DomainCommand.TrustContactForRecovery(contactId))
}

fun PlatformAppEngine.untrustContactForRecovery(contactId: String) {
    dispatchDomainCommand(DomainCommand.UntrustContactForRecovery(contactId))
}

// `mobile_contact_detail.rs` impl block retired in core 0.51.2
// (`refactor: retire mobile_*.rs contact-CRUD impl blocks`,
// `5b415656`). The two read methods moved to `DomainCommand` dispatch.

fun PlatformAppEngine.contactDetailFooterActionId(contactId: String): String {
    val result = dispatchDomainCommand(DomainCommand.ContactDetailFooterActionId(contactId))
    return (result as? DomainCommandResult.Text)?.value
        ?: unexpectedResult("ContactDetailFooterActionId")
}

fun PlatformAppEngine.contactDetailViewState(contactId: String): MobileContactDetailViewState {
    val result = dispatchDomainCommand(DomainCommand.ContactDetailViewState(contactId))
    return (result as? DomainCommandResult.ContactDetailView)?.state
        ?: unexpectedResult("ContactDetailViewState")
}

// User-initiated relay sync (collapse-vauchi-platform G1). The engine owns
// the connect lifecycle and honors the C1/C2 timing throttle: a throttled
// (TooSoon) call comes back as a benign no-change MobileSyncResult.
fun PlatformAppEngine.sync(): MobileSyncResult {
    val result = dispatchDomainCommand(DomainCommand.Sync)
    return (result as? DomainCommandResult.SyncResult)?.result ?: unexpectedResult("Sync")
}
