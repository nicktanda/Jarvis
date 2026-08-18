package com.adam.app.ai

import kotlinx.serialization.Serializable

sealed class IntentResult {
    data class ReplyNotification(val notificationIndex: Int, val message: String) : IntentResult()
    data class SendSms(val contactName: String, val message: String) : IntentResult()
    data class MakeCall(val contactName: String) : IntentResult()
    object ReadNotifications : IntentResult()
    data class DismissNotification(val notificationIndex: Int) : IntentResult()
    data class WebSearch(val query: String) : IntentResult()
    data class ReactToMessage(val notificationIndex: Int, val emoji: String) : IntentResult()
    data class StartConversation(val topic: String) : IntentResult()
    data class ContinueConversation(val topic: String) : IntentResult()
    object ListConversations : IntentResult()
    data class ReadNews(val topic: String) : IntentResult()
    data class CreateCalendarEvent(val title: String, val date: String, val time: String, val duration: String) : IntentResult()
    object ReadTodayCalendar : IntentResult()
    data class SetAlarm(val hour: Int, val minute: Int, val label: String) : IntentResult()
    data class SetTimer(val seconds: Int, val label: String) : IntentResult()
    data class SetDoNotDisturb(val enabled: Boolean) : IntentResult()
    object Repeat : IntentResult()
    data class Unknown(val clarification: String) : IntentResult()
}

@Serializable
data class ParsedIntent(
    val action: String,
    val notification_index: Int = 0,
    val message: String = "",
    val contact_name: String = "",
    val clarification: String = "",
    val query: String = "",
    val emoji: String = "",
    val topic: String = "",
    val event_title: String = "",
    val event_date: String = "",
    val event_time: String = "",
    val event_duration: String = "1 hour",
    val alarm_hour: Int = -1,
    val alarm_minute: Int = 0,
    val alarm_label: String = "",
    val timer_seconds: Int = 0,
    val timer_label: String = "",
    val dnd_enabled: Boolean = false
)
