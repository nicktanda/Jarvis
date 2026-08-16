package com.jarvis.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSTTClient(private val context: Context) {

    companion object {
        private const val TAG = "AndroidSTT"
        private val END_PHRASES = listOf(
            "end message", "send it", "send message", "that's it",
            "that's all", "done", "finish", "finished", "send that"
        )

        /**
         * If the text contains an end phrase, returns the text before it (trimmed).
         * Returns null if no end phrase is found.
         */
        fun stripEndPhrase(text: String): String? {
            val lower = text.lowercase()
            for (phrase in END_PHRASES) {
                val idx = lower.indexOf(phrase)
                if (idx != -1) {
                    return text.substring(0, idx).trim()
                }
            }
            return null
        }
    }

    private var activeRecognizer: SpeechRecognizer? = null
    private var activeDeferred: CompletableDeferred<Result<String>>? = null

    /**
     * Cancel any active recognition session, freeing the mic for a new one.
     * Must be called on Main thread.
     */
    fun cancel() {
        activeRecognizer?.let {
            try { it.cancel(); it.destroy() } catch (_: Exception) {}
        }
        activeRecognizer = null
        activeDeferred?.complete(Result.failure(Exception("Cancelled")))
        activeDeferred = null
    }

    suspend fun transcribe(): Result<String> {
        return withContext(Dispatchers.Main) {
            // Cancel any previous recognizer to avoid RECOGNIZER_BUSY
            cancel()

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e(TAG, "Speech recognition not available on this device")
                return@withContext Result.failure(Exception("Speech recognition not available"))
            }

            val deferred = CompletableDeferred<Result<String>>()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            activeDeferred = deferred
            activeRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        Log.d(TAG, "Transcription: $text")
                        deferred.complete(Result.success(text))
                    } else {
                        deferred.complete(Result.failure(Exception("No transcription result")))
                    }
                    activeRecognizer = null
                    activeDeferred = null
                    recognizer.destroy()
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                        else -> "Unknown error ($error)"
                    }
                    Log.e(TAG, "Recognition error: $errorMsg")
                    deferred.complete(Result.failure(Exception(errorMsg)))
                    activeRecognizer = null
                    activeDeferred = null
                    recognizer.destroy()
                }

                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Ready for speech")
                }
                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech started")
                }
                override fun onEndOfSpeech() {
                    Log.d(TAG, "Speech ended")
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                // Wait longer after pauses so users can dictate longer messages
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
            }

            recognizer.startListening(intent)
            deferred.await()
        }
    }

    /**
     * Dictation mode: listens across multiple recognition sessions, accumulating text.
     * Only stops when the user says an end phrase like "end message" or "send it".
     * Falls back to ending after 30 seconds of total silence as a safety valve.
     */
    suspend fun transcribeDictation(): Result<String> {
        val parts = mutableListOf<String>()
        var totalSilenceMs = 0L
        val maxSilenceMs = 30_000L // 30 seconds total silence before auto-ending

        while (true) {
            val result = transcribe()
            val text = result.getOrNull()

            if (text.isNullOrBlank()) {
                // Each silence timeout is roughly 3-5 seconds
                totalSilenceMs += 5000L
                if (totalSilenceMs >= maxSilenceMs) {
                    Log.d(TAG, "Dictation ended: silence timeout")
                    break
                }
                Log.d(TAG, "Dictation: silence, still listening...")
                continue
            }

            totalSilenceMs = 0L

            // Check if this chunk contains an end phrase
            val stripped = stripEndPhrase(text)
            if (stripped != null) {
                if (stripped.isNotBlank()) {
                    parts.add(stripped)
                }
                break
            }

            parts.add(text)
            Log.d(TAG, "Dictation chunk: $text")
        }

        return if (parts.isEmpty()) {
            Result.failure(Exception("No message dictated"))
        } else {
            val fullMessage = parts.joinToString(" ")
            Log.d(TAG, "Full dictation: $fullMessage")
            Result.success(fullMessage)
        }
    }
}
