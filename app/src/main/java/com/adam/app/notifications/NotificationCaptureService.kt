package com.adam.app.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.adam.app.core.ServiceBridge

class NotificationCaptureService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationCapture"
        // Store active notifications so we can reply to them later
        private val activeNotifications = java.util.concurrent.ConcurrentHashMap<String, StatusBarNotification>()
        private var instance: NotificationCaptureService? = null

        private val KNOWN_APPS = mapOf(
            "com.instagram.android" to "Instagram",
            "com.google.android.gm" to "Gmail",
            "com.whatsapp" to "WhatsApp",
            "com.facebook.katana" to "Facebook",
            "com.facebook.orca" to "Messenger",
            "com.twitter.android" to "X",
            "com.snapchat.android" to "Snapchat",
            "com.spotify.music" to "Spotify",
            "com.google.android.apps.messaging" to "Messages",
            "com.samsung.android.messaging" to "Messages",
            "com.microsoft.teams" to "Teams",
            "com.slack" to "Slack",
            "com.discord" to "Discord",
            "org.telegram.messenger" to "Telegram",
            "com.linkedin.android" to "LinkedIn"
        )

        fun extractAppName(packageName: String): String {
            KNOWN_APPS[packageName]?.let { return it }
            val parts = packageName.split(".")
            val skip = setOf("com", "android", "app", "apps", "org", "net", "io", "co", "google")
            val meaningful = parts.firstOrNull { it !in skip && it.length > 2 }
                ?: parts.lastOrNull() ?: packageName
            return meaningful.replaceFirstChar { it.uppercase() }
        }

        fun getNotification(key: String): StatusBarNotification? = activeNotifications[key]

        /**
         * Dismiss a notification and try to mark it as read.
         * Looks for "Mark as read" / "Read" actions before dismissing.
         */
        fun dismissAndMarkRead(key: String) {
            val service = instance ?: return
            val sbn = activeNotifications[key] ?: return

            // Try to trigger a "mark as read" action if the app provides one
            try {
                sbn.notification.actions?.firstOrNull { action ->
                    val label = action.title?.toString()?.lowercase() ?: ""
                    label.contains("mark") && label.contains("read") ||
                    label == "read" ||
                    label.contains("mark as read")
                }?.actionIntent?.send()
            } catch (e: Exception) {
                Log.d(TAG, "No mark-as-read action for ${sbn.packageName}")
            }

            // Dismiss the notification from the status bar
            try {
                service.cancelNotification(key)
                Log.d(TAG, "Dismissed notification: $key")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dismiss notification", e)
            }
        }

        fun getAllActiveNotificationData(): List<NotificationData> {
            val service = instance
            if (service == null) {
                Log.w(TAG, "No listener instance, returning empty (cached data may be stale)")
                return emptyList()
            }

            return try {
                val all = service.activeNotifications ?: emptyArray()
                Log.d(TAG, "System has ${all.size} active notifications")
                all.forEach { sbn ->
                    val extras = sbn.notification.extras
                    Log.d(TAG, "  ${sbn.packageName}: title='${extras.getCharSequence(Notification.EXTRA_TITLE)}' text='${extras.getCharSequence(Notification.EXTRA_TEXT)}' ongoing=${sbn.isOngoing}")
                }
                val filtered = all.filter { sbn ->
                    val pass = sbn.packageName != service.packageName && !sbn.isOngoing
                    Log.d(TAG, "  filter ${sbn.packageName}: pass=$pass (ours=${sbn.packageName == service.packageName}, ongoing=${sbn.isOngoing})")
                    pass
                }
                Log.d(TAG, "After filter: ${filtered.size} notifications")
                val result = filtered.mapNotNull { sbn ->
                    val data = sbnToNotificationData(sbn, service)
                    Log.d(TAG, "  map ${sbn.packageName}: ${if (data != null) "appName='${data.appName}'" else "null (filtered)"}")
                    data
                }
                Log.d(TAG, "Final result: ${result.size} notifications")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error reading active notifications", e)
                emptyList()
            }
        }

        private fun sbnToNotificationData(sbn: StatusBarNotification, service: NotificationCaptureService?): NotificationData? {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: ""

            // Try multiple text sources — apps like Instagram use different extras
            val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
                ?: run {
                    // Try extracting from messaging style (WhatsApp, Instagram DMs, etc.)
                    @Suppress("DEPRECATION")
                    val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                    messages?.lastOrNull()?.let { msg ->
                        val bundle = msg as? android.os.Bundle
                        bundle?.getCharSequence("text")?.toString()
                    }
                }
                ?: sbn.notification.tickerText?.toString()
                ?: ""

            if (title.isBlank() && text.isBlank()) return null

            val hasReply = sbn.notification.actions?.any { action ->
                action.remoteInputs?.isNotEmpty() == true
            } ?: false

            val appName = if (service != null) {
                try {
                    val appInfo = service.packageManager.getApplicationInfo(sbn.packageName, 0)
                    service.packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get app label for ${sbn.packageName}", e)
                    extractAppName(sbn.packageName)
                }
            } else {
                extractAppName(sbn.packageName)
            }

            return NotificationData(
                key = sbn.key,
                packageName = sbn.packageName,
                appName = appName,
                title = title,
                text = text,
                hasReply = hasReply,
                timestamp = sbn.postTime
            )
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "Notification listener connected")
        try {
            for (sbn in activeNotifications) {
                Companion.activeNotifications[sbn.key] = sbn
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading active notifications", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Skip our own persistent notification
        if (sbn.packageName == packageName) return

        // Skip ongoing notifications (music players, etc.)
        if (sbn.isOngoing) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""

        // Skip if both title and text are empty
        if (title.isBlank() && text.isBlank()) return

        // Check if notification has a reply action
        val hasReply = sbn.notification.actions?.any { action ->
            action.remoteInputs?.isNotEmpty() == true
        } ?: false

        // Store for later reply
        Companion.activeNotifications[sbn.key] = sbn

        val appName = getAppName(sbn.packageName)
        Log.d(TAG, "Notification: $appName - $title: $text (reply=$hasReply)")

        ServiceBridge.sendNotification(
            context = this,
            appName = appName,
            title = title,
            text = text,
            notificationKey = sbn.key,
            packageName = sbn.packageName,
            hasReply = hasReply
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Companion.activeNotifications.remove(sbn.key)
    }

    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            extractAppName(packageName)
        }
    }
}
