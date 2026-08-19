package com.adam.app.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.*

class WakeWordDetector(
    private val context: Context,
    private val wakeWordName: String = "adam",
    private val onWakeWordDetected: () -> Unit
) {

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val RESTART_DELAY_MS = 500L
        private const val ERROR_BACKOFF_MS = 2000L
    }

    private val nameLower = wakeWordName.lowercase().trim()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var isActive = false
    private var consecutiveErrors = 0
    private var savedMusicVolume = -1
    private var savedNotificationVolume = -1
    private var savedSystemVolume = -1
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val googleRecognizerComponent: ComponentName by lazy { findGoogleRecognizer() }

    // Audio focus request used to briefly suppress the recognizer beep
    private val beepSuppressFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    fun start() {
        if (isActive) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            return
        }

        isActive = true
        consecutiveErrors = 0
        Log.d(TAG, "Wake word detection started")
        startListening()
    }

    fun stop() {
        if (!isActive) return
        isActive = false
        scope.coroutineContext.cancelChildren()
        destroyRecognizer()
        restoreVolumes()
        audioManager.abandonAudioFocusRequest(beepSuppressFocusRequest)
        Log.d(TAG, "Wake word detection stopped")
    }

    private fun startListening() {
        if (!isActive) return

        destroyRecognizer()

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            Log.d(TAG, "Using system default recognizer")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
            restartWithBackoff()
            return
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                consecutiveErrors = 0
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val speech = matches?.firstOrNull()?.lowercase() ?: ""
                Log.d(TAG, "Heard: '$speech'")

                if (OnDeviceWakeWordDetector.matchesWakeWord(speech, nameLower)) {
                    Log.d(TAG, "Wake word detected!")
                    isActive = false
                    destroyRecognizer()
                    onWakeWordDetected()
                } else {
                    restartListening()
                }
            }

            override fun onError(error: Int) {
                val errorName = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                    SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                    SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                    SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                    SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                    else -> "UNKNOWN($error)"
                }

                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Normal — no speech detected, just restart
                        consecutiveErrors = 0
                        restartListening()
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // Another recognizer is active, back off
                        Log.d(TAG, "Recognizer busy, backing off")
                        restartWithBackoff()
                    }
                    else -> {
                        consecutiveErrors++
                        Log.w(TAG, "Recognition error: $errorName (consecutive: $consecutiveErrors)")
                        restartWithBackoff()
                    }
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Listening for wake word...")
            }
            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val speech = matches?.firstOrNull()?.lowercase() ?: ""
                if (OnDeviceWakeWordDetector.matchesWakeWord(speech, nameLower)) {
                    Log.d(TAG, "Wake word detected in partial results!")
                    isActive = false
                    destroyRecognizer()
                    onWakeWordDetected()
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        try {
            // Mute streams the recognizer beep may play on
            // (STREAM_SYSTEM breaks NothingOS recognizer, so only mute MUSIC + NOTIFICATION)
            savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            savedNotificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            savedSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
            // Set system to 1 (not 0 — zero breaks recognizer on some devices)
            val minSystem = audioManager.getStreamMinVolume(AudioManager.STREAM_SYSTEM)
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, minSystem.coerceAtLeast(1), 0)
            audioManager.requestAudioFocus(beepSuppressFocusRequest)

            recognizer?.startListening(intent)

            // Restore after the beep window
            scope.launch {
                delay(800)
                restoreVolumes()
                audioManager.abandonAudioFocusRequest(beepSuppressFocusRequest)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            restoreVolumes()
            audioManager.abandonAudioFocusRequest(beepSuppressFocusRequest)
            restartWithBackoff()
        }
    }

    private fun restartListening() {
        if (!isActive) return
        scope.launch {
            delay(RESTART_DELAY_MS)
            if (isActive) startListening()
        }
    }

    private fun restartWithBackoff() {
        if (!isActive) return
        // Cap backoff at 3 seconds to avoid long gaps where wake word isn't heard
        val backoff = ERROR_BACKOFF_MS * minOf(consecutiveErrors, 2)
        scope.launch {
            delay(backoff.coerceAtMost(3000L))
            if (isActive) startListening()
        }
    }

    private fun restoreVolumes() {
        if (savedMusicVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
            savedMusicVolume = -1
        }
        if (savedNotificationVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotificationVolume, 0)
            savedNotificationVolume = -1
        }
        if (savedSystemVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, savedSystemVolume, 0)
            savedSystemVolume = -1
        }
    }

    private fun findGoogleRecognizer(): ComponentName {
        // The Google Search app has the reliable cloud speech recognizer.
        // Other com.google.android.* packages (tts, as/AiAi) expose
        // RecognitionService but fail on many devices.
        return ComponentName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        )
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying recognizer", e)
        }
        recognizer = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }
}
