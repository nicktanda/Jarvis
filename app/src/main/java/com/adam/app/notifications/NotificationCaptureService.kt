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
                Log.w(TAG, "No listener instance, using cached notifications (${activeNotifications.size})")
                return activeNotifications.values.mapNotNull { sbn ->
                    sbnToNotificationData(sbn, null)
                }
            }

            return try {
                val all = service.activeNotifications ?: emptyArray()
                Log.d(TAG, "System has ${all.size} active notifications")
                all.forEach { sbn ->
                    val extras = sbn.notification.extras
                    Log.d(TAG, "  ${sbn.packageName}: title='${extras.getCharSequence(Notification.EXTRA_TITLE)}' text='${extras.getCharSequence(Notification.EXTRA_TEXT)}' ongoing=${sbn.isOngoing}")
                }
                all.filter { sbn ->
                        sbn.packageName != service.packageName && !sbn.isOngoing
                    }
                    .mapNotNull { sbn -> sbnToNotificationData(sbn, service) }
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
                    sbn.packageName.substringAfterLast('.')
                }
            } else {
                sbn.packageName.substringAfterLast('.')
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
            packageName.substringAfterLast('.')
        }
    }
}
