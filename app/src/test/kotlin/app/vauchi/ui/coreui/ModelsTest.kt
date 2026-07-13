// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that Kotlin model deserialization matches serde's JSON output format.
 *
 * These tests use JSON strings matching the exact format that the Rust core
 * produces via `serde_json::to_string`. This ensures the Kotlin models
 * correctly interoperate with the core.
 */
class ModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ── ScreenModel ─────────────────────────────────────────────────

    @Test
    fun `deserialize minimal ScreenModel`() {
        val input =
            """
            {
                "screen_id": "welcome",
                "title": "Welcome",
                "components": [],
                "actions": []
            }
            """.trimIndent()

        val screen = json.decodeFromString<ScreenModel>(input)
        assertEquals("welcome", screen.screenId)
        assertEquals("Welcome", screen.title)
        assertNull(screen.subtitle)
        assertTrue(screen.components.isEmpty())
        assertTrue(screen.actions.isEmpty())
        assertNull(screen.progress)
    }

    @Test
    fun `deserialize ScreenModel with Pinned layout`() {
        val input =
            """
            {
                "screen_id": "contacts",
                "title": "Contacts",
                "components": [],
                "actions": [],
                "layout": "Pinned"
            }
            """.trimIndent()

        val screen = json.decodeFromString<ScreenModel>(input)
        assertEquals(ScreenLayout.Pinned, screen.layout)
    }

    @Test
    fun `layout defaults to Scroll when absent`() {
        val input =
            """
            {
                "screen_id": "welcome",
                "title": "Welcome",
                "components": [],
                "actions": []
            }
            """.trimIndent()

        val screen = json.decodeFromString<ScreenModel>(input)
        assertEquals(ScreenLayout.Scroll, screen.layout)
    }

    @Test
    fun `deserialize ScreenModel with progress`() {
        val input =
            """
            {
                "screen_id": "step1",
                "title": "Create Identity",
                "subtitle": "Choose your display name",
                "components": [],
                "actions": [],
                "progress": {
                    "current_step": 1,
                    "total_steps": 9,
                    "label": "Step 1 of 9"
                }
            }
            """.trimIndent()

        val screen = json.decodeFromString<ScreenModel>(input)
        assertEquals("step1", screen.screenId)
        assertEquals("Choose your display name", screen.subtitle)
        val progress = screen.progress!!
        assertEquals(1, progress.currentStep)
        assertEquals(9, progress.totalSteps)
        assertEquals("Step 1 of 9", progress.label)
    }

    // ── Component ───────────────────────────────────────────────────

    @Test
    fun `deserialize Text component`() {
        val input = """{"Text":{"id":"t1","content":"Hello","style":"Title"}}"""
        val component = json.decodeFromString<Component>(input)
        assertTrue(component is Component.Text)
        val text = component as Component.Text
        assertEquals("t1", text.id)
        assertEquals("Hello", text.content)
        assertEquals(TextStyle.Title, text.style)
    }

    @Test
    fun `deserialize TextInput component with validation error`() {
        val input =
            """
            {
                "TextInput": {
                    "id": "display_name",
                    "label": "Display Name",
                    "value": "",
                    "placeholder": "e.g. Alice",
                    "max_length": 50,
                    "validation_error": "Name is required",
                    "input_type": "Text"
                }
            }
            """.trimIndent()

        val component = json.decodeFromString<Component>(input)
        assertTrue(component is Component.TextInput)
        val textInput = component as Component.TextInput
        assertEquals("display_name", textInput.id)
        assertEquals("Display Name", textInput.label)
        assertEquals("", textInput.value)
        assertEquals("e.g. Alice", textInput.placeholder)
        assertEquals(50, textInput.maxLength)
        assertEquals("Name is required", textInput.validationError)
        assertEquals(InputType.Text, textInput.inputType)
    }

    @Test
    fun `deserialize TextInput component with null optionals`() {
        val input =
            """
            {
                "TextInput": {
                    "id": "phone",
                    "label": "Phone",
                    "value": "+1234",
                    "placeholder": null,
                    "max_length": null,
                    "validation_error": null,
                    "input_type": "Phone"
                }
            }
            """.trimIndent()

        val component = json.decodeFromString<Component>(input)
        val textInput = component as Component.TextInput
        assertNull(textInput.placeholder)
        assertNull(textInput.maxLength)
        assertNull(textInput.validationError)
        assertEquals(InputType.Phone, textInput.inputType)
    }

    @Test
    fun `deserialize ToggleList component`() {
        val input =
            """
            {
                "ToggleList": {
                    "id": "groups",
                    "label": "Select groups",
                    "items": [
                        {"id": "family", "label": "Family", "selected": true, "subtitle": "Close family"},
                        {"id": "work", "label": "Work", "selected": false, "subtitle": null}
                    ]
                }
            }
            """.trimIndent()

        val component = json.decodeFromString<Component>(input)
        val toggleList = component as Component.ToggleList
        assertEquals("groups", toggleList.id)
        assertEquals(2, toggleList.items.size)
        assertEquals("family", toggleList.items[0].id)
        assertTrue(toggleList.items[0].selected)
        assertEquals("Close family", toggleList.items[0].subtitle)
        assertNull(toggleList.items[1].subtitle)
    }

    @Test
    fun `deserialize FieldList component`() {
        val input =
            """
            {
                "FieldList": {
                    "id": "fields",
                    "fields": [
                        {"id": "f1", "field_type": "Phone", "label": "Mobile", "value": "+1234", "icon": "phone", "visibility": "Shown"},
                        {"id": "f2", "field_type": "Email", "label": "Work", "value": "a@b.com", "icon": "envelope", "visibility": "Hidden"}
                    ],
                    "visibility_mode": "ShowHide",
                    "available_scopes": ["Family", "Work"]
                }
            }
            """.trimIndent()

        val component = json.decodeFromString<Component>(input)
        val fieldList = component as Component.FieldList
        assertEquals(2, fieldList.fields.size)
        assertEquals("Phone", fieldList.fields[0].fieldType)
        assertEquals("phone", fieldList.fields[0].icon)
        assertTrue(fieldList.fields[0].visibility is UiFieldVisibility.Shown)
        assertEquals("envelope", fieldList.fields[1].icon)
        assertTrue(fieldList.fields[1].visibility is UiFieldVisibility.Hidden)
        assertEquals(VisibilityMode.ShowHide, fieldList.visibilityMode)
        assertEquals(listOf("Family", "Work"), fieldList.availableGroups)
    }

    @Test
    fun `deserialize Preview component`() {
        val input =
            """
            {
                "Preview": {
                    "name": "Alice",
                    "fields": [],
                    "variants": [
                        {
                            "variant_id": "Family",
                            "display_name": "Alice",
                            "visible_fields": []
                        }
                    ],
                    "selected_variant": null
                }
            }
            """.trimIndent()

        val component = json.decodeFromString<Component>(input)
        val preview = component as Component.Preview
        assertEquals("Alice", preview.name)
        assertEquals(1, preview.variants.size)
        assertEquals("Family", preview.variants[0].variantId)
        assertNull(preview.selectedVariant)
    }

    @Test
    fun `deserialize InfoPanel component`() {
        val input =
            """
            {
                "InfoPanel": {
                    "id": "security",
                    "icon": "shield",
                    "title": "Your data is safe",
                    "items": [
                        {"icon": "lock", "title": "Encrypted", "detail": "End-to-end encrypted"}
                    ]
                }
            }
            """.trimIndent()

        val component = json.decodeFromString<Component>(input)
        val panel = component as Component.InfoPanel
        assertEquals("security", panel.id)
        assertEquals("shield", panel.icon)
        assertEquals(1, panel.items.size)
        assertEquals("lock", panel.items[0].icon)
    }

    @Test
    fun `deserialize Divider component`() {
        val input = """"Divider""""
        val component = json.decodeFromString<Component>(input)
        assertTrue(component is Component.Divider)
    }

    // ── UiFieldVisibility ───────────────────────────────────────────

    @Test
    fun `deserialize Scopes visibility with scope list`() {
        val input = """{"Scopes":["Family","Work"]}"""
        val visibility = json.decodeFromString<UiFieldVisibility>(input)
        assertTrue(visibility is UiFieldVisibility.Scopes)
        assertEquals(listOf("Family", "Work"), (visibility as UiFieldVisibility.Scopes).scopes)
    }

    // ── ScreenAction ────────────────────────────────────────────────

    @Test
    fun `deserialize ScreenAction`() {
        val input =
            """
            {
                "id": "next",
                "label": "Continue",
                "style": "Primary",
                "enabled": true
            }
            """.trimIndent()

        val action = json.decodeFromString<ScreenAction>(input)
        assertEquals("next", action.id)
        assertEquals("Continue", action.label)
        assertEquals(ActionStyle.Primary, action.style)
        assertTrue(action.enabled)
    }

    @Test
    fun `deserialize List component without window fields`() {
        // Core skip-serializes zero windowing fields — absence is the
        // unwindowed wire shape (Track B,
        // 2026-06-11-contacts-list-eager-render-anr).
        val input = """{"List":{"id":"contacts","items":[],"searchable":true}}"""
        val component = json.decodeFromString<Component>(input)
        assertTrue(component is Component.List)
        val list = component as Component.List
        assertEquals(0, list.totalCount)
        assertEquals(0, list.offset)
        assertEquals(0, list.window)
    }

    @Test
    fun `deserialize windowed List component`() {
        val input =
            """{"List":{"id":"contacts","items":[],"searchable":true,"total_count":500,"offset":200,"window":200}}"""
        val component = json.decodeFromString<Component>(input)
        assertTrue(component is Component.List)
        val list = component as Component.List
        assertEquals(500, list.totalCount)
        assertEquals(200, list.offset)
        assertEquals(200, list.window)
    }

    // ── UserAction serialization ────────────────────────────────────

    @Test
    fun `serialize ActionPressed`() {
        val action = UserAction.ActionPressed(actionId = "get_started")
        val serialized = json.encodeToString(UserAction.serializer(), action)
        assertEquals("""{"ActionPressed":{"action_id":"get_started"}}""", serialized)
    }

    @Test
    fun `serialize NavigateToTab`() {
        val action = UserAction.NavigateToTab(actionId = "groups")
        val serialized = json.encodeToString(UserAction.serializer(), action)
        assertEquals("""{"NavigateToTab":{"action_id":"groups"}}""", serialized)
    }

    @Test
    fun `serialize TextChanged`() {
        val action = UserAction.TextChanged(componentId = "display_name", value = "Alice")
        val serialized = json.encodeToString(UserAction.serializer(), action)
        assertEquals("""{"TextChanged":{"component_id":"display_name","value":"Alice"}}""", serialized)
    }

    @Test
    fun `serialize ItemToggled`() {
        val action = UserAction.ItemToggled(componentId = "groups", itemId = "Family")
        val serialized = json.encodeToString(UserAction.serializer(), action)
        assertEquals("""{"ItemToggled":{"component_id":"groups","item_id":"Family"}}""", serialized)
    }

    @Test
    fun `serialize ListWindowRequested`() {
        val action = UserAction.ListWindowRequested(componentId = "contacts", offset = 150)
        val serialized = json.encodeToString(UserAction.serializer(), action)
        assertEquals("""{"ListWindowRequested":{"component_id":"contacts","offset":150}}""", serialized)
    }

    @Test
    fun `serialize FieldVisibilityChanged with null group`() {
        val action = UserAction.FieldVisibilityChanged(fieldId = "f1", groupId = null, visible = true)
        val serialized = json.encodeToString(UserAction.serializer(), action)
        assertEquals("""{"FieldVisibilityChanged":{"field_id":"f1","group_id":null,"visible":true}}""", serialized)
    }

    @Test
    fun `serialize VariantSelected`() {
        val action = UserAction.VariantSelected(variantId = "Family")
        val serialized = json.encodeToString(UserAction.serializer(), action)
        assertEquals("""{"VariantSelected":{"variant_id":"Family"}}""", serialized)
    }

    @Test
    fun `serialize UndoPressed`() {
        val action = UserAction.UndoPressed(actionId = "undo_delete_field:f1")
        val serialized = json.encodeToString(UserAction.serializer(), action)
        assertEquals("""{"UndoPressed":{"action_id":"undo_delete_field:f1"}}""", serialized)
    }

    @Test
    fun `deserialize UndoPressed`() {
        val input = """{"UndoPressed":{"action_id":"undo_delete_field:f1"}}"""
        val result = json.decodeFromString<UserAction>(input)
        assertTrue(result is UserAction.UndoPressed)
        assertEquals("undo_delete_field:f1", (result as UserAction.UndoPressed).actionId)
    }

    // ── ActionResult ────────────────────────────────────────────────

    @Test
    fun `deserialize Complete result`() {
        val input = """"Complete""""
        val result = json.decodeFromString<ActionResult>(input)
        assertTrue(result is ActionResult.Complete)
    }

    @Test
    fun `deserialize ValidationError result`() {
        val input = """{"ValidationError":{"component_id":"name","message":"Required"}}"""
        val result = json.decodeFromString<ActionResult>(input)
        assertTrue(result is ActionResult.ValidationError)
        val error = result as ActionResult.ValidationError
        assertEquals("name", error.componentId)
        assertEquals("Required", error.message)
    }

    @Test
    fun `deserialize UpdateScreen result`() {
        val input =
            """
            {
                "UpdateScreen": {
                    "screen_id": "identity",
                    "title": "Create Identity",
                    "components": [],
                    "actions": []
                }
            }
            """.trimIndent()

        val result = json.decodeFromString<ActionResult>(input)
        assertTrue(result is ActionResult.UpdateScreen)
        assertEquals("identity", (result as ActionResult.UpdateScreen).screen.screenId)
    }

    @Test
    fun `deserialize NavigateTo result`() {
        val input =
            """
            {
                "NavigateTo": {
                    "screen_id": "groups",
                    "title": "Select Groups",
                    "components": [],
                    "actions": []
                }
            }
            """.trimIndent()

        val result = json.decodeFromString<ActionResult>(input)
        assertTrue(result is ActionResult.NavigateTo)
        assertEquals("groups", (result as ActionResult.NavigateTo).screen.screenId)
    }

    @Test
    fun `ActionResult Commands deserialization`() {
        val input = """{"Commands": {"commands": ["QrRequestScan", {"QrDisplay": {"data": "test-qr"}}]}}"""
        val result = json.decodeFromString<ActionResult>(input)
        assertTrue(result is ActionResult.Commands)
        val cmds = (result as ActionResult.Commands).commands
        assertEquals(2, cmds.size)
        assertTrue(cmds[0] is CommandDTO.QrRequestScan)
        assertTrue(cmds[1] is CommandDTO.QrDisplay)
        assertEquals("test-qr", (cmds[1] as CommandDTO.QrDisplay).data)
    }

    @Test
    fun `ActionResult ShowToast deserialization`() {
        val input = """{"ShowToast": {"message": "Saved", "undo_action_id": "undo_1"}}"""
        val result = json.decodeFromString<ActionResult>(input)
        assertTrue(result is ActionResult.ShowToast)
        assertEquals("Saved", (result as ActionResult.ShowToast).message)
        assertEquals("undo_1", result.undoActionId)
    }

    @Test
    fun `ActionResult EditContact deserialization`() {
        val input = """{"EditContact": {"contact_id": "c123"}}"""
        val result = json.decodeFromString<ActionResult>(input)
        assertTrue(result is ActionResult.EditContact)
        assertEquals("c123", (result as ActionResult.EditContact).contactId)
    }

    @Test
    fun `ActionResult OpenEntryDetail deserialization`() {
        val input = """{"OpenEntryDetail": {"field_id": "phone_1"}}"""
        val result = json.decodeFromString<ActionResult>(input)
        assertTrue(result is ActionResult.OpenEntryDetail)
        assertEquals("phone_1", (result as ActionResult.OpenEntryDetail).fieldId)
    }

    @Test
    fun `ActionResult BiometricUnlockOutcome Unlocked deserialization`() {
        val input = """{"BiometricUnlockOutcome": {"outcome": "Unlocked"}}"""
        val result = json.decodeFromString<ActionResult>(input)
        assertTrue(result is ActionResult.BiometricUnlockOutcome)
        assertEquals("Unlocked", (result as ActionResult.BiometricUnlockOutcome).outcome)
    }

    @Test
    fun `ActionResult BiometricUnlockOutcome PromptForDuressPin deserialization`() {
        val input = """{"BiometricUnlockOutcome": {"outcome": "PromptForDuressPin"}}"""
        val result = json.decodeFromString<ActionResult>(input)
        assertTrue(result is ActionResult.BiometricUnlockOutcome)
        assertEquals(
            "PromptForDuressPin",
            (result as ActionResult.BiometricUnlockOutcome).outcome,
        )
    }

    @Test
    fun `CommandDTO BleStartAdvertising deserialization`() {
        val input = """{"BleStartAdvertising": {"service_uuid": "1234-abcd", "payload": [1, 2, 3]}}"""
        val result = json.decodeFromString(CommandDTOSerializer, input)
        assertTrue(result is CommandDTO.BleStartAdvertising)
        assertEquals("1234-abcd", (result as CommandDTO.BleStartAdvertising).serviceUuid)
        assertEquals(listOf(1, 2, 3), result.payload)
    }

    @Test
    fun `CommandDTO NfcActivate deserialization`() {
        val input = """{"NfcActivate": {"payload": [170]}}"""
        val result = json.decodeFromString(CommandDTOSerializer, input)
        assertTrue(result is CommandDTO.NfcActivate)
        assertEquals(listOf(170), (result as CommandDTO.NfcActivate).payload)
    }

    @Test
    fun `CommandDTO NfcSendApdu deserialization`() {
        // Core emits Command::NfcSendApdu { data } in handshake phases 2/3
        // (vauchi-app/src/ui/exchange/nfc.rs). Without this arm the responder's
        // continuation APDUs decode to Unknown and the flow wedges after the tap.
        val input = """{"NfcSendApdu": {"data": [0, 255, 16]}}"""
        val result = json.decodeFromString(CommandDTOSerializer, input)
        assertTrue(result is CommandDTO.NfcSendApdu)
        assertEquals(listOf(0, 255, 16), (result as CommandDTO.NfcSendApdu).data)
    }

    @Test
    fun `CommandDTO BleWriteCharacteristic deserialization`() {
        val input = """{"BleWriteCharacteristic": {"uuid": "char-uuid", "data": [255, 0]}}"""
        val result = json.decodeFromString(CommandDTOSerializer, input)
        assertTrue(result is CommandDTO.BleWriteCharacteristic)
        assertEquals("char-uuid", (result as CommandDTO.BleWriteCharacteristic).uuid)
    }

    @Test
    fun `CommandDTO SetScreenBrightness deserialization with value`() {
        val input = """{"SetScreenBrightness": {"level": 0.65}}"""
        val result = json.decodeFromString(CommandDTOSerializer, input)
        assertTrue(result is CommandDTO.SetScreenBrightness)
        assertEquals(0.65f, (result as CommandDTO.SetScreenBrightness).level)
    }

    @Test
    fun `CommandDTO SetScreenBrightness deserialization with null level`() {
        // Core emits SetScreenBrightness { level: None } -> {"level": null} to
        // restore automatic brightness (e.g. when leaving the exchange screen).
        // Regression: the null guard checked isString instead of JsonNull, so
        // Float.parseFloat("null") threw NumberFormatException and aborted the
        // whole action handler — wedging every Android exchange at "Waiting for
        // peer". Device-tested 2026-06-03 (Pixel 3a / Samsung S7).
        val input = """{"SetScreenBrightness": {"level": null}}"""
        val result = json.decodeFromString(CommandDTOSerializer, input)
        assertTrue(result is CommandDTO.SetScreenBrightness)
        assertEquals(null, (result as CommandDTO.SetScreenBrightness).level)
    }

    @Test
    fun `CommandDTO unknown variant carries its wire name`() {
        // Silent-failure umbrella: a command this build can't decode must
        // be reportable as HardwareUnavailable(<variant>) instead of being
        // dropped — the name is the report payload
        // (2026-06-11-silent-failure-mode-umbrella).
        val objectForm = """{"SomeFutureCommand": {"x": 1}}"""
        val result = json.decodeFromString(CommandDTOSerializer, objectForm)
        assertTrue(result is CommandDTO.Unknown)
        assertEquals("SomeFutureCommand", (result as CommandDTO.Unknown).variantName)

        val unitForm = "\"SomeFutureUnitCommand\""
        val unitResult = json.decodeFromString(CommandDTOSerializer, unitForm)
        assertTrue(unitResult is CommandDTO.Unknown)
        assertEquals("SomeFutureUnitCommand", (unitResult as CommandDTO.Unknown).variantName)
    }

    @Test
    fun `CommandDTO FilePickFromUser deserialization with known purpose`() {
        // Core emits this from `link_choice`'s restore_backup action
        // (onboarding) and the lost-device replacement flow. Regression:
        // the variant was missing entirely, so "Restore from backup" was
        // a silent no-op on Android — see
        // 2026-06-11-android-restore-paths-all-dead.
        val input =
            """{"FilePickFromUser": {"accepted_mime_types": ["application/octet-stream", "text/plain"], "purpose": "ImportBackup"}}"""
        val result = json.decodeFromString(CommandDTOSerializer, input)
        assertTrue(result is CommandDTO.FilePickFromUser)
        val cmd = result as CommandDTO.FilePickFromUser
        assertEquals(listOf("application/octet-stream", "text/plain"), cmd.acceptedMimeTypes)
        assertEquals("ImportBackup", cmd.purpose)
    }

    @Test
    fun `CommandDTO FilePickFromUser deserialization with Other purpose`() {
        val input =
            """{"FilePickFromUser": {"accepted_mime_types": [], "purpose": {"Other": {"label_key": "import.key_bundle"}}}}"""
        val result = json.decodeFromString(CommandDTOSerializer, input)
        assertTrue(result is CommandDTO.FilePickFromUser)
        val cmd = result as CommandDTO.FilePickFromUser
        assertEquals(emptyList<String>(), cmd.acceptedMimeTypes)
        assertEquals("import.key_bundle", cmd.purpose)
    }

    @Test
    fun `CommandDTO Celebrate deserialization`() {
        val input =
            """{"Celebrate": {"haptic": "success", "sound": "none", "animation": "checkmark"}}"""
        val result = json.decodeFromString(CommandDTOSerializer, input)
        assertTrue(result is CommandDTO.Celebrate)
        val cmd = result as CommandDTO.Celebrate
        assertEquals("success", cmd.haptic)
        assertEquals("none", cmd.sound)
        assertEquals("checkmark", cmd.animation)
    }

    // ── Full round-trip ─────────────────────────────────────────────

    @Test
    fun `full ScreenModel deserialization matching core output`() {
        val input =
            """
            {
                "screen_id": "welcome",
                "title": "Welcome to Vauchi",
                "subtitle": "Your contacts, your rules.",
                "components": [
                    {"Text": {"id": "hero", "content": "Privacy-first contact sharing", "style": "Title"}},
                    {"InfoPanel": {"id": "features", "icon": "shield", "title": "Why Vauchi?", "items": [
                        {"icon": "lock", "title": "End-to-end encrypted", "detail": "Your data stays yours"},
                        {"icon": "visibility_off", "title": "No central database", "detail": "Decentralized by design"}
                    ]}}
                ],
                "actions": [
                    {"id": "get_started", "label": "Get Started", "style": "Primary", "enabled": true},
                    {"id": "restore", "label": "I have a backup", "style": "Secondary", "enabled": true}
                ],
                "progress": {"current_step": 1, "total_steps": 9, "label": null}
            }
            """.trimIndent()

        val screen = json.decodeFromString<ScreenModel>(input)
        assertEquals("welcome", screen.screenId)
        assertEquals("Welcome to Vauchi", screen.title)
        assertEquals("Your contacts, your rules.", screen.subtitle)
        assertEquals(2, screen.components.size)
        assertTrue(screen.components[0] is Component.Text)
        assertTrue(screen.components[1] is Component.InfoPanel)
        assertEquals(2, screen.actions.size)
        assertEquals("get_started", screen.actions[0].id)
        assertEquals(ActionStyle.Primary, screen.actions[0].style)
        assertEquals(1, screen.progress!!.currentStep)
        assertNull(screen.progress!!.label)
    }

    // ── Resilient decoding (unknown variants) ──────────────────────

    @Test
    fun `unknown primitive Component decodes as Unknown`() {
        val input = """"FutureWidget""""
        val component = json.decodeFromString<Component>(input)
        assertTrue(component is Component.Unknown)
    }

    @Test
    fun `unknown object Component decodes as Unknown`() {
        val input = """{"Chart": {"id": "c1", "data": [1, 2, 3]}}"""
        val component = json.decodeFromString<Component>(input)
        assertTrue(component is Component.Unknown)
    }

    @Test
    fun `ScreenModel with unknown component decodes gracefully`() {
        val input =
            """
            {
                "screen_id": "test",
                "title": "Test",
                "components": [
                    {"Text": {"id": "t1", "content": "Hello", "style": "Body"}},
                    {"FutureWidget": {"id": "fw1"}},
                    "Divider"
                ],
                "actions": []
            }
            """.trimIndent()

        val screen = json.decodeFromString<ScreenModel>(input)
        assertEquals(3, screen.components.size)
        assertTrue(screen.components[0] is Component.Text)
        assertTrue(screen.components[1] is Component.Unknown)
        assertTrue(screen.components[2] is Component.Divider)
    }

    @Test
    fun `unknown UiFieldVisibility primitive throws SerializationException`() {
        // Unknown variants must surface as decode errors so the screen
        // pipeline can flag "frontend out of date" instead of silently
        // showing fields that core wanted hidden (security-relevant).
        val input = """"Redacted""""
        val ex =
            assertThrows(SerializationException::class.java) {
                json.decodeFromString<UiFieldVisibility>(input)
            }
        assertTrue(
            "exception message should name the unknown variant: ${ex.message}",
            ex.message?.contains("Redacted") == true,
        )
    }

    @Test
    fun `unknown UiFieldVisibility object throws SerializationException`() {
        val input = """{"Conditional": {"rule": "age > 18"}}"""
        val ex =
            assertThrows(SerializationException::class.java) {
                json.decodeFromString<UiFieldVisibility>(input)
            }
        assertTrue(
            "exception message should name the unknown variant: ${ex.message}",
            ex.message?.contains("Conditional") == true,
        )
    }

    @Test
    fun `unknown SettingsItemKind object decodes as Unknown`() {
        val input = """{"Slider": {"min": 0, "max": 100}}"""
        val kind = json.decodeFromString<SettingsItemKind>(input)
        assertTrue(kind is SettingsItemKind.Unknown)
    }

    @Test
    fun `unknown SettingsItemKind primitive decodes as Unknown`() {
        val input = """"Separator""""
        val kind = json.decodeFromString<SettingsItemKind>(input)
        assertTrue(kind is SettingsItemKind.Unknown)
    }

    @Test
    fun `unknown UserAction object decodes as Unknown`() {
        val input = """{"FutureAction": {"widget_id": "x"}}"""
        val action = json.decodeFromString<UserAction>(input)
        assertTrue(action is UserAction.Unknown)
    }

    @Test
    fun `unknown UserAction primitive decodes as Unknown`() {
        val input = """"Heartbeat""""
        val action = json.decodeFromString<UserAction>(input)
        assertTrue(action is UserAction.Unknown)
    }

    @Test
    fun `unknown ActionResult variant decodes as Unknown`() {
        val input = """{"FutureResult": {"data": "x"}}"""
        val result = json.decodeFromString<ActionResult>(input)
        assertTrue(result is ActionResult.Unknown)
    }

    // ── Dropdown ────────────────────────────────────────────────────

    @Test
    fun `deserialize Dropdown component with selection`() {
        val input = """{"Dropdown":{"id":"theme","label":"Theme","selected":"dark","options":[{"id":"dark","label":"Dark"},{"id":"light","label":"Light"}]}}"""
        val component = json.decodeFromString<Component>(input)
        assertTrue(component is Component.Dropdown)
        val dropdown = component as Component.Dropdown
        assertEquals("theme", dropdown.id)
        assertEquals("Theme", dropdown.label)
        assertEquals("dark", dropdown.selected)
        assertEquals(2, dropdown.options.size)
        assertEquals("dark", dropdown.options[0].id)
        assertEquals("Dark", dropdown.options[0].label)
        assertEquals("light", dropdown.options[1].id)
    }

    @Test
    fun `deserialize Dropdown component without selection`() {
        val input = """{"Dropdown":{"id":"lang","label":"Language","selected":null,"options":[{"id":"en","label":"English"}]}}"""
        val component = json.decodeFromString<Component>(input)
        assertTrue(component is Component.Dropdown)
        val dropdown = component as Component.Dropdown
        assertEquals("lang", dropdown.id)
        assertNull(dropdown.selected)
        assertEquals(1, dropdown.options.size)
    }

    // ── ShowFormDialog / PreviewAs ──────────────────────────────────

    @Test
    fun `deserialize ShowFormDialog result`() {
        val input = """{"ShowFormDialog":{"dialog_type":"create_group","context_id":"grp-1"}}"""
        val result = json.decodeFromString<ActionResult>(input)
        assertTrue(result is ActionResult.ShowFormDialog)
        val dialog = result as ActionResult.ShowFormDialog
        assertEquals("create_group", dialog.dialogType)
        assertEquals("grp-1", dialog.contextId)
    }

    @Test
    fun `deserialize ShowFormDialog result with null contextId`() {
        val input = """{"ShowFormDialog":{"dialog_type":"create_group","context_id":null}}"""
        val result = json.decodeFromString<ActionResult>(input)
        assertTrue(result is ActionResult.ShowFormDialog)
        assertNull((result as ActionResult.ShowFormDialog).contextId)
    }

    @Test
    fun `deserialize PreviewAs result`() {
        val input = """{"PreviewAs":{"contact_id":"c42"}}"""
        val result = json.decodeFromString<ActionResult>(input)
        assertTrue(result is ActionResult.PreviewAs)
        assertEquals("c42", (result as ActionResult.PreviewAs).contactId)
    }

    // ── Item + ListItemAction wire format (core!637 + Wire Humble G2) ─

    @Test
    fun `deserialize Item with actions`() {
        // Wire Humble G2 (core 0.41.0) retired `searchable_fields` from the
        // wire — it was an engine input that leaked through the boundary.
        val input =
            """
            {
                "id": "c1",
                "name": "Alice",
                "subtitle": "alice@example.org",
                "initials": "A",
                "status": null,
                "actions": [
                    {"id": "archive", "label": "Archive", "kind": "archive", "destructive": false},
                    {"id": "delete", "label": "Delete", "kind": "delete", "destructive": true}
                ]
            }
            """.trimIndent()
        val item = json.decodeFromString<Item>(input)
        assertEquals("c1", item.id)
        assertEquals("A", item.avatarInitials)
        assertEquals(2, item.actions.size)
        assertEquals("archive", item.actions[0].id)
        assertEquals(ListItemActionKind.Archive, item.actions[0].kind)
        assertEquals(false, item.actions[0].destructive)
        assertEquals(ListItemActionKind.Delete, item.actions[1].kind)
        assertEquals(true, item.actions[1].destructive)
    }

    @Test
    fun `deserialize legacy Item without new fields`() {
        // Fixtures written before core!637 omit `actions`. Decoding must
        // still succeed — the data class provides an empty default.
        val input = """{"id":"c1","name":"Bob","initials":"B"}"""
        val item = json.decodeFromString<Item>(input)
        assertEquals("c1", item.id)
        assertTrue(item.actions.isEmpty())
    }

    @Test
    fun `deserialize ListItemActionKind unknown falls back`() {
        // A newer core ships an unrecognised kind. Decoding must degrade to
        // Unknown so the UI can render a generic affordance.
        val input = """{"id":"x","label":"Future","kind":"promote_to_vip","destructive":false}"""
        val action = json.decodeFromString<ListItemAction>(input)
        assertEquals(ListItemActionKind.Unknown, action.kind)
    }

    @Test
    fun `serialize ListItemAction user action round-trips`() {
        val action =
            UserAction.ListItemAction(
                componentId = "contacts",
                itemId = "c1",
                actionId = "archive",
            )
        val out = json.encodeToString(UserActionSerializer, action)
        assertEquals(
            """{"ListItemAction":{"component_id":"contacts","item_id":"c1","action_id":"archive"}}""",
            out,
        )

        val back = json.decodeFromString(UserActionSerializer, out)
        assertTrue(back is UserAction.ListItemAction)
        back as UserAction.ListItemAction
        assertEquals("contacts", back.componentId)
        assertEquals("c1", back.itemId)
        assertEquals("archive", back.actionId)
    }

    // ── Indicator (core 0.51.21 / core!990) ─────────────────────────

    @Test
    fun `deserialize Indicator component with action_id (tappable)`() {
        val input =
            """
            {
                "Indicator": {
                    "id": "sync",
                    "label": "Synced 15:47",
                    "kind": "Active",
                    "action_id": "sync_now"
                }
            }
            """.trimIndent()

        val component = json.decodeFromString<Component>(input)
        assertTrue(component is Component.Indicator)
        val indicator = component as Component.Indicator
        assertEquals("sync", indicator.id)
        assertEquals("Synced 15:47", indicator.label)
        assertEquals(IndicatorKind.Active, indicator.kind)
        assertEquals("sync_now", indicator.actionId)
        assertNull(indicator.a11y)
    }

    @Test
    fun `deserialize Indicator component without action_id (display-only)`() {
        val input =
            """
            {
                "Indicator": {
                    "id": "online",
                    "label": "Offline",
                    "kind": "Error"
                }
            }
            """.trimIndent()

        val component = json.decodeFromString<Component>(input)
        assertTrue(component is Component.Indicator)
        val indicator = component as Component.Indicator
        assertEquals("online", indicator.id)
        assertEquals("Offline", indicator.label)
        assertEquals(IndicatorKind.Error, indicator.kind)
        assertNull(indicator.actionId)
    }

    @Test
    fun `deserialize Indicator component covers all kinds`() {
        listOf(
            "Active" to IndicatorKind.Active,
            "Error" to IndicatorKind.Error,
            "Neutral" to IndicatorKind.Neutral,
            "Busy" to IndicatorKind.Busy,
        ).forEach { (wire, expected) ->
            val input = """{"Indicator":{"id":"x","label":"L","kind":"$wire"}}"""
            val component = json.decodeFromString<Component>(input)
            assertTrue(component is Component.Indicator)
            assertEquals(expected, (component as Component.Indicator).kind)
        }
    }

    @Test
    fun `deserialize Indicator component with a11y`() {
        val input =
            """
            {
                "Indicator": {
                    "id": "backup",
                    "label": "Backup overdue",
                    "kind": "Error",
                    "action_id": "open_backup",
                    "a11y": {"label": "Backup overdue", "hint": "Tap to open backup settings"}
                }
            }
            """.trimIndent()

        val component = json.decodeFromString<Component>(input)
        assertTrue(component is Component.Indicator)
        val indicator = component as Component.Indicator
        assertEquals("Backup overdue", indicator.a11y?.label)
        assertEquals("Tap to open backup settings", indicator.a11y?.hint)
    }

    // ── SectionedActionList (core 0.51.21 / core!990) ───────────────

    @Test
    fun `deserialize SectionedActionList component`() {
        val input =
            """
            {
                "SectionedActionList": {
                    "id": "more",
                    "sections": [
                        {
                            "id": "primary",
                            "label": "Primary",
                            "items": [
                                {"id": "settings", "label": "Settings", "icon": "settings"},
                                {"id": "profile", "label": "Profile", "detail": "Alice"}
                            ]
                        },
                        {
                            "id": "data",
                            "label": "Data",
                            "items": [
                                {"id": "backup", "label": "Backup"}
                            ]
                        }
                    ]
                }
            }
            """.trimIndent()

        val component = json.decodeFromString<Component>(input)
        assertTrue(component is Component.SectionedActionList)
        val list = component as Component.SectionedActionList
        assertEquals("more", list.id)
        assertEquals(2, list.sections.size)

        val primary = list.sections[0]
        assertEquals("primary", primary.id)
        assertEquals("Primary", primary.label)
        assertEquals(2, primary.items.size)
        assertEquals("settings", primary.items[0].id)
        assertEquals("Settings", primary.items[0].label)
        assertEquals("settings", primary.items[0].icon)
        assertEquals("profile", primary.items[1].id)
        assertEquals("Alice", primary.items[1].detail)

        val data = list.sections[1]
        assertEquals("data", data.id)
        assertEquals(1, data.items.size)
        assertEquals("backup", data.items[0].id)
    }

    @Test
    fun `deserialize SectionedActionList with empty sections`() {
        val input = """{"SectionedActionList":{"id":"empty","sections":[]}}"""
        val component = json.decodeFromString<Component>(input)
        assertTrue(component is Component.SectionedActionList)
        assertTrue((component as Component.SectionedActionList).sections.isEmpty())
    }

    // ── Roundtrip (serialize + deserialize) ─────────────────────────

    @Test
    fun `Indicator roundtrip preserves all fields`() {
        val original =
            Component.Indicator(
                id = "sync",
                label = "Synced",
                kind = IndicatorKind.Active,
                actionId = "sync_now",
                a11y = A11y(label = "Sync status", hint = "Tap to sync"),
            )
        val encoded = json.encodeToString(Component.serializer(), original)
        val decoded = json.decodeFromString(Component.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `Indicator roundtrip with null action_id omits the field`() {
        // skip_serializing_if = "Option::is_none" on the Rust side means
        // display-only indicators must serialize without the key.
        val original =
            Component.Indicator(
                id = "online",
                label = "Offline",
                kind = IndicatorKind.Error,
            )
        val encoded = json.encodeToString(Component.serializer(), original)
        assertTrue(
            "action_id should be omitted when null, got: $encoded",
            !encoded.contains("action_id"),
        )
        val decoded = json.decodeFromString(Component.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `SectionedActionList roundtrip preserves sections + items`() {
        val original =
            Component.SectionedActionList(
                id = "more",
                sections =
                    listOf(
                        Section(
                            id = "primary",
                            label = "Primary",
                            items =
                                listOf(
                                    ActionListItem(id = "settings", label = "Settings", icon = "settings"),
                                ),
                        ),
                        Section(id = "data", label = "Data", items = emptyList()),
                    ),
            )
        val encoded = json.encodeToString(Component.serializer(), original)
        val decoded = json.decodeFromString(Component.serializer(), encoded)
        assertEquals(original, decoded)
    }

    // ── ADR-044 Amendment 2a wire variants ──────────────────────────

    @Test
    fun `serialize NavigateBack as bare string`() {
        val action = UserAction.NavigateBack
        val serialized = json.encodeToString(UserAction.serializer(), action)
        assertEquals("\"NavigateBack\"", serialized)
    }

    @Test
    fun `deserialize NavigateBack from bare string`() {
        val result = json.decodeFromString<UserAction>("\"NavigateBack\"")
        assertTrue(result is UserAction.NavigateBack)
    }

    @Test
    fun `serialize AppForegrounded as bare string`() {
        val action = UserAction.AppForegrounded
        val serialized = json.encodeToString(UserAction.serializer(), action)
        assertEquals("\"AppForegrounded\"", serialized)
    }

    @Test
    fun `deserialize AppForegrounded from bare string`() {
        val result = json.decodeFromString<UserAction>("\"AppForegrounded\"")
        assertTrue(result is UserAction.AppForegrounded)
    }

    @Test
    fun `deserialize PerformNativeBack result`() {
        val result = json.decodeFromString<ActionResult>("\"PerformNativeBack\"")
        assertTrue(result is ActionResult.PerformNativeBack)
    }

    @Test
    fun `deserialize ScreenModel with nav_actions and nav_tab_id`() {
        val input =
            """
            {
                "screen_id": "contacts",
                "title": "Contacts",
                "components": [],
                "actions": [
                    {"id": "add", "label": "Add", "style": "Primary", "enabled": true}
                ],
                "nav_tab_id": "contacts",
                "nav_actions": [
                    {"id": "back", "label": "Back", "style": "Secondary", "enabled": true}
                ]
            }
            """.trimIndent()

        val screen = json.decodeFromString<ScreenModel>(input)
        assertEquals("contacts", screen.navTabId)
        assertEquals(1, screen.navActions.size)
        assertEquals("back", screen.navActions[0].id)
        assertEquals("Back", screen.navActions[0].label)
    }
}
