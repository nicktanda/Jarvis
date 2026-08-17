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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class WebSearcher(private val apiKey: String) {

    companion object {
        private const val TAG = "WebSearcher"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5-20251001"
        private const val MAX_SNIPPETS = 5
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        try {
            val snippets = fetchSearchResults(query)

            if (snippets.isEmpty()) {
                return@withContext "I couldn't find any results for that."
            }

            summarizeWithClaude(query, snippets)
        } catch (e: Exception) {
            Log.e(TAG, "Web search failed", e)
            "Sorry, the search didn't work. Try again."
        }
    }

    private fun fetchSearchResults(query: String): List<String> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encoded"

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: return emptyList()

        val snippets = mutableListOf<String>()
        val pattern = Pattern.compile(
            "class=\"result__snippet\"[^>]*>(.*?)</(?:a|span)>",
            Pattern.DOTALL
        )
        val matcher = pattern.matcher(html)

        while (matcher.find() && snippets.size < MAX_SNIPPETS) {
            val snippet = matcher.group(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace("&amp;", "&")
                ?.replace("&lt;", "<")
                ?.replace("&gt;", ">")
                ?.replace("&quot;", "\"")
                ?.replace("&#x27;", "'")
                ?.replace("&#39;", "'")
                ?.replace("&nbsp;", " ")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
            if (!snippet.isNullOrBlank() && snippet.length > 20) {
                snippets.add(snippet)
            }
        }

        Log.d(TAG, "Found ${snippets.size} search snippets for: $query")
        return snippets
    }

    private fun summarizeWithClaude(query: String, snippets: List<String>): String {
        val searchContext = snippets.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")

        val systemPrompt = """You are providing a brief spoken answer based on web search results.
Keep your response concise (2-3 sentences max) and conversational — it will be read aloud by a voice assistant.
Do not use formatting, bullet points, links, or special characters.
If the search results don't contain a clear answer, say so briefly."""

        val userMessage = "Search results for \"$query\":\n\n$searchContext\n\nProvide a brief spoken answer."

        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 300)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
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
            Log.e(TAG, "Claude summarization failed: ${response.code}")
            // Fall back to reading the first snippet directly
            return snippets.first()
        }

        val responseJson = JSONObject(body)
        val summary = responseJson.getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
            .trim()

        Log.d(TAG, "Search summary: $summary")
        return summary
    }
}
