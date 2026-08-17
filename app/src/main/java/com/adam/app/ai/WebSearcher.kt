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

class WebSearcher(private val apiKey: String) {

    companion object {
        private const val TAG = "WebSearcher"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5-20251001"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        try {
            val systemPrompt = """You are a voice assistant answering a user's question using web search.
Keep your response concise (2-3 sentences max) and conversational — it will be read aloud.
Do not use formatting, bullet points, links, or special characters.
If you can't find a clear answer, say so briefly."""

            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 512)
                put("system", systemPrompt)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", query)
                    })
                })
                put("tools", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "web_search_20250305")
                        put("name", "web_search")
                        put("max_uses", 3)
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
                Log.e(TAG, "Claude web search failed: ${response.code} $body")
                return@withContext "Sorry, the search didn't work. Try again."
            }

            val responseJson = JSONObject(body)
            val content = responseJson.getJSONArray("content")

            // Extract text blocks from the response (skip tool_use/search_result blocks)
            val textParts = mutableListOf<String>()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.getString("type") == "text") {
                    textParts.add(block.getString("text").trim())
                }
            }

            val result = textParts.joinToString(" ").ifBlank {
                "I couldn't find an answer for that."
            }

            Log.d(TAG, "Web search result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Web search failed", e)
            "Sorry, the search didn't work. Try again."
        }
    }
}
