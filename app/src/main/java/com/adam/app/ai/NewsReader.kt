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

class NewsReader(private val apiKey: String) {

    companion object {
        private const val TAG = "NewsReader"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5-20251001"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Headline(val number: Int, val title: String)

    suspend fun fetchHeadlines(topic: String = ""): Pair<List<Headline>, String> = withContext(Dispatchers.IO) {
        try {
            val query = if (topic.isBlank()) {
                "What are today's top news headlines?"
            } else {
                "What are today's top news headlines about $topic?"
            }

            val systemPrompt = """You are a news reader for a voice assistant. Search for today's top news stories and return exactly 5 headlines.

Format your response EXACTLY like this, with one headline per line numbered 1 through 5:
1. [headline]
2. [headline]
3. [headline]
4. [headline]
5. [headline]

Rules:
- Each headline should be a concise single sentence.
- Do not include source names, dates, or URLs.
- Do not include any other text before or after the list.
- Cover a variety of topics unless a specific topic was requested."""

            val result = callClaude(systemPrompt, query)

            // Parse numbered headlines
            val headlines = mutableListOf<Headline>()
            val lines = result.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            for (line in lines) {
                val match = Regex("^(\\d+)\\.\\s*(.+)").find(line)
                if (match != null) {
                    val num = match.groupValues[1].toInt()
                    val title = match.groupValues[2].trim()
                    headlines.add(Headline(num, title))
                }
            }

            Log.d(TAG, "Fetched ${headlines.size} headlines")
            Pair(headlines, result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch headlines", e)
            Pair(emptyList(), "")
        }
    }

    suspend fun summarize(headline: String): String = withContext(Dispatchers.IO) {
        try {
            val systemPrompt = """You are a news reader for a voice assistant. Give a spoken summary of this news story.

Rules:
- The summary should be about 30 seconds when read aloud (roughly 4-6 sentences).
- Be conversational and natural — this will be read aloud by text-to-speech.
- Do not use formatting, bullet points, links, or special characters.
- Include key facts: who, what, when, where, and why it matters.
- End with a brief note on why this story is significant or what might happen next."""

            val result = callClaude(systemPrompt, "Give me a summary of this news story: $headline")
            Log.d(TAG, "Summary: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to summarize", e)
            "Sorry, I couldn't get a summary for that story."
        }
    }

    private fun callClaude(systemPrompt: String, userMessage: String): String {
        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 1024)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            })
            put("tools", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "web_search_20250305")
                    put("name", "web_search")
                    put("max_uses", 5)
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
            Log.e(TAG, "Claude API error: ${response.code} $body")
            return "Sorry, I couldn't fetch the news right now."
        }

        val responseJson = JSONObject(body)
        val content = responseJson.getJSONArray("content")
        val textParts = mutableListOf<String>()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.getString("type") == "text") {
                textParts.add(block.getString("text").trim())
            }
        }

        return textParts.joinToString(" ").ifBlank {
            "I couldn't find any news right now."
        }
    }
}
