package com.adam.app.notifications

data class NotificationData(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val hasReply: Boolean,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toSpokenText(): String {
        return if (title.isNotBlank() && text.isNotBlank()) {
            "$title. $text"
        } else {
            title.ifBlank { text }
        }
    }

    fun toAnnouncement(): String {
        return "Notification from $appName. Would you like to hear it?"
    }
}
