// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.ui.coreui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                        {"id": "f1", "field_type": "Phone", "label": "Mobile", "value": "+1234", "visibility": "Shown"},
                        {"id": "f2", "field_type": "Email", "label": "Work", "value": "a@b.com", "visibility": "Hidden"}
                    ],
                    "visibility_mode": "ShowHide",
                    "available_groups": ["Family", "Work"]
                }
            }
            """.trimIndent()

        val component = json.decodeFromString<Component>(input)
        val fieldList = component as Component.FieldList
        assertEquals(2, fieldList.fields.size)
        assertEquals("Phone", fieldList.fields[0].fieldType)
        assertTrue(fieldList.fields[0].visibility is UiFieldVisibility.Shown)
        assertTrue(fieldList.fields[1].visibility is UiFieldVisibility.Hidden)
        assertEquals(VisibilityMode.ShowHide, fieldList.visibilityMode)
        assertEquals(listOf("Family", "Work"), fieldList.availableGroups)
    }

    @Test
    fun `deserialize CardPreview component`() {
        val input =
            """
            {
                "CardPreview": {
                    "name": "Alice",
                    "fields": [],
                    "group_views": [
                        {
                            "group_name": "Family",
                            "display_name": "Alice",
                            "visible_fields": []
                        }
                    ],
                    "selected_group": null
                }
            }
            """.trimIndent()

        val component = json.decodeFromString<Component>(input)
        val preview = component as Component.CardPreview
        assertEquals("Alice", preview.name)
        assertEquals(1, preview.groupViews.size)
        assertEquals("Family", preview.groupViews[0].groupName)
        assertNull(preview.selectedGroup)
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
    fun `deserialize Groups visibility with group list`() {
        val input = """{"Groups":["Family","Work"]}"""
        val visibility = json.decodeFromString<UiFieldVisibility>(input)
        assertTrue(visibility is UiFieldVisibility.Groups)
        assertEquals(listOf("Family", "Work"), (visibility as UiFieldVisibility.Groups).groups)
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

    // ── UserAction serialization ────────────────────────────────────

    @Test
    fun `serialize ActionPressed`() {
        val action = UserAction.ActionPressed(actionId = "get_started")
        val serialized = json.encodeToString(UserAction.serializer(), action)
        assertEquals("""{"ActionPressed":{"action_id":"get_started"}}""", serialized)
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
    fun `serialize FieldVisibilityChanged with null group`() {
        val action = UserAction.FieldVisibilityChanged(fieldId = "f1", groupId = null, visible = true)
        val serialized = json.encodeToString(UserAction.serializer(), action)
        assertEquals("""{"FieldVisibilityChanged":{"field_id":"f1","group_id":null,"visible":true}}""", serialized)
    }

    @Test
    fun `serialize GroupViewSelected`() {
        val action = UserAction.GroupViewSelected(groupName = "Family")
        val serialized = json.encodeToString(UserAction.serializer(), action)
        assertEquals("""{"GroupViewSelected":{"group_name":"Family"}}""", serialized)
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
    fun `ActionResult ExchangeCommands deserialization`() {
        val input = """{"ExchangeCommands": {"commands": ["QrRequestScan", {"QrDisplay": {"data": "test-qr"}}]}}"""
        val result = json.decodeFromString<ActionResult>(input)
        assertTrue(result is ActionResult.ExchangeCommands)
        val cmds = (result as ActionResult.ExchangeCommands).commands
        assertEquals(2, cmds.size)
        assertTrue(cmds[0] is ExchangeCommandDTO.QrRequestScan)
        assertTrue(cmds[1] is ExchangeCommandDTO.QrDisplay)
        assertEquals("test-qr", (cmds[1] as ExchangeCommandDTO.QrDisplay).data)
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
    fun `ExchangeCommandDTO BleStartAdvertising deserialization`() {
        val input = """{"BleStartAdvertising": {"service_uuid": "1234-abcd", "payload": [1, 2, 3]}}"""
        val result = json.decodeFromString(ExchangeCommandDTOSerializer, input)
        assertTrue(result is ExchangeCommandDTO.BleStartAdvertising)
        assertEquals("1234-abcd", (result as ExchangeCommandDTO.BleStartAdvertising).serviceUuid)
        assertEquals(listOf(1, 2, 3), result.payload)
    }

    @Test
    fun `ExchangeCommandDTO NfcActivate deserialization`() {
        val input = """{"NfcActivate": {"payload": [170]}}"""
        val result = json.decodeFromString(ExchangeCommandDTOSerializer, input)
        assertTrue(result is ExchangeCommandDTO.NfcActivate)
        assertEquals(listOf(170), (result as ExchangeCommandDTO.NfcActivate).payload)
    }

    @Test
    fun `ExchangeCommandDTO BleWriteCharacteristic deserialization`() {
        val input = """{"BleWriteCharacteristic": {"uuid": "char-uuid", "data": [255, 0]}}"""
        val result = json.decodeFromString(ExchangeCommandDTOSerializer, input)
        assertTrue(result is ExchangeCommandDTO.BleWriteCharacteristic)
        assertEquals("char-uuid", (result as ExchangeCommandDTO.BleWriteCharacteristic).uuid)
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
}
