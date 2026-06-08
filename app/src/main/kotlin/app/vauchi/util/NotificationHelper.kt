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
import uniffi.vauchi_platform.MobileNotificationCategory
import uniffi.vauchi_platform.MobilePendingNotification

/**
 * Helper for creating and showing OS notifications.
 */
object NotificationHelper {
    private const val TAG = "NotificationHelper"
    
    const val CHANNEL_UPDATES = "vauchi_updates"
    const val CHANNEL_ALERTS = "vauchi_alerts"

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
     * Show a notification from a [MobilePendingNotification].
     */
    fun showNotification(context: Context, notification: MobilePendingNotification) {
        val notificationManager = NotificationManagerCompat.from(context)

        createNotificationChannels(context)

        val channelId = when (notification.category) {
            MobileNotificationCategory.EMERGENCY_ALERT -> CHANNEL_ALERTS
            MobileNotificationCategory.CONTACT_ADDED -> CHANNEL_UPDATES
        }

        val priority = when (notification.category) {
            MobileNotificationCategory.EMERGENCY_ALERT -> NotificationCompat.PRIORITY_HIGH
            else -> NotificationCompat.PRIORITY_DEFAULT
        }

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
            Log.d(TAG, "Notification shown: ${notification.title} (${notification.category})")
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission missing (POST_NOTIFICATIONS)", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification", e)
        }
    }
}
