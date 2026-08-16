package com.jarvis.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class JarvisApplication : Application() {

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

        val updateChannel = NotificationChannel(
            UPDATE_CHANNEL_ID,
            "Updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Jarvis app update notifications"
        }
        manager.createNotificationChannel(updateChannel)
    }

    companion object {
        const val CHANNEL_ID = "jarvis_service"
        const val UPDATE_CHANNEL_ID = "jarvis_updates"
    }
}
