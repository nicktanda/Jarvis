package com.adam.app.core

import android.content.Context
import android.os.PowerManager

class WakeLockManager(context: Context) {

    companion object {
        // 30-minute timeout — re-acquire periodically rather than holding indefinitely.
        // If the service gets into a bad state, the CPU can eventually sleep.
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    fun acquire() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "adam:core_wake_lock"
            )
        }
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    fun release() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
}
