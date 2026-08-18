package com.adam.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TextProcessor(private val apiKey: String) {

    companion object {
        private const val TAG = "TextProcessor"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5-20251001"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun addPunctuation(text: String): String = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 256)
                put("system", "Add punctuation and capitalization to this dictated text message. Return ONLY the corrected text, nothing else. Do not change any words, just add punctuation and fix capitalization.")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", text)
                    })
                })
            }

            val request = Request.Builder()
                .url(API_URL)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                Log.e(TAG, "Punctuation API error: ${response.code}")
                return@withContext text
            }

            val result = JSONObject(body)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()

            Log.d(TAG, "Punctuated: $text -> $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Punctuation failed, using original", e)
            text
        }
    }
}
