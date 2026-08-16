package com.jarvis.app.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class AudioPipeline(private val context: Context) {

    companion object {
        private const val TAG = "AudioPipeline"
        const val SAMPLE_RATE = 16000
        const val FRAME_SIZE = 512 // ~32ms at 16kHz, matches Silero VAD input
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    interface FrameListener {
        fun onAudioFrame(samples: FloatArray)
    }

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private val isRunning = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<FrameListener>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(): Boolean {
        if (isRunning.get()) return true

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return false
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize.coerceAtLeast(FRAME_SIZE * 2 * 4) // at least 4 frames
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating AudioRecord", e)
            return false
        }

        val record = audioRecord ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            audioRecord = null
            return false
        }

        // Attach audio effects
        val sessionId = record.audioSessionId
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(sessionId)
            Log.d(TAG, "NoiseSuppressor attached: ${noiseSuppressor != null}")
        }
        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(sessionId)
            Log.d(TAG, "AGC attached: ${agc != null}")
        }

        record.startRecording()
        isRunning.set(true)
        Log.d(TAG, "Audio pipeline started (session=$sessionId)")

        // Start read loop
        scope.launch { readLoop(record) }

        return true
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        noiseSuppressor?.release()
        noiseSuppressor = null
        agc?.release()
        agc = null

        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord?.release()
        audioRecord = null

        scope.coroutineContext.cancelChildren()
        Log.d(TAG, "Audio pipeline stopped")
    }

    fun addListener(listener: FrameListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: FrameListener) {
        listeners.remove(listener)
    }

    fun isRunning(): Boolean = isRunning.get()

    private suspend fun readLoop(record: AudioRecord) {
        val shortBuffer = ShortArray(FRAME_SIZE)
        val floatBuffer = FloatArray(FRAME_SIZE)

        while (isRunning.get()) {
            val readCount = record.read(shortBuffer, 0, FRAME_SIZE)
            if (readCount <= 0) {
                if (readCount == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "AudioRecord invalid operation")
                    break
                }
                continue
            }

            // Convert PCM16 to float32 normalized [-1, 1]
            for (i in 0 until readCount) {
                floatBuffer[i] = shortBuffer[i] / 32768.0f
            }

            // Distribute to listeners
            val frame = if (readCount == FRAME_SIZE) floatBuffer else floatBuffer.copyOf(readCount)
            for (listener in listeners) {
                try {
                    listener.onAudioFrame(frame)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in frame listener", e)
                }
            }
        }
    }
}
