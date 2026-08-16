package com.adam.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.adam.app.core.AdamService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed, starting AdamService")
            val prefs = context.getSharedPreferences("adam_prefs", Context.MODE_PRIVATE)
            val wasRunning = prefs.getBoolean("service_running", false)

            if (wasRunning) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AdamService::class.java)
                )
            }
        }
    }
}
