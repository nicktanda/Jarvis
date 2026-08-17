package com.adam.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ClaudeIntentParser(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "ClaudeParser"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5-20251001"

        private const val SYSTEM_PROMPT = """You are a command parser for a voice-controlled phone assistant called Adam. The user is speaking commands to control their phone without looking at the screen.

Given the user's spoken command and current context, return ONLY a single JSON object describing the action to take. No other text.

Possible actions:
{"action": "reply_notification", "notification_index": 0, "message": "the reply message"}
{"action": "send_sms", "contact_name": "contact name", "message": "the message text"}
{"action": "make_call", "contact_name": "contact name"}
{"action": "read_notifications"}
{"action": "dismiss_notification", "notification_index": 0}
{"action": "web_search", "query": "the search query"}
{"action": "repeat"}
{"action": "unknown", "clarification": "what you need to know"}

Rules:
- If the user says "reply" or "respond" without specifying which notification, use the last spoken notification (index from context).
- Match contact names loosely (e.g., "Mom" could match "Mom", "Mum", or a contact nicknamed "Mom").
- If the user says "that" or "this" referring to a notification, use the last spoken notification.
- For "reply" actions: use "reply_notification" if there's a notification to reply to, or "send_sms" if they're initiating a new message.
- Use "web_search" when the user asks a question that requires looking something up online, wants to search for something, or asks about current events, facts, weather, sports scores, etc. Extract the core search query from their spoken request.
- Return ONLY valid JSON. No markdown, no explanation."""
    }

    suspend fun parse(
        userCommand: String,
        context: ConversationContext,
        contactNames: List<String>
    ): Result<IntentResult> = withContext(Dispatchers.IO) {
        try {
            val userMessage = buildString {
                appendLine("User command: \"$userCommand\"")
                appendLine()
                appendLine(context.buildContextString())
                appendLine()
                appendLine("Available contacts: ${contactNames.take(50).joinToString(", ")}")
            }

            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 256)
                put("system", SYSTEM_PROMPT)
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
                Log.e(TAG, "Claude API error: ${response.code} $body")
                return@withContext Result.failure(Exception("Claude API error: ${response.code}"))
            }

            val responseJson = JSONObject(body)
            val content = responseJson.getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()

            // Strip markdown code fences if present
            val jsonStr = content
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val parsed = json.decodeFromString<ParsedIntent>(jsonStr)
            val result = toIntentResult(parsed)

            Log.d(TAG, "Parsed intent: $result")
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Intent parsing failed", e)
            Result.failure(e)
        }
    }

    private fun toIntentResult(parsed: ParsedIntent): IntentResult {
        return when (parsed.action) {
            "reply_notification" -> IntentResult.ReplyNotification(
                notificationIndex = parsed.notification_index,
                message = parsed.message
            )
            "send_sms" -> IntentResult.SendSms(
                contactName = parsed.contact_name,
                message = parsed.message
            )
            "make_call" -> IntentResult.MakeCall(
                contactName = parsed.contact_name
            )
            "read_notifications" -> IntentResult.ReadNotifications
            "dismiss_notification" -> IntentResult.DismissNotification(
                notificationIndex = parsed.notification_index
            )
            "web_search" -> IntentResult.WebSearch(query = parsed.query)
            "repeat" -> IntentResult.Repeat
            else -> IntentResult.Unknown(
                clarification = parsed.clarification.ifEmpty { "I didn't understand that command." }
            )
        }
    }
}
