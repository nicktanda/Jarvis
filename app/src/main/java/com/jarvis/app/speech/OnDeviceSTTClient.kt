package com.jarvis.app.speech

import android.util.Log
import kotlinx.coroutines.*

/**
 * On-device speech-to-text using AudioPipeline + Silero VAD + whisper.cpp.
 * Drop-in replacement for AndroidSTTClient with the same public API.
 */
class OnDeviceSTTClient(
    private val audioPipeline: AudioPipeline,
    private val vadDetector: SileroVadDetector,
    private val whisperEngine: WhisperEngine
) {

    companion object {
        private const val TAG = "OnDeviceSTT"

        private val END_PHRASES = listOf(
            "end message", "send it", "send message", "that's it",
            "that's all", "done", "finish", "finished", "send that"
        )

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

        // Silence after speech thresholds
        private const val SPEECH_END_SILENCE_MS = 2000L   // 2s silence = end of utterance
        private const val MAX_LISTEN_MS = 30_000L          // 30s max listen per transcribe()
        private const val NO_SPEECH_TIMEOUT_MS = 10_000L   // 10s no speech = timeout
        private const val MIN_SPEECH_MS = 300L             // ignore segments shorter than 300ms
        private const val PRE_SPEECH_FRAMES = 10           // ~320ms pre-buffer for leading consonants
        private const val LEADOUT_MS = 600L                // ignore audio for 600ms after start to let TTS echo dissipate
    }

    @Volatile
    private var isCancelled = false

    fun cancel() {
        isCancelled = true
    }

    /**
     * Listen for a single utterance. Returns the transcription.
     * Uses VAD to detect speech start/end, then transcribes with Whisper.
     */
    suspend fun transcribe(): Result<String> = withContext(Dispatchers.IO) {
        isCancelled = false

        val audioBuffer = mutableListOf<FloatArray>()
        val preBuffer = ArrayDeque<FloatArray>(PRE_SPEECH_FRAMES)
        var isSpeaking = false
        var silenceStartMs = 0L
        var speechStartMs = 0L
        val listenStartMs = System.currentTimeMillis()
        var speechDetected = false

        val deferred = CompletableDeferred<Result<String>>()

        vadDetector.resetState()

        val listener = object : AudioPipeline.FrameListener {
            override fun onAudioFrame(samples: FloatArray) {
                if (isCancelled || deferred.isCompleted) return

                val now = System.currentTimeMillis()

                // Skip early frames to let TTS echo dissipate
                if (now - listenStartMs < LEADOUT_MS) return

                val prob = vadDetector.processFrame(samples)

                if (!isSpeaking) {
                    // Waiting for speech
                    preBuffer.addLast(samples.copyOf())
                    if (preBuffer.size > PRE_SPEECH_FRAMES) preBuffer.removeFirst()

                    if (prob >= SileroVadDetector.SPEECH_THRESHOLD) {
                        isSpeaking = true
                        speechDetected = true
                        speechStartMs = now
                        // Add pre-buffer to capture leading sounds
                        audioBuffer.addAll(preBuffer)
                        audioBuffer.add(samples.copyOf())
                        Log.d(TAG, "Speech started")
                    } else if (now - listenStartMs > NO_SPEECH_TIMEOUT_MS) {
                        deferred.complete(Result.failure(Exception("No speech detected")))
                    }
                } else {
                    // Currently speaking
                    audioBuffer.add(samples.copyOf())

                    if (prob < SileroVadDetector.SILENCE_THRESHOLD) {
                        if (silenceStartMs == 0L) silenceStartMs = now
                        if (now - silenceStartMs >= SPEECH_END_SILENCE_MS) {
                            // Speech ended
                            Log.d(TAG, "Speech ended (silence)")
                            finishTranscription(audioBuffer, speechStartMs, deferred)
                        }
                    } else {
                        silenceStartMs = 0L
                    }

                    // Max duration safety
                    if (now - listenStartMs > MAX_LISTEN_MS) {
                        Log.d(TAG, "Max listen duration reached")
                        finishTranscription(audioBuffer, speechStartMs, deferred)
                    }
                }
            }
        }

        audioPipeline.addListener(listener)

        try {
            deferred.await()
        } finally {
            audioPipeline.removeListener(listener)
        }
    }

    private fun finishTranscription(
        audioBuffer: List<FloatArray>,
        speechStartMs: Long,
        deferred: CompletableDeferred<Result<String>>
    ) {
        if (deferred.isCompleted) return

        val speechDurationMs = System.currentTimeMillis() - speechStartMs
        if (speechDurationMs < MIN_SPEECH_MS) {
            deferred.complete(Result.failure(Exception("Speech too short")))
            return
        }

        // Flatten audio buffer into single array
        val totalSamples = audioBuffer.sumOf { it.size }
        val allSamples = FloatArray(totalSamples)
        var offset = 0
        for (frame in audioBuffer) {
            frame.copyInto(allSamples, offset)
            offset += frame.size
        }

        // Transcribe async (whisper runs on IO)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val text = whisperEngine.transcribe(allSamples)
                Log.d(TAG, "Transcription: $text")
                if (text.isBlank()) {
                    deferred.complete(Result.failure(Exception("Empty transcription")))
                } else {
                    deferred.complete(Result.success(text))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                deferred.complete(Result.failure(e))
            }
        }
    }

    /**
     * Dictation mode: listens across multiple utterances, accumulating text.
     * Only stops when the user says an end phrase or after 30s total silence.
     */
    suspend fun transcribeDictation(): Result<String> {
        val parts = mutableListOf<String>()
        var totalSilenceMs = 0L
        val maxSilenceMs = 30_000L

        while (true) {
            val result = transcribe()
            val text = result.getOrNull()

            if (text.isNullOrBlank()) {
                totalSilenceMs += 5000L
                if (totalSilenceMs >= maxSilenceMs) {
                    Log.d(TAG, "Dictation ended: silence timeout")
                    break
                }
                Log.d(TAG, "Dictation: silence, still listening...")
                continue
            }

            totalSilenceMs = 0L

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
