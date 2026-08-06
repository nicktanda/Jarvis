package com.jarvis.app.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
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
        private const val PREFS_NAME = "jarvis_updater"
        private const val KEY_LAST_COMMIT = "last_installed_commit"
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
                return
            }

            val release = json.decodeFromString<GitHubRelease>(body)

            // Extract commit SHA from the release body
            // Body format: "Auto-built from commit <sha>\n<message>"
            val commitSha = extractCommitSha(release.body)
            if (commitSha == null) {
                Log.w(TAG, "Could not extract commit SHA from release body")
                return
            }

            // Check if we already have this version
            val lastInstalledCommit = getLastInstalledCommit()
            if (commitSha == lastInstalledCommit) {
                Log.d(TAG, "Already on latest version ($commitSha)")
                return
            }

            // Find the APK asset
            val apkAsset = release.assets.find { it.name.endsWith(".apk") }
            if (apkAsset == null) {
                Log.w(TAG, "No APK found in release assets")
                return
            }

            Log.d(TAG, "Update available: $commitSha (current: $lastInstalledCommit)")
            onUpdateStatus("Update available. Downloading.")

            // Download the APK
            val apkFile = downloadApk(apkAsset.browser_download_url)
            if (apkFile == null) {
                onUpdateStatus("Download failed. Will retry later.")
                return
            }

            // Save the commit SHA so we know what version we're installing
            saveLastInstalledCommit(commitSha)

            onUpdateStatus("Update downloaded. Volume up to install.")

            // Trigger install on the main thread
            withContext(Dispatchers.Main) {
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

            val apkFile = File(context.getExternalFilesDir(null), "jarvis-update.apk")

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

    private fun promptInstall(apkFile: File) {
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

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prompt install", e)
            onUpdateStatus("Could not open installer.")
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
