package com.jarvis.app.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jarvis.app.core.ServiceBridge

class NotificationCaptureService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationCapture"
        // Store active notifications so we can reply to them later
        private val activeNotifications = java.util.concurrent.ConcurrentHashMap<String, StatusBarNotification>()

        fun getNotification(key: String): StatusBarNotification? = activeNotifications[key]
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Skip our own persistent notification
        if (sbn.packageName == packageName) return

        // Skip ongoing notifications (music players, etc.)
        if (sbn.isOngoing) return

        // Skip group summaries
        val extras = sbn.notification.extras
        if (extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) {
            // Still process group messages
        }

        val appName = getAppName(sbn.packageName)
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
        val nKey = sbn.key
        Companion.activeNotifications[nKey] = sbn

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
        val nKey = sbn.key
        Companion.activeNotifications.remove(nKey)
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
