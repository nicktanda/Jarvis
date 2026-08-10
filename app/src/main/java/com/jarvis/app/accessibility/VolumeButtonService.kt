package com.jarvis.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.jarvis.app.core.ButtonEvent
import com.jarvis.app.core.ServiceBridge

class VolumeButtonService : AccessibilityService() {

    private var volumeUpDownTime = 0L
    private var volumeDownDownTime = 0L
    private var volumeUpHandled = false
    private var volumeDownHandled = false

    companion object {
        private const val TAG = "VolumeButtonService"
        private const val LONG_PRESS_THRESHOLD_MS = 800L
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Let volume buttons work normally when Jarvis is idle
        if (!ServiceBridge.interceptVolumeButtons) {
            return super.onKeyEvent(event)
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            volumeUpDownTime = System.currentTimeMillis()
                            volumeUpHandled = false
                        } else if (!volumeUpHandled) {
                            val elapsed = System.currentTimeMillis() - volumeUpDownTime
                            if (elapsed >= LONG_PRESS_THRESHOLD_MS) {
                                volumeUpHandled = true
                                Log.d(TAG, "Long press volume up")
                                ServiceBridge.sendButtonEvent(this, ButtonEvent.LONG_PRESS_VOLUME_UP)
                            }
                        }
                        return true
                    }
                    KeyEvent.ACTION_UP -> {
                        if (!volumeUpHandled) {
                            Log.d(TAG, "Short press volume up")
                            ServiceBridge.sendButtonEvent(this, ButtonEvent.VOLUME_UP)
                        }
                        volumeUpDownTime = 0L
                        volumeUpHandled = false
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            volumeDownDownTime = System.currentTimeMillis()
                            volumeDownHandled = false
                        } else if (!volumeDownHandled) {
                            val elapsed = System.currentTimeMillis() - volumeDownDownTime
                            if (elapsed >= LONG_PRESS_THRESHOLD_MS) {
                                volumeDownHandled = true
                                Log.d(TAG, "Long press volume down")
                                ServiceBridge.sendButtonEvent(this, ButtonEvent.LONG_PRESS_VOLUME_DOWN)
                            }
                        }
                        return true
                    }
                    KeyEvent.ACTION_UP -> {
                        if (!volumeDownHandled) {
                            Log.d(TAG, "Short press volume down")
                            ServiceBridge.sendButtonEvent(this, ButtonEvent.VOLUME_DOWN)
                        }
                        volumeDownDownTime = 0L
                        volumeDownHandled = false
                        return true
                    }
                }
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — we only need key event filtering
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }
}
