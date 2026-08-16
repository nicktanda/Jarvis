package com.adam.app.ai

import kotlinx.serialization.Serializable

sealed class IntentResult {
    data class ReplyNotification(val notificationIndex: Int, val message: String) : IntentResult()
    data class SendSms(val contactName: String, val message: String) : IntentResult()
    data class MakeCall(val contactName: String) : IntentResult()
    object ReadNotifications : IntentResult()
    data class DismissNotification(val notificationIndex: Int) : IntentResult()
    object Repeat : IntentResult()
    data class Unknown(val clarification: String) : IntentResult()
}

@Serializable
data class ParsedIntent(
    val action: String,
    val notification_index: Int = 0,
    val message: String = "",
    val contact_name: String = "",
    val clarification: String = ""
)
