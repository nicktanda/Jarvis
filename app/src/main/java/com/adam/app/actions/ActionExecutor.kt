package com.adam.app.actions

import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.telephony.SmsManager
import android.util.Log
import com.adam.app.ai.IntentResult
import com.adam.app.notifications.NotificationCaptureService
import com.adam.app.speech.OnDeviceSTTClient
import com.adam.app.notifications.NotificationData
import com.adam.app.notifications.NotificationQueue
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

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
            is IntentResult.CreateCalendarEvent -> prepareCalendarEvent(intent)
            is IntentResult.SetAlarm -> prepareAlarm(intent)
            is IntentResult.SetTimer -> prepareTimer(intent)
            is IntentResult.SetDoNotDisturb -> prepareDoNotDisturb(intent)
            is IntentResult.StartConversation,
            is IntentResult.ContinueConversation,
            is IntentResult.ListConversations,
            is IntentResult.ReadNews,
            is IntentResult.ReadTodayCalendar -> {
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

    // --- Calendar ---

    private fun prepareCalendarEvent(intent: IntentResult.CreateCalendarEvent): ActionDescription? {
        val startMillis = parseDateTimeToMillis(intent.date, intent.time)
            ?: return ActionDescription("I couldn't understand that date or time.") { false }
        val durationMillis = parseDurationToMillis(intent.duration)
        val endMillis = startMillis + durationMillis

        val timeStr = formatTimeForSpeech(intent.time)
        return ActionDescription(
            "Adding calendar event: ${intent.title}, ${intent.date} at $timeStr. Confirm?"
        ) {
            try {
                val calendarId = getDefaultCalendarId()
                if (calendarId == null) {
                    Log.e(TAG, "No calendar found on device")
                    return@ActionDescription false
                }

                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, intent.title)
                    put(CalendarContract.Events.DTSTART, startMillis)
                    put(CalendarContract.Events.DTEND, endMillis)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }

                context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                Log.d(TAG, "Calendar event created: ${intent.title}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create calendar event", e)
                false
            }
        }
    }

    private fun getDefaultCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        ) ?: return null

        cursor.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }
        return null
    }

    private fun parseDateTimeToMillis(dateStr: String, timeStr: String): Long? {
        return try {
            val date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
            val timeParts = timeStr.split(":")
            val time = LocalTime.of(timeParts[0].toInt(), timeParts.getOrElse(1) { "0" }.toInt())
            date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse date/time: $dateStr $timeStr", e)
            null
        }
    }

    private fun parseDurationToMillis(duration: String): Long {
        val lower = duration.lowercase()
        var totalMinutes = 0L
        Regex("(\\d+)\\s*hour").find(lower)?.let { totalMinutes += it.groupValues[1].toLong() * 60 }
        Regex("(\\d+)\\s*min").find(lower)?.let { totalMinutes += it.groupValues[1].toLong() }
        if (totalMinutes == 0L) totalMinutes = 60 // default 1 hour
        return totalMinutes * 60 * 1000
    }

    private fun formatTimeForSpeech(timeStr: String): String {
        return try {
            val parts = timeStr.split(":")
            val hour = parts[0].toInt()
            val minute = parts.getOrElse(1) { "0" }.toInt()
            val amPm = if (hour < 12) "AM" else "PM"
            val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
            if (minute == 0) "$displayHour $amPm" else "$displayHour:${"%02d".format(minute)} $amPm"
        } catch (e: Exception) {
            timeStr
        }
    }

    // --- Alarm ---

    private fun prepareAlarm(intent: IntentResult.SetAlarm): ActionDescription {
        val amPm = if (intent.hour < 12) "AM" else "PM"
        val displayHour = if (intent.hour == 0) 12 else if (intent.hour > 12) intent.hour - 12 else intent.hour
        val timeStr = if (intent.minute == 0) "$displayHour $amPm"
            else "$displayHour:${"%02d".format(intent.minute)} $amPm"
        val labelStr = if (intent.label.isNotBlank()) ": ${intent.label}" else ""

        return ActionDescription(
            "Setting alarm for $timeStr$labelStr. Confirm?"
        ) {
            try {
                val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, intent.hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, intent.minute)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (intent.label.isNotBlank()) {
                        putExtra(AlarmClock.EXTRA_MESSAGE, intent.label)
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(alarmIntent)
                Log.d(TAG, "Alarm set for ${intent.hour}:${intent.minute}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set alarm", e)
                false
            }
        }
    }

    // --- Timer ---

    private fun prepareTimer(intent: IntentResult.SetTimer): ActionDescription {
        val minutes = intent.seconds / 60
        val secs = intent.seconds % 60
        val durationStr = when {
            minutes > 0 && secs > 0 -> "$minutes minute${if (minutes > 1) "s" else ""} and $secs second${if (secs > 1) "s" else ""}"
            minutes > 0 -> "$minutes minute${if (minutes > 1) "s" else ""}"
            else -> "$secs second${if (secs > 1) "s" else ""}"
        }
        val labelStr = if (intent.label.isNotBlank()) ": ${intent.label}" else ""

        return ActionDescription(
            "Setting a $durationStr timer$labelStr. Confirm?"
        ) {
            try {
                val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, intent.seconds)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (intent.label.isNotBlank()) {
                        putExtra(AlarmClock.EXTRA_MESSAGE, intent.label)
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(timerIntent)
                Log.d(TAG, "Timer set for ${intent.seconds}s")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set timer", e)
                false
            }
        }
    }

    // --- Do Not Disturb ---

    private fun prepareDoNotDisturb(intent: IntentResult.SetDoNotDisturb): ActionDescription {
        val action = if (intent.enabled) "on" else "off"
        return ActionDescription(
            "Turning Do Not Disturb $action. Confirm?"
        ) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!nm.isNotificationPolicyAccessGranted) {
                    Log.e(TAG, "DND policy access not granted")
                    return@ActionDescription false
                }
                nm.setInterruptionFilter(
                    if (intent.enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    else NotificationManager.INTERRUPTION_FILTER_ALL
                )
                Log.d(TAG, "DND set to $action")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle DND", e)
                false
            }
        }
    }
}
