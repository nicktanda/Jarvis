package com.adam.app.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import com.adam.app.ai.IntentResult
import com.adam.app.notifications.NotificationCaptureService
import com.adam.app.speech.OnDeviceSTTClient
import com.adam.app.notifications.NotificationData
import com.adam.app.notifications.NotificationQueue

class ActionExecutor(
    private val context: Context,
    private val contactResolver: ContactResolver,
    private val notificationQueue: NotificationQueue
) {

    companion object {
        private const val TAG = "ActionExecutor"

        val EMOJI_MAP = mapOf(
            "thumbs_up" to "\uD83D\uDC4D", "like" to "\uD83D\uDC4D",
            "thumbs_down" to "\uD83D\uDC4E", "dislike" to "\uD83D\uDC4E",
            "heart" to "❤\uFE0F", "love" to "❤\uFE0F",
            "laugh" to "\uD83D\uDE02", "laughing" to "\uD83D\uDE02", "haha" to "\uD83D\uDE02",
            "sad" to "\uD83D\uDE22", "crying" to "\uD83D\uDE22",
            "wow" to "\uD83D\uDE2E", "surprised" to "\uD83D\uDE2E",
            "angry" to "\uD83D\uDE21", "mad" to "\uD83D\uDE21",
            "fire" to "\uD83D\uDD25",
            "thumbs down" to "\uD83D\uDC4E",
            "thumbs up" to "\uD83D\uDC4D",
            "clap" to "\uD83D\uDC4F", "clapping" to "\uD83D\uDC4F",
            "pray" to "\uD83D\uDE4F", "praying" to "\uD83D\uDE4F", "thanks" to "\uD83D\uDE4F",
            "100" to "\uD83D\uDCAF", "hundred" to "\uD83D\uDCAF",
            "eyes" to "\uD83D\uDC40",
            "skull" to "\uD83D\uDC80", "dead" to "\uD83D\uDC80"
        )

        fun resolveEmoji(spoken: String): String? {
            val lower = spoken.lowercase().trim()
            return EMOJI_MAP[lower]
        }
    }

    data class ActionDescription(
        val description: String,
        val isCall: Boolean = false,
        val execute: () -> Boolean
    )

    /**
     * Prepares an action for confirmation. Returns a description string
     * and an executable lambda. The caller should speak the description,
     * wait for confirmation, then call execute().
     */
    fun prepare(
        intent: IntentResult,
        recentNotifications: List<NotificationData>
    ): ActionDescription? {
        return when (intent) {
            is IntentResult.SendSms -> prepareSms(intent)
            is IntentResult.MakeCall -> prepareCall(intent)
            is IntentResult.ReplyNotification -> prepareReply(intent, recentNotifications)
            is IntentResult.DismissNotification -> prepareDismiss(intent, recentNotifications)
            is IntentResult.ReadNotifications -> {
                ActionDescription("Reading notifications") { true }
            }
            is IntentResult.WebSearch -> {
                ActionDescription("Searching") { true }
            }
            is IntentResult.ReactToMessage -> {
                prepareReaction(intent, recentNotifications)
            }
            is IntentResult.Repeat -> {
                ActionDescription("Repeating") { true }
            }
            is IntentResult.StartConversation,
            is IntentResult.ContinueConversation,
            is IntentResult.ListConversations -> {
                null // Handled directly by AdamService
            }
            is IntentResult.Unknown -> {
                null // Handled by caller via TTS
            }
        }
    }

    private fun prepareSms(intent: IntentResult.SendSms): ActionDescription? {
        val contact = contactResolver.findContact(intent.contactName)
        if (contact == null) {
            return ActionDescription(
                "I couldn't find a contact named ${intent.contactName}."
            ) { false }
        }

        // Strip end phrases like "end message", "send it", etc.
        val message = OnDeviceSTTClient.stripEndPhrase(intent.message) ?: intent.message

        return ActionDescription(
            "Sending text to ${contact.name}: $message. Confirm?"
        ) {
            try {
                val smsManager = context.getSystemService(SmsManager::class.java)
                val parts = smsManager.divideMessage(message)
                if (parts.size == 1) {
                    smsManager.sendTextMessage(contact.phoneNumber, null, message, null, null)
                } else {
                    smsManager.sendMultipartTextMessage(contact.phoneNumber, null, parts, null, null)
                }
                Log.d(TAG, "SMS sent to ${contact.name}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send SMS", e)
                false
            }
        }
    }

    private fun prepareCall(intent: IntentResult.MakeCall): ActionDescription? {
        val contact = contactResolver.findContact(intent.contactName)
        if (contact == null) {
            return ActionDescription(
                "I couldn't find a contact named ${intent.contactName}."
            ) { false }
        }

        return ActionDescription(
            "Calling ${contact.name}. Confirm?",
            isCall = true
        ) {
            try {
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:${contact.phoneNumber}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(callIntent)
                Log.d(TAG, "Call initiated to ${contact.name}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to make call", e)
                false
            }
        }
    }

    private fun prepareReply(
        intent: IntentResult.ReplyNotification,
        recentNotifications: List<NotificationData>
    ): ActionDescription? {
        val notification = recentNotifications.getOrNull(intent.notificationIndex)
            ?: return ActionDescription("No notification to reply to.") { false }

        if (!notification.hasReply) {
            return ActionDescription(
                "The notification from ${notification.appName} doesn't support replies."
            ) { false }
        }

        return ActionDescription(
            "Replying to ${notification.appName}: ${intent.message}. Confirm?"
        ) {
            try {
                val sbn = NotificationCaptureService.getNotification(notification.key)
                    ?: return@ActionDescription false

                val replyAction = sbn.notification.actions?.find { action ->
                    action.remoteInputs?.isNotEmpty() == true
                } ?: return@ActionDescription false

                val remoteInput = replyAction.remoteInputs!!.first()
                val replyIntent = Intent().apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val bundle = Bundle().apply {
                    putCharSequence(remoteInput.resultKey, intent.message)
                }
                android.app.RemoteInput.addResultsToIntent(replyAction.remoteInputs, replyIntent, bundle)
                replyAction.actionIntent.send(context, 0, replyIntent)

                Log.d(TAG, "Reply sent to ${notification.appName}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reply to notification", e)
                false
            }
        }
    }

    private fun prepareDismiss(
        intent: IntentResult.DismissNotification,
        recentNotifications: List<NotificationData>
    ): ActionDescription? {
        val notification = recentNotifications.getOrNull(intent.notificationIndex)
            ?: return ActionDescription("No notification to dismiss.") { false }

        return ActionDescription(
            "Dismissing notification from ${notification.appName}."
        ) { true } // Dismissal doesn't need confirmation
    }

    fun prepareReaction(
        intent: IntentResult.ReactToMessage,
        recentNotifications: List<NotificationData>
    ): ActionDescription? {
        val notification = recentNotifications.getOrNull(intent.notificationIndex)
            ?: return ActionDescription("No notification to react to.") { false }

        if (!notification.hasReply) {
            return ActionDescription(
                "The notification from ${notification.appName} doesn't support reactions."
            ) { false }
        }

        val emoji = resolveEmoji(intent.emoji)
            ?: return ActionDescription("I don't know that emoji.") { false }

        val emojiName = intent.emoji.replace("_", " ")
        return ActionDescription(
            "Reacting to ${notification.appName} with $emojiName. Confirm?"
        ) {
            try {
                val sbn = NotificationCaptureService.getNotification(notification.key)
                    ?: return@ActionDescription false

                val replyAction = sbn.notification.actions?.find { action ->
                    action.remoteInputs?.isNotEmpty() == true
                } ?: return@ActionDescription false

                val remoteInput = replyAction.remoteInputs!!.first()
                val replyIntent = Intent().apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val bundle = Bundle().apply {
                    putCharSequence(remoteInput.resultKey, emoji)
                }
                android.app.RemoteInput.addResultsToIntent(replyAction.remoteInputs, replyIntent, bundle)
                replyAction.actionIntent.send(context, 0, replyIntent)

                Log.d(TAG, "Reaction $emoji sent to ${notification.appName}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to react to notification", e)
                false
            }
        }
    }
}
