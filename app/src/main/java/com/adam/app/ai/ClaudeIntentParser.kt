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
{"action": "react_to_message", "notification_index": 0, "emoji": "thumbs_up"}
{"action": "start_conversation", "topic": "what they want to discuss"}
{"action": "continue_conversation", "topic": "topic to search for"}
{"action": "list_conversations"}
{"action": "repeat"}
{"action": "unknown", "clarification": "what you need to know"}

Rules:
- If the user says "reply" or "respond" without specifying which notification, use the last spoken notification (index from context).
- Match contact names loosely (e.g., "Mom" could match "Mom", "Mum", or a contact nicknamed "Mom").
- If the user says "that" or "this" referring to a notification, use the last spoken notification.
- For "reply" actions: use "reply_notification" if there's a notification to reply to, or "send_sms" if they're initiating a new message.
- Use "start_conversation" for ANY open-ended question, discussion, or topic the user wants to explore. This includes "tell me about...", "what is...", "explain...", "I want to know about...", "how does... work", or any question that could lead to follow-up discussion. The conversation supports web search internally, so prefer this over web_search for most queries.
- Use "web_search" ONLY for quick factual lookups where no follow-up is expected: current weather, sports scores, stock prices, "what time is it in Tokyo", or when the user explicitly says "search for...". If in doubt between web_search and start_conversation, prefer start_conversation.
- Use "react_to_message" when the user wants to react or emoji-react to a message/notification. Use notification_index -1 if no specific message is indicated. Map their spoken emoji to one of: thumbs_up, heart, laugh, sad, wow, angry, fire, thumbs_down, clap, pray, 100, eyes, skull. Use empty string for emoji if not specified.
- Use "continue_conversation" when the user says "continue conversation", "continue our conversation", "resume conversation", "go back to our chat", "pick up where we left off", or similar. If they specify a topic (e.g. "continue conversation about angry birds"), include it. If they just say "continue conversation" with no topic, use an empty topic string.
- Use "list_conversations" ONLY when the user explicitly asks to list or show their conversations, e.g. "list my conversations", "show my conversations", "what conversations do I have".
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
            "react_to_message" -> IntentResult.ReactToMessage(
                notificationIndex = parsed.notification_index,
                emoji = parsed.emoji
            )
            "start_conversation" -> IntentResult.StartConversation(topic = parsed.topic)
            "continue_conversation" -> IntentResult.ContinueConversation(topic = parsed.topic)
            "list_conversations" -> IntentResult.ListConversations
            "repeat" -> IntentResult.Repeat
            else -> IntentResult.Unknown(
                clarification = parsed.clarification.ifEmpty { "I didn't understand that command." }
            )
        }
    }
}
