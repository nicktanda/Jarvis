package com.jarvis.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.LinkedList
import java.util.Locale
import java.util.UUID

class TTSEngine(context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private val pendingQueue = LinkedList<SpeechItem>()
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

                    // Force output to phone speaker
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    engine.setAudioAttributes(audioAttributes)

                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            isSpeaking = false
                            currentCallback?.invoke()
                            currentCallback = null
                            processQueue()
                        }

                        @Deprecated("Deprecated in API")
                        override fun onError(utteranceId: String?) {
                            isSpeaking = false
                            currentCallback?.invoke()
                            currentCallback = null
                            processQueue()
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

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
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
