package com.adam.app.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class WhisperEngine(private val context: Context) {

    companion object {
        private const val TAG = "WhisperEngine"
        private const val MODEL_NAME = "ggml-base.en-q5_1.bin"

        init {
            System.loadLibrary("whisper_jni")
        }
    }

    private var nativeContext: Long = 0
    private val mutex = Mutex()

    suspend fun init() = withContext(Dispatchers.IO) {
        val modelFile = File(context.filesDir, MODEL_NAME)

        // Copy model from assets if not already cached
        if (!modelFile.exists()) {
            Log.d(TAG, "Copying whisper model from assets...")
            context.assets.open(MODEL_NAME).use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Model copied to ${modelFile.absolutePath}")
        }

        nativeContext = nativeInit(modelFile.absolutePath)
        if (nativeContext == 0L) {
            throw RuntimeException("Failed to initialize whisper engine")
        }
        Log.d(TAG, "Whisper engine initialized")
    }

    /**
     * Transcribe float32 PCM audio samples (16kHz mono, normalized to [-1, 1]).
     * Runs on IO dispatcher to avoid blocking Main.
     */
    suspend fun transcribe(samples: FloatArray): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (nativeContext == 0L) {
                Log.e(TAG, "Whisper engine not initialized")
                return@withContext ""
            }
            nativeTranscribe(nativeContext, samples)
        }
    }

    fun release() {
        if (nativeContext != 0L) {
            nativeRelease(nativeContext)
            nativeContext = 0
        }
    }

    fun isInitialized(): Boolean = nativeContext != 0L

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(context: Long, samples: FloatArray): String
    private external fun nativeRelease(context: Long)
}
