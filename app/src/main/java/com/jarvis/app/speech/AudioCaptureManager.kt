package com.jarvis.app.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class AudioCaptureManager(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val SILENCE_THRESHOLD = 500.0  // RMS threshold for silence
        private const val SILENCE_DURATION_MS = 2000L // 2 seconds of silence to stop
        private const val MAX_RECORDING_MS = 30000L   // Max 30 seconds
        private const val MIN_RECORDING_MS = 500L     // Min recording before silence detection kicks in
    }

    suspend fun startRecording(): File? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return@withContext null
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return@withContext null
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating AudioRecord", e)
            return@withContext null
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return@withContext null
        }

        val outputFile = File(context.cacheDir, "jarvis_audio_${System.currentTimeMillis()}.wav")
        val rawFile = File(context.cacheDir, "jarvis_raw_${System.currentTimeMillis()}.pcm")

        isRecording = true
        audioRecord?.startRecording()

        val buffer = ShortArray(bufferSize / 2)
        var silenceStartTime = 0L
        val recordingStartTime = System.currentTimeMillis()

        try {
            FileOutputStream(rawFile).use { fos ->
                while (isRecording) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readCount <= 0) break

                    // Write raw PCM data
                    val byteBuffer = ByteBuffer.allocate(readCount * 2)
                    byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until readCount) {
                        byteBuffer.putShort(buffer[i])
                    }
                    fos.write(byteBuffer.array(), 0, readCount * 2)

                    // Silence detection
                    val elapsed = System.currentTimeMillis() - recordingStartTime
                    if (elapsed > MIN_RECORDING_MS) {
                        val rms = calculateRMS(buffer, readCount)
                        if (rms < SILENCE_THRESHOLD) {
                            if (silenceStartTime == 0L) {
                                silenceStartTime = System.currentTimeMillis()
                            } else if (System.currentTimeMillis() - silenceStartTime > SILENCE_DURATION_MS) {
                                Log.d(TAG, "Silence detected, stopping recording")
                                break
                            }
                        } else {
                            silenceStartTime = 0L
                        }
                    }

                    // Max duration check
                    if (System.currentTimeMillis() - recordingStartTime > MAX_RECORDING_MS) {
                        Log.d(TAG, "Max recording duration reached")
                        break
                    }
                }
            }
        } finally {
            stopRecordingInternal()
        }

        // Convert raw PCM to WAV
        val success = pcmToWav(rawFile, outputFile, SAMPLE_RATE, 1, 16)
        rawFile.delete()

        if (success) outputFile else null
    }

    fun stopRecording() {
        isRecording = false
    }

    private fun stopRecordingInternal() {
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord?.release()
        audioRecord = null
    }

    private fun calculateRMS(buffer: ShortArray, readCount: Int): Double {
        var sum = 0.0
        for (i in 0 until readCount) {
            sum += buffer[i].toDouble() * buffer[i].toDouble()
        }
        return sqrt(sum / readCount)
    }

    private fun pcmToWav(
        pcmFile: File,
        wavFile: File,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): Boolean {
        try {
            val pcmData = pcmFile.readBytes()
            val dataLength = pcmData.size
            val totalLength = dataLength + 36

            FileOutputStream(wavFile).use { fos ->
                val header = ByteBuffer.allocate(44)
                header.order(ByteOrder.LITTLE_ENDIAN)

                // RIFF header
                header.put("RIFF".toByteArray())
                header.putInt(totalLength)
                header.put("WAVE".toByteArray())

                // fmt subchunk
                header.put("fmt ".toByteArray())
                header.putInt(16) // subchunk size
                header.putShort(1) // PCM format
                header.putShort(channels.toShort())
                header.putInt(sampleRate)
                header.putInt(sampleRate * channels * bitsPerSample / 8) // byte rate
                header.putShort((channels * bitsPerSample / 8).toShort()) // block align
                header.putShort(bitsPerSample.toShort())

                // data subchunk
                header.put("data".toByteArray())
                header.putInt(dataLength)

                fos.write(header.array())
                fos.write(pcmData)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error converting PCM to WAV", e)
            return false
        }
    }
}
