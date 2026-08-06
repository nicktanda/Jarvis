package com.jarvis.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.app.core.JarvisService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed, starting JarvisService")
            val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            val wasRunning = prefs.getBoolean("service_running", false)

            if (wasRunning) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, JarvisService::class.java)
                )
            }
        }
    }
}
