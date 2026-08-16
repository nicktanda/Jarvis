package com.adam.app.updater

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.adam.app.AdamApplication
import com.adam.app.R
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class UpdateChecker(
    private val context: Context,
    private val onUpdateStatus: (String) -> Unit
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private var checkJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "UpdateChecker"
        private const val REPO_OWNER = "nicktanda"
        private const val REPO_NAME = "Jarvis"
        private const val RELEASES_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/tags/latest"
        private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
        private const val PREFS_NAME = "adam_updater"
        private const val KEY_LAST_COMMIT = "last_installed_commit"
        private const val UPDATE_NOTIFICATION_ID = 42
    }

    @Serializable
    data class GitHubRelease(
        val tag_name: String = "",
        val body: String = "",
        val assets: List<GitHubAsset> = emptyList()
    )

    @Serializable
    data class GitHubAsset(
        val name: String = "",
        val browser_download_url: String = "",
        val size: Long = 0
    )

    fun startPeriodicChecks() {
        checkJob?.cancel()
        checkJob = scope.launch {
            while (isActive) {
                checkForUpdate()
                delay(CHECK_INTERVAL_MS)
            }
        }
        Log.d(TAG, "Periodic update checks started (every ${CHECK_INTERVAL_MS / 60000} min)")
    }

    fun stopPeriodicChecks() {
        checkJob?.cancel()
        checkJob = null
    }

    fun checkNow() {
        scope.launch { checkForUpdate() }
    }

    private suspend fun checkForUpdate() {
        try {
            Log.d(TAG, "Checking for updates...")

            val request = Request.Builder()
                .url(RELEASES_URL)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                Log.w(TAG, "GitHub API error: ${response.code}")
                onUpdateStatus("Could not check for updates.")
                return
            }

            val release = json.decodeFromString<GitHubRelease>(body)

            // Extract commit SHA from the release body
            // Body format: "Auto-built from commit <sha>\n<message>"
            val commitSha = extractCommitSha(release.body)
            if (commitSha == null) {
                Log.w(TAG, "Could not extract commit SHA from release body")
                onUpdateStatus("No release found.")
                return
            }

            // Find the APK asset
            val apkAsset = release.assets.find { it.name.endsWith(".apk") }
            if (apkAsset == null) {
                Log.w(TAG, "No APK found in release assets")
                onUpdateStatus("No release found.")
                return
            }

            // Check if we already downloaded this commit
            val lastDownloadedCommit = getLastInstalledCommit()
            val existingApk = File(context.getExternalFilesDir(null), "adam-update.apk")

            if (commitSha == lastDownloadedCommit && existingApk.exists()) {
                Log.d(TAG, "Update already downloaded ($commitSha), skipping")
                return
            }

            Log.d(TAG, "Update available: $commitSha (current: $lastDownloadedCommit)")

            // Max out alarm volume so the update announcement is heard
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

            onUpdateStatus("Update available. Downloading.")

            // Download the APK
            val apkFile = downloadApk(apkAsset.browser_download_url)
            if (apkFile == null) {
                onUpdateStatus("Download failed. Will retry later.")
                return
            }

            // Save commit so we don't re-download, but keep showing notification
            saveLastInstalledCommit(commitSha)

            onUpdateStatus("Update downloaded. Installing.")

            // Show notification AND launch installer directly
            withContext(Dispatchers.Main) {
                showUpdateNotification(apkFile)
                promptInstall(apkFile)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
        }
    }

    private fun downloadApk(url: String): File? {
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "APK download failed: ${response.code}")
                return null
            }

            val apkFile = File(context.getExternalFilesDir(null), "adam-update.apk")

            response.body?.byteStream()?.use { input ->
                FileOutputStream(apkFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "APK downloaded: ${apkFile.length()} bytes")
            return apkFile

        } catch (e: Exception) {
            Log.e(TAG, "APK download error", e)
            return null
        }
    }

    private fun showUpdateNotification(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            val pendingIntent = PendingIntent.getActivity(
                context, 0, installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, AdamApplication.UPDATE_CHANNEL_ID)
                .setContentTitle("Adam Update Ready")
                .setContentText("Tap to install the latest version")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(R.drawable.ic_notification, "Install Update", pendingIntent)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(UPDATE_NOTIFICATION_ID, notification)

            Log.d(TAG, "Update notification posted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show update notification", e)
            onUpdateStatus("Could not show update notification.")
        }
    }

    private fun promptInstall(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer", e)
        }
    }

    private fun extractCommitSha(body: String): String? {
        // Body format: "Auto-built from commit <sha>\n..."
        val regex = Regex("commit\\s+([a-f0-9]{40})")
        return regex.find(body)?.groupValues?.get(1)
    }

    private fun getLastInstalledCommit(): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_COMMIT, null)
    }

    private fun saveLastInstalledCommit(sha: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_COMMIT, sha).apply()
    }

    fun destroy() {
        scope.cancel()
    }
}
