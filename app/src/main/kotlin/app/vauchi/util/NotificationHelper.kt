// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.vauchi.MainActivity
import app.vauchi.R
import uniffi.vauchi_platform.MobileNotificationPriority
import uniffi.vauchi_platform.MobilePendingNotification

/**
 * One OS notification as Core prepared it (ADR-066): copy, an opaque
 * channel id, and a closed presentation priority. The shell never reads a
 * notification category.
 */
data class NotificationPresentation(
    val eventKey: String,
    val title: String,
    val body: String,
    val contactId: String,
    val channelId: String,
    val priority: MobileNotificationPriority,
)

fun MobilePendingNotification.toPresentation(): NotificationPresentation =
    NotificationPresentation(
        eventKey = eventKey,
        title = title,
        body = body,
        contactId = contactId,
        channelId = osChannelId,
        priority = priority,
    )

/**
 * Helper for creating and showing OS notifications.
 */
object NotificationHelper {
    private const val TAG = "NotificationHelper"

    const val CHANNEL_UPDATES = "vauchi_updates"
    const val CHANNEL_ALERTS = "vauchi_alerts"

    /** Android channels registered for the opaque channel ids Core emits. */
    private val registeredChannels =
        mapOf(
            "updates" to CHANNEL_UPDATES,
            "alerts" to CHANNEL_ALERTS,
        )

    fun androidChannelIdFor(coreChannelId: String): String = registeredChannels[coreChannelId] ?: CHANNEL_UPDATES

    fun priorityFor(priority: MobileNotificationPriority): Int =
        when (priority) {
            MobileNotificationPriority.DEFAULT -> NotificationCompat.PRIORITY_DEFAULT
            MobileNotificationPriority.HIGH, MobileNotificationPriority.URGENT -> NotificationCompat.PRIORITY_HIGH
        }

    /**
     * Create notification channels for Android O+.
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Channel for contact updates (Default importance)
            val updatesChannel = NotificationChannel(
                CHANNEL_UPDATES,
                context.getString(R.string.channel_updates_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_updates_desc)
            }

            // Channel for emergency alerts (High importance)
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.channel_alerts_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_alerts_desc)
                enableLights(true)
                enableVibration(true)
            }

            notificationManager.createNotificationChannels(listOf(updatesChannel, alertsChannel))
            Log.d(TAG, "Notification channels created")
        }
    }

    /**
     * Show a notification Core prepared.
     */
    fun showNotification(context: Context, notification: NotificationPresentation) {
        val notificationManager = NotificationManagerCompat.from(context)

        createNotificationChannels(context)

        val channelId = androidChannelIdFor(notification.channelId)
        val priority = priorityFor(notification.priority)

        // Tapping the notification opens MainActivity
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // Optional: navigate to contact detail screen
            putExtra("contact_id", notification.contactId)
            putExtra("event_key", notification.eventKey)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            notification.eventKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync) // TODO: Use actual vauchi icon
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            notificationManager.notify(notification.eventKey.hashCode(), builder.build())
            Log.d(TAG, "Notification shown on channel $channelId")
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission missing (POST_NOTIFICATIONS)", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification", e)
        }
    }
}
