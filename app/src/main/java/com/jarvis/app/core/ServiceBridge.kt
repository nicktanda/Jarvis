package com.jarvis.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Communication hub between JarvisService, NotificationCaptureService,
 * and VolumeButtonService. Uses LocalBroadcastManager since all services
 * run in the same process.
 */
object ServiceBridge {

    @Volatile
    var interceptVolumeButtons = false

    const val ACTION_NOTIFICATION = "com.jarvis.NOTIFICATION"
    const val ACTION_BUTTON_EVENT = "com.jarvis.BUTTON_EVENT"
    const val ACTION_COMMAND = "com.jarvis.COMMAND"

    const val EXTRA_APP_NAME = "app_name"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_NOTIFICATION_KEY = "notification_key"
    const val EXTRA_PACKAGE_NAME = "package_name"
    const val EXTRA_HAS_REPLY = "has_reply"
    const val EXTRA_BUTTON_EVENT = "button_event"
    const val EXTRA_COMMAND = "command"

    fun sendNotification(
        context: Context,
        appName: String,
        title: String,
        text: String,
        notificationKey: String,
        packageName: String,
        hasReply: Boolean
    ) {
        val intent = Intent(ACTION_NOTIFICATION).apply {
            putExtra(EXTRA_APP_NAME, appName)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_NOTIFICATION_KEY, notificationKey)
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_HAS_REPLY, hasReply)
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    fun sendButtonEvent(context: Context, event: ButtonEvent) {
        val intent = Intent(ACTION_BUTTON_EVENT).apply {
            putExtra(EXTRA_BUTTON_EVENT, event.name)
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    fun sendCommand(context: Context, command: String) {
        val intent = Intent(ACTION_COMMAND).apply {
            putExtra(EXTRA_COMMAND, command)
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    fun registerReceiver(context: Context, receiver: BroadcastReceiver, vararg actions: String) {
        val filter = IntentFilter()
        actions.forEach { filter.addAction(it) }
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter)
    }

    fun unregisterReceiver(context: Context, receiver: BroadcastReceiver) {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
    }
}
