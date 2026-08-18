package com.adam.app.ai

import android.content.Context
import android.util.Log
import com.adam.app.data.AppDatabase
import com.adam.app.data.ConversationEntity
import com.adam.app.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ConversationEngine(
    private val context: Context,
    private val apiKey: String
) {

    companion object {
        private const val TAG = "ConversationEngine"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5-20251001"

        private const val SYSTEM_PROMPT = """You are Adam, a friendly voice assistant. You are having a spoken conversation with the user. You have access to web search to look up current information when needed.

Rules:
- Keep responses concise (2-4 sentences) since they will be read aloud.
- Be conversational and natural — avoid bullet points, formatting, links, or special characters.
- Remember context from earlier in the conversation.
- Use web search when the user asks about facts, current events, or anything you're not sure about.
- If the user wants to end the conversation, acknowledge it briefly."""

        private const val TITLE_PROMPT = """Generate a short title (3-6 words) for a conversation that starts with this message. Return ONLY the title, no quotes or punctuation."""
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val db = AppDatabase.getInstance(context)

    var activeConversationId: Long? = null
        private set

    suspend fun startConversation(topic: String?): Long = withContext(Dispatchers.IO) {
        val title = topic ?: "New conversation"
        val conversation = ConversationEntity(title = title)
        val id = db.conversationDao().insert(conversation)
        activeConversationId = id
        Log.d(TAG, "Started conversation $id: $title")
        id
    }

    suspend fun resumeConversation(conversationId: Long): Boolean = withContext(Dispatchers.IO) {
        val conversation = db.conversationDao().getById(conversationId)
        if (conversation != null) {
            activeConversationId = conversationId
            Log.d(TAG, "Resumed conversation $conversationId: ${conversation.title}")
            true
        } else {
            Log.w(TAG, "Conversation $conversationId not found")
            false
        }
    }

    fun endConversation() {
        Log.d(TAG, "Ended conversation $activeConversationId")
        activeConversationId = null
    }

    suspend fun sendMessage(userMessage: String): String = withContext(Dispatchers.IO) {
        val convId = activeConversationId
            ?: return@withContext "No active conversation."

        try {
            // Save user message
            db.messageDao().insert(
                MessageEntity(conversationId = convId, role = "user", content = userMessage)
            )
            db.conversationDao().touch(convId)

            // Auto-generate title from first message
            val messageCount = db.messageDao().getMessageCount(convId)
            if (messageCount == 1) {
                generateTitle(convId, userMessage)
            }

            // Build message history
            val history = db.messageDao().getByConversation(convId)
            val messagesArray = JSONArray()
            for (msg in history) {
                messagesArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }

            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 512)
                put("system", SYSTEM_PROMPT)
                put("messages", messagesArray)
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
                Log.e(TAG, "Claude API error: ${response.code} $body")
                return@withContext "Sorry, I couldn't respond right now."
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

            val assistantMessage = textParts.joinToString(" ").ifBlank {
                "I'm not sure how to respond to that."
            }

            // Save assistant message
            db.messageDao().insert(
                MessageEntity(conversationId = convId, role = "assistant", content = assistantMessage)
            )
            db.conversationDao().touch(convId)

            Log.d(TAG, "Response: $assistantMessage")
            assistantMessage
        } catch (e: Exception) {
            Log.e(TAG, "Conversation failed", e)
            "Sorry, something went wrong. Try again."
        }
    }

    suspend fun getRecentConversations(limit: Int = 5): List<ConversationEntity> =
        withContext(Dispatchers.IO) {
            db.conversationDao().getRecent(limit)
        }

    suspend fun findConversation(topic: String): ConversationEntity? =
        withContext(Dispatchers.IO) {
            db.conversationDao().search(topic, 1).firstOrNull()
        }

    suspend fun getConversationSummary(conversationId: Long): String? =
        withContext(Dispatchers.IO) {
            val conversation = db.conversationDao().getById(conversationId) ?: return@withContext null
            val messages = db.messageDao().getByConversation(conversationId)
            val lastMessage = messages.lastOrNull()
            buildString {
                append(conversation.title)
                if (lastMessage != null) {
                    append(". Last message: ")
                    append(lastMessage.content.take(100))
                }
            }
        }

    private suspend fun generateTitle(conversationId: Long, firstMessage: String) {
        try {
            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 30)
                put("system", TITLE_PROMPT)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", firstMessage)
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

            if (response.isSuccessful && body != null) {
                val responseJson = JSONObject(body)
                val content = responseJson.getJSONArray("content")
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.getString("type") == "text") {
                        val title = block.getString("text").trim()
                            .removeSurrounding("\"")
                            .take(50)
                        if (title.isNotBlank()) {
                            db.conversationDao().updateTitle(conversationId, title)
                            Log.d(TAG, "Auto-titled conversation $conversationId: $title")
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate title", e)
        }
    }
}
