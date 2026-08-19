package com.adam.app.speech

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * On-device wake word detector using AudioPipeline + Silero VAD + whisper.cpp.
 * Drop-in replacement for WakeWordDetector. No beeps, no SpeechRecognizer.
 */
class OnDeviceWakeWordDetector(
    private val audioPipeline: AudioPipeline,
    private val vadDetector: SileroVadDetector,
    private val whisperEngine: WhisperEngine,
    private val wakeWordName: String = "adam",
    private val onWakeWordDetected: () -> Unit
) : AudioPipeline.FrameListener {

    companion object {
        private const val TAG = "OnDeviceWakeWord"
        private const val MIN_SPEECH_FRAMES = 10  // ~320ms
        private const val MAX_SPEECH_FRAMES = 160 // ~5s — anything longer isn't a wake word
        private const val SILENCE_FRAMES_END = 20 // ~640ms silence after speech = utterance done

        /** Check if transcription matches the wake word using fuzzy matching. */
        fun matchesWakeWord(transcription: String, name: String): Boolean {
            val lower = transcription.lowercase().trim()
            val nameLower = name.lowercase().trim()
            if (nameLower.isEmpty()) return false

            // Exact containment — fastest path
            if (lower.contains(nameLower)) return true

            val words = lower.split(Regex("\\s+"))
            // Allow edit distance 1 for short names (<=4), 2 for longer ones
            val maxDist = if (nameLower.length <= 4) 1 else 2

            // Check each individual word
            for (word in words) {
                if (levenshtein(word, nameLower) <= maxDist) return true
            }

            // Check adjacent word pairs — handles splits like "a dam" for "adam", "jar vis" for "jarvis"
            for (i in 0 until words.size - 1) {
                val pair = words[i] + words[i + 1]
                if (levenshtein(pair, nameLower) <= maxDist) return true
            }

            return false
        }

        private fun levenshtein(a: String, b: String): Int {
            val m = a.length
            val n = b.length
            // Quick bounds check — if length difference alone exceeds threshold, skip
            if (kotlin.math.abs(m - n) > 2) return kotlin.math.abs(m - n)

            var prev = IntArray(n + 1) { it }
            var curr = IntArray(n + 1)
            for (i in 1..m) {
                curr[0] = i
                for (j in 1..n) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                }
                val tmp = prev; prev = curr; curr = tmp
            }
            return prev[n]
        }
    }

    private val nameLower = wakeWordName.lowercase().trim()

    @Volatile
    private var isActive = false
    private var isTranscribing = false
    private val whisperMutex = Mutex()
    private val audioBuffer = mutableListOf<FloatArray>()
    private var isSpeaking = false
    private var silenceFrameCount = 0
    private var lastVadProb = 0f
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (isActive) return
        isActive = true
        resetDetection()
        vadDetector.resetState()
        audioPipeline.addListener(this)
        Log.d(TAG, "Wake word detection started")
    }

    fun stop() {
        if (!isActive) return
        isActive = false
        audioPipeline.removeListener(this)
        scope.coroutineContext.cancelChildren()
        Log.d(TAG, "Wake word detection stopped")
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private var frameCount = 0

    override fun onAudioFrame(samples: FloatArray) {
        if (!isActive) return

        frameCount++

        // Only run VAD every 3rd frame (~10/sec instead of 31) to save CPU.
        // During speech, still buffer every frame for Whisper.
        val prob = if (isSpeaking || frameCount % 3 == 0) {
            vadDetector.processFrame(samples).also { lastVadProb = it }
        } else {
            lastVadProb
        }

        if (!isSpeaking) {
            if (prob >= SileroVadDetector.SPEECH_THRESHOLD) {
                isSpeaking = true
                silenceFrameCount = 0
                audioBuffer.clear()
                audioBuffer.add(samples.copyOf())
            }
        } else {
            audioBuffer.add(samples.copyOf())

            if (prob < SileroVadDetector.SILENCE_THRESHOLD) {
                silenceFrameCount++
                if (silenceFrameCount >= SILENCE_FRAMES_END) {
                    // Utterance complete — check if it's a wake word
                    val frameCount = audioBuffer.size
                    if (frameCount in MIN_SPEECH_FRAMES..MAX_SPEECH_FRAMES && !isTranscribing) {
                        val captured = flattenBuffer()
                        // Check average energy — skip if too quiet (noise, not speech)
                        var sum = 0.0
                        for (s in captured) sum += s * s
                        val avgRms = kotlin.math.sqrt(sum / captured.size)
                        if (avgRms < 0.01) {
                            Log.d(TAG, "Skipping: avg RMS $avgRms too low")
                        } else {
                            scope.launch { checkWakeWord(captured) }
                        }
                    } else if (frameCount !in MIN_SPEECH_FRAMES..MAX_SPEECH_FRAMES) {
                        Log.d(TAG, "Skipping: ${frameCount} frames (too ${if (frameCount < MIN_SPEECH_FRAMES) "short" else "long"})")
                    }
                    resetDetection()
                }
            } else {
                silenceFrameCount = 0
            }

            // Safety: discard if too long
            if (audioBuffer.size > MAX_SPEECH_FRAMES) {
                Log.d(TAG, "Discarding: speech too long for wake word")
                resetDetection()
            }
        }
    }

    private fun resetDetection() {
        isSpeaking = false
        silenceFrameCount = 0
        audioBuffer.clear()
        vadDetector.resetState()
    }

    private fun flattenBuffer(): FloatArray {
        val totalSamples = audioBuffer.sumOf { it.size }
        val result = FloatArray(totalSamples)
        var offset = 0
        for (frame in audioBuffer) {
            frame.copyInto(result, offset)
            offset += frame.size
        }
        return result
    }

    private suspend fun checkWakeWord(samples: FloatArray) {
        if (!isActive) return

        whisperMutex.withLock {
        isTranscribing = true
        try {
            val text = whisperEngine.transcribe(samples)
            val lower = text.lowercase().trim()
            Log.d(TAG, "Heard: '$lower'")

            if (matchesWakeWord(lower, nameLower)) {
                Log.d(TAG, "Wake word detected!")
                if (!isActive) return
                isActive = false
                withContext(Dispatchers.Main) {
                    audioPipeline.removeListener(this@OnDeviceWakeWordDetector)
                    onWakeWordDetected()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wake word transcription error", e)
        } finally {
            isTranscribing = false
        }
        } // whisperMutex
    }
}
