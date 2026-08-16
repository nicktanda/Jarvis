package com.jarvis.app.speech

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

    // Silero VAD is stateful — hidden and cell state persist between frames
    private var h = FloatArray(2 * 1 * 64) // [2, 1, 64]
    private var c = FloatArray(2 * 1 * 64) // [2, 1, 64]

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
     */
    fun processFrame(samples: FloatArray): Float {
        if (samples.size != FRAME_SIZE) {
            Log.w(TAG, "Expected $FRAME_SIZE samples, got ${samples.size}")
            return 0f
        }

        val inputTensor = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(samples),
            longArrayOf(1, FRAME_SIZE.toLong())
        )

        val srTensor = OnnxTensor.createTensor(
            ortEnv,
            LongBuffer.wrap(longArrayOf(16000)),
            longArrayOf(1)
        )

        val hTensor = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(h),
            longArrayOf(2, 1, 64)
        )

        val cTensor = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(c),
            longArrayOf(2, 1, 64)
        )

        val inputs = mapOf(
            "input" to inputTensor,
            "sr" to srTensor,
            "h" to hTensor,
            "c" to cTensor
        )

        return try {
            val results = ortSession.run(inputs)

            // Extract outputs
            val outputTensor = results[0] as OnnxTensor
            val hnTensor = results[1] as OnnxTensor
            val cnTensor = results[2] as OnnxTensor

            val probability = (outputTensor.floatBuffer.get(0))

            // Update hidden states for next frame
            hnTensor.floatBuffer.get(h)
            cnTensor.floatBuffer.get(c)

            results.close()
            probability
        } catch (e: Exception) {
            Log.e(TAG, "VAD inference error", e)
            0f
        } finally {
            inputTensor.close()
            srTensor.close()
            hTensor.close()
            cTensor.close()
        }
    }

    /**
     * Reset hidden state between utterances.
     */
    fun resetState() {
        h = FloatArray(2 * 1 * 64)
        c = FloatArray(2 * 1 * 64)
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
