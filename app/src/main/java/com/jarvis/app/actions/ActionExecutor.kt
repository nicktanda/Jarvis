package com.jarvis.app.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import com.jarvis.app.ai.IntentResult
import com.jarvis.app.notifications.NotificationCaptureService
import com.jarvis.app.notifications.NotificationData
import com.jarvis.app.notifications.NotificationQueue

class ActionExecutor(
    private val context: Context,
    private val contactResolver: ContactResolver,
    private val notificationQueue: NotificationQueue
) {

    companion object {
        private const val TAG = "ActionExecutor"
    }

    data class ActionDescription(
        val description: String,
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
            is IntentResult.Repeat -> {
                ActionDescription("Repeating") { true }
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

        return ActionDescription(
            "Sending text to ${contact.name}: ${intent.message}. Confirm?"
        ) {
            try {
                val smsManager = context.getSystemService(SmsManager::class.java)
                val parts = smsManager.divideMessage(intent.message)
                if (parts.size == 1) {
                    smsManager.sendTextMessage(contact.phoneNumber, null, intent.message, null, null)
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
            "Calling ${contact.name}. Confirm?"
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
}
