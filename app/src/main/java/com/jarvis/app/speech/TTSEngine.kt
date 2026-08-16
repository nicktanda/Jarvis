package com.jarvis.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.LinkedList
import java.util.Locale
import java.util.UUID

class TTSEngine(context: Context) {

    private var tts: TextToSpeech? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isReady = false
    private val pendingQueue = LinkedList<SpeechItem>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentCallback: (() -> Unit)? = null
    private var isSpeaking = false

    data class SpeechItem(
        val text: String,
        val onComplete: (() -> Unit)? = null
    )

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    engine.language = Locale.getDefault()
                    engine.setSpeechRate(1.1f)

                    // Select a male voice if available
                    try {
                        val maleVoice = engine.voices?.firstOrNull { voice ->
                            voice.locale.language == Locale.getDefault().language &&
                            !voice.isNetworkConnectionRequired &&
                            voice.name.lowercase().let { name ->
                                name.contains("male") || name.contains("deep") ||
                                // Common male voice IDs across TTS engines
                                name.contains("-b-") || name.contains("-c-") ||
                                name.contains("-d-") || name.contains("#male")
                            }
                        }
                        if (maleVoice != null) {
                            engine.voice = maleVoice
                            Log.d(TAG, "Selected male voice: ${maleVoice.name}")
                        } else {
                            // Fallback: lower pitch for a more masculine tone
                            engine.setPitch(0.8f)
                            Log.d(TAG, "No male voice found, lowering pitch")
                        }
                    } catch (e: Exception) {
                        engine.setPitch(0.8f)
                        Log.d(TAG, "Voice selection failed, lowering pitch", e)
                    }

                    // Use alarm stream to always play through mute/silent
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    engine.setAudioAttributes(audioAttributes)

                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            mainHandler.post {
                                isSpeaking = false
                                val cb = currentCallback
                                currentCallback = null
                                cb?.invoke()
                                processQueue()
                            }
                        }

                        @Deprecated("Deprecated in API")
                        override fun onError(utteranceId: String?) {
                            mainHandler.post {
                                isSpeaking = false
                                val cb = currentCallback
                                currentCallback = null
                                cb?.invoke()
                                processQueue()
                            }
                        }
                    })

                    isReady = true
                    Log.d(TAG, "TTS engine initialized")
                    processQueue()
                }
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
            }
        }
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        pendingQueue.add(SpeechItem(text, onComplete))
        processQueue()
    }

    fun speakInterrupt(text: String, onComplete: (() -> Unit)? = null) {
        pendingQueue.clear()
        stop()
        pendingQueue.add(SpeechItem(text, onComplete))
        processQueue()
    }

    private fun processQueue() {
        if (!isReady || isSpeaking || pendingQueue.isEmpty()) return

        val item = pendingQueue.poll() ?: return
        isSpeaking = true
        currentCallback = item.onComplete

        // Ensure alarm volume is audible
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        if (currentVolume < maxVolume / 2) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume / 2, 0)
        }

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
        }

        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(item.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun stop() {
        tts?.stop()
        isSpeaking = false
        currentCallback = null
    }

    fun shutdown() {
        stop()
        pendingQueue.clear()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    companion object {
        private const val TAG = "TTSEngine"
    }
}
