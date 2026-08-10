package com.jarvis.app.speech

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.*

class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val RESTART_DELAY_MS = 300L
        private const val ERROR_BACKOFF_MS = 2000L
        private val WAKE_WORDS = listOf("jarvis", "hey jarvis", "hey travis", "hey jarvy", "hey service")
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var isActive = false
    private var consecutiveErrors = 0
    private var savedMusicVolume = -1
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
        // Restore music volume if we muted it for the beep
        if (savedMusicVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
            savedMusicVolume = -1
        }
        Log.d(TAG, "Wake word detection stopped")
    }

    private fun startListening() {
        if (!isActive) return

        destroyRecognizer()

        try {
            recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                Log.d(TAG, "Using on-device recognizer")
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
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

                if (WAKE_WORDS.any { speech.contains(it) }) {
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
                if (WAKE_WORDS.any { speech.contains(it) }) {
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
            // Extend listening window
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
        }

        try {
            // Mute the beep sound that SpeechRecognizer plays on start
            savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

            recognizer?.startListening(intent)

            // Restore music volume after a short delay (beep is brief)
            scope.launch {
                delay(500)
                if (savedMusicVolume >= 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
                    savedMusicVolume = -1
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            if (savedMusicVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
                savedMusicVolume = -1
            }
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
        val backoff = ERROR_BACKOFF_MS * minOf(consecutiveErrors, 5)
        scope.launch {
            delay(backoff)
            if (isActive) startListening()
        }
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
