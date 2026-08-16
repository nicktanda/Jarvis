package com.jarvis.app.speech

import android.util.Log
import kotlinx.coroutines.*

/**
 * On-device wake word detector using AudioPipeline + Silero VAD + whisper.cpp.
 * Drop-in replacement for WakeWordDetector. No beeps, no SpeechRecognizer.
 */
class OnDeviceWakeWordDetector(
    private val audioPipeline: AudioPipeline,
    private val vadDetector: SileroVadDetector,
    private val whisperEngine: WhisperEngine,
    private val onWakeWordDetected: () -> Unit
) : AudioPipeline.FrameListener {

    companion object {
        private const val TAG = "OnDeviceWakeWord"
        private val WAKE_WORDS = listOf("jarvis", "hey jarvis", "hey travis", "hey jarvy", "hey service")
        private const val MIN_SPEECH_FRAMES = 10  // ~320ms
        private const val MAX_SPEECH_FRAMES = 160 // ~5s — anything longer isn't a wake word
        private const val SILENCE_FRAMES_END = 20 // ~640ms silence after speech = utterance done
    }

    @Volatile
    private var isActive = false
    private val audioBuffer = mutableListOf<FloatArray>()
    private var isSpeaking = false
    private var silenceFrameCount = 0
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

    override fun onAudioFrame(samples: FloatArray) {
        if (!isActive) return

        val prob = vadDetector.processFrame(samples)

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
                    if (frameCount in MIN_SPEECH_FRAMES..MAX_SPEECH_FRAMES) {
                        val captured = flattenBuffer()
                        scope.launch { checkWakeWord(captured) }
                    } else {
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

        try {
            val text = whisperEngine.transcribe(samples)
            val lower = text.lowercase().trim()
            Log.d(TAG, "Heard: '$lower'")

            if (WAKE_WORDS.any { lower.contains(it) }) {
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
        }
    }
}
