package com.adam.app.ai

import com.adam.app.notifications.NotificationData

class ConversationContext {

    private val recentNotifications = mutableListOf<NotificationData>()
    private var lastSpokenNotification: NotificationData? = null
    private var lastSpokenIndex: Int = -1

    fun addNotification(notification: NotificationData) {
        recentNotifications.add(0, notification)
        // Keep only the last 20 notifications for context
        if (recentNotifications.size > 20) {
            recentNotifications.removeAt(recentNotifications.lastIndex)
        }
    }

    fun setLastSpoken(notification: NotificationData, index: Int) {
        lastSpokenNotification = notification
        lastSpokenIndex = index
    }

    fun getLastSpoken(): NotificationData? = lastSpokenNotification

    fun getRecentNotifications(): List<NotificationData> = recentNotifications.toList()

    fun buildContextString(): String {
        val sb = StringBuilder()

        sb.appendLine("Recent notifications:")
        recentNotifications.forEachIndexed { index, notif ->
            sb.appendLine("  [$index] From ${notif.appName}: ${notif.title} - ${notif.text}")
            if (notif.hasReply) sb.appendLine("      (supports reply)")
        }

        lastSpokenNotification?.let {
            sb.appendLine()
            sb.appendLine("Last spoken notification (index $lastSpokenIndex): From ${it.appName}: ${it.title} - ${it.text}")
        }

        return sb.toString()
    }

    fun clear() {
        recentNotifications.clear()
        lastSpokenNotification = null
        lastSpokenIndex = -1
    }
}
