// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import androidx.core.app.NotificationCompat
import app.vauchi.ui.coreui.WakeupOutcome
import app.vauchi.ui.coreui.toPresentation
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.vauchi_platform.MobileNotificationPriority

/**
 * Core prepares every OS-facing notification value (ADR-066): the shell
 * consumes the opaque `os_channel_id` and the closed priority enum and never
 * reads a notification category. These pin that consumption.
 */
class NotificationPresentationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun wakeup_notification_carries_core_channel_and_priority() {
        val outcome =
            json.decodeFromString<WakeupOutcome>(
                """
                {"notifications":[{"event_key":"evt-1","category":"EmergencyAlert",
                  "title":"Alert","body":"Body","contact_id":"c1",
                  "os_category_id":"emergency_alert","os_channel_id":"alerts",
                  "priority":"Urgent","os_category_options":["custom_dismiss_action"]}],
                 "commands":[]}
                """.trimIndent(),
            )

        assertEquals(
            NotificationPresentation(
                eventKey = "evt-1",
                title = "Alert",
                body = "Body",
                contactId = "c1",
                channelId = "alerts",
                priority = MobileNotificationPriority.URGENT,
            ),
            outcome.notifications.single().toPresentation(),
        )
    }

    @Test
    fun core_channel_ids_resolve_to_registered_android_channels() {
        assertEquals(NotificationHelper.CHANNEL_ALERTS, NotificationHelper.androidChannelIdFor("alerts"))
        assertEquals(NotificationHelper.CHANNEL_UPDATES, NotificationHelper.androidChannelIdFor("updates"))
    }

    @Test
    fun unregistered_channel_id_falls_back_to_updates_channel() {
        assertEquals(NotificationHelper.CHANNEL_UPDATES, NotificationHelper.androidChannelIdFor("duress"))
    }

    @Test
    fun priorities_map_to_notification_compat_levels() {
        assertEquals(NotificationCompat.PRIORITY_DEFAULT, NotificationHelper.priorityFor(MobileNotificationPriority.DEFAULT))
        assertEquals(NotificationCompat.PRIORITY_HIGH, NotificationHelper.priorityFor(MobileNotificationPriority.HIGH))
        assertEquals(NotificationCompat.PRIORITY_HIGH, NotificationHelper.priorityFor(MobileNotificationPriority.URGENT))
    }
}
