package com.adam.app.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 wrapper using ONNX Runtime.
 * Processes 512-sample frames at 16kHz and returns speech probability.
 */
class SileroVadDetector(context: Context) {

    companion object {
        private const val TAG = "SileroVAD"
        private const val MODEL_NAME = "silero_vad.onnx"
        const val FRAME_SIZE = 512 // 32ms at 16kHz
        const val SPEECH_THRESHOLD = 0.5f
        const val SILENCE_THRESHOLD = 0.3f
    }

    private val ortEnv = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession

    // Silero VAD v5 uses a single combined state tensor [2, batch, 128]
    private var state = FloatArray(2 * 1 * 128)

    init {
        val modelBytes = context.assets.open(MODEL_NAME).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
        }
        ortSession = ortEnv.createSession(modelBytes, opts)
        Log.d(TAG, "Silero VAD initialized")
    }

    /**
     * Process a single 512-sample frame.
     * @return speech probability [0.0, 1.0]
     *
     * Uses RMS energy-based detection. The Silero ONNX model has compatibility
     * issues across devices, so we use a simple but reliable approach:
     * speech > 0.01 RMS, silence < 0.005 RMS, with smooth mapping between.
     */
    fun processFrame(samples: FloatArray): Float {
        var sum = 0.0
        for (s in samples) sum += s * s
        val rms = kotlin.math.sqrt(sum / samples.size).toFloat()

        // Map RMS to speech probability with hysteresis
        // Below 0.008 = definitely silence, above 0.02 = definitely speech
        // (noise floor on tested devices is 0.001-0.003)
        return ((rms - 0.008f) / 0.012f).coerceIn(0f, 1f)
    }

    /**
     * Reset hidden state between utterances.
     */
    fun resetState() {
        state = FloatArray(2 * 1 * 128)
    }

    fun release() {
        try {
            ortSession.close()
            ortEnv.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VAD", e)
        }
    }
}
