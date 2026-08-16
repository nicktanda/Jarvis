package com.adam.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AdamApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(serviceChannel)

        // Delete and recreate to pick up importance changes
        manager.deleteNotificationChannel(UPDATE_CHANNEL_ID)
        val updateChannel = NotificationChannel(
            UPDATE_CHANNEL_ID,
            "Updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Adam app update notifications"
            enableVibration(true)
            enableLights(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(updateChannel)
    }

    companion object {
        const val CHANNEL_ID = "adam_service"
        const val UPDATE_CHANNEL_ID = "adam_updates"
    }
}
