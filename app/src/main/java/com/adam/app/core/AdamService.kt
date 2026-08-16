package com.adam.app.core

import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.adam.app.AdamApplication
import com.adam.app.R
import com.adam.app.actions.ActionExecutor
import com.adam.app.actions.ContactResolver
import com.adam.app.ai.ClaudeIntentParser
import com.adam.app.ai.ConversationContext
import com.adam.app.ai.IntentResult
import com.adam.app.audio.AudioFocusManager
import com.adam.app.notifications.NotificationCaptureService
import com.adam.app.notifications.NotificationData
import com.adam.app.notifications.NotificationQueue
import com.adam.app.setup.SetupActivity
import com.adam.app.speech.AudioPipeline
import com.adam.app.speech.OnDeviceSTTClient
import com.adam.app.speech.OnDeviceWakeWordDetector
import com.adam.app.speech.SileroVadDetector
import com.adam.app.speech.TTSEngine
import com.adam.app.speech.WhisperEngine
import com.adam.app.updater.UpdateChecker
import kotlinx.coroutines.*

class AdamService : Service(), StateMachine.StateListener {

    private lateinit var stateMachine: StateMachine
    private lateinit var ttsEngine: TTSEngine
    private lateinit var audioPipeline: AudioPipeline
    private lateinit var vadDetector: SileroVadDetector
    private lateinit var whisperEngine: WhisperEngine
    private lateinit var sttClient: OnDeviceSTTClient
    private lateinit var wakeLockManager: WakeLockManager
    private lateinit var audioFocusManager: AudioFocusManager
    private lateinit var notificationQueue: NotificationQueue
    private lateinit var conversationContext: ConversationContext
    private lateinit var contactResolver: ContactResolver
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var updateChecker: UpdateChecker
    private lateinit var wakeWordDetector: OnDeviceWakeWordDetector

    private var claudeParser: ClaudeIntentParser? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentNotification: NotificationData? = null
    private var pendingAction: ActionExecutor.ActionDescription? = null
    private var pendingSmsContact: String? = null
    private var pendingSmsFirstChunk: String? = null
    private var pendingClarification: String? = null
    private var pendingNotifications: List<NotificationData> = emptyList()
    private var announceTimeoutJob: Job? = null
    private var savedRingerMode: Int = -1
    private var savedAlarmVolume: Int = -1

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ServiceBridge.ACTION_NOTIFICATION -> handleNotificationBroadcast(intent)
                ServiceBridge.ACTION_BUTTON_EVENT -> handleButtonBroadcast(intent)
            }
        }
    }

    companion object {
        private const val TAG = "AdamService"
        private const val NOTIFICATION_ID = 1
        private const val ANNOUNCE_TIMEOUT_MS = 10000L
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AdamService created")

        // Initialize all components
        stateMachine = StateMachine(this)
        ttsEngine = TTSEngine(this)
        wakeLockManager = WakeLockManager(this)
        audioFocusManager = AudioFocusManager(this)
        notificationQueue = NotificationQueue()
        conversationContext = ConversationContext()
        contactResolver = ContactResolver(this)
        actionExecutor = ActionExecutor(this, contactResolver, notificationQueue)
        updateChecker = UpdateChecker(this) { status ->
            serviceScope.launch(Dispatchers.Main) {
                ttsEngine.speak(status)
            }
        }

        // On-device speech pipeline
        audioPipeline = AudioPipeline(this)
        vadDetector = SileroVadDetector(this)
        whisperEngine = WhisperEngine(this)
        sttClient = OnDeviceSTTClient(audioPipeline, vadDetector, whisperEngine)
        wakeWordDetector = OnDeviceWakeWordDetector(audioPipeline, vadDetector, whisperEngine) {
            serviceScope.launch(Dispatchers.Main) {
                if (stateMachine.currentState == AdamState.IDLE) {
                    vibrate(100)
                    ttsEngine.speak("Yes?") {
                        stateMachine.transition(AdamState.LISTENING)
                    }
                }
            }
        }

        // Load API keys and initialize clients
        initializeApiClients()

        // Acquire wake lock
        wakeLockManager.acquire()

        // Start foreground — try with microphone type, fall back to default
        // (background-started services can't request mic type on Android 14+)
        try {
            startForeground(
                NOTIFICATION_ID,
                buildForegroundNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Mic foreground type denied (background start), using default", e)
            startForeground(NOTIFICATION_ID, buildForegroundNotification())
        }

        // Register for broadcasts from other services
        ServiceBridge.registerReceiver(
            this, broadcastReceiver,
            ServiceBridge.ACTION_NOTIFICATION,
            ServiceBridge.ACTION_BUTTON_EVENT
        )

        // Mark as running for boot receiver
        getSharedPreferences("adam_prefs", MODE_PRIVATE)
            .edit().putBoolean("service_running", true).apply()

        // Start periodic update checks
        updateChecker.startPeriodicChecks()

        // Initialize whisper model and start audio pipeline
        serviceScope.launch {
            try {
                whisperEngine.init()
                audioPipeline.start()
                wakeWordDetector.start()
                Log.d(TAG, "On-device speech pipeline ready")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize speech pipeline", e)
                ttsEngine.speak("Speech engine failed to load. Voice commands unavailable.")
            }
        }
    }

    private fun initializeApiClients() {
        try {
            val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                "adam_keys",
                androidx.security.crypto.MasterKeys.getOrCreate(
                    androidx.security.crypto.MasterKeys.AES256_GCM_SPEC
                ),
                this,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val claudeKey = prefs.getString("claude_key", null)

            if (claudeKey != null) {
                claudeParser = ClaudeIntentParser(claudeKey)
            } else {
                Log.w(TAG, "Claude API key not set")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load API keys", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AdamService destroyed")

        serviceScope.cancel()
        announceTimeoutJob?.cancel()
        wakeWordDetector.destroy()
        audioPipeline.stop()
        whisperEngine.release()
        vadDetector.release()
        updateChecker.destroy()
        ServiceBridge.unregisterReceiver(this, broadcastReceiver)
        ttsEngine.shutdown()
        wakeLockManager.release()
        audioFocusManager.abandonFocus()
        restoreAudioState()

        getSharedPreferences("adam_prefs", MODE_PRIVATE)
            .edit().putBoolean("service_running", false).apply()
    }

    // --- State Machine Listener ---

    override fun onStateChanged(oldState: AdamState, newState: AdamState) {
        Log.d(TAG, "State: $oldState -> $newState")

        // Stop wake word detection when leaving IDLE to free the mic
        if (newState != AdamState.IDLE) {
            wakeWordDetector.stop()
        }

        // Save audio state when leaving IDLE, restore when returning
        if (oldState == AdamState.IDLE && newState != AdamState.IDLE) {
            saveAudioState()
        }

        // Never intercept volume buttons — all interaction is voice-controlled
        ServiceBridge.interceptVolumeButtons = false

        when (newState) {
            AdamState.IDLE -> {
                pendingSmsContact = null
                pendingSmsFirstChunk = null
                pendingClarification = null
                announceTimeoutJob?.cancel()
                audioFocusManager.abandonFocus()
                restoreAudioState()
                // Resume wake word detection (just re-registers as AudioPipeline listener)
                wakeWordDetector.start()

                // Process any queued notifications
                processNextNotification()
            }

            AdamState.NOTIFY_ANNOUNCE -> {
                currentNotification?.let { notif ->
                    audioFocusManager.requestFocus()
                    vibrate(100)
                    ttsEngine.speak(notif.toAnnouncement()) {
                        // After announcement finishes, listen for yes/no
                        listenForYesNo()
                    }

                    // Timeout: if no response in 10 seconds, queue it
                    announceTimeoutJob?.cancel()
                    announceTimeoutJob = serviceScope.launch {
                        delay(ANNOUNCE_TIMEOUT_MS)
                        if (stateMachine.currentState == AdamState.NOTIFY_ANNOUNCE) {
                            val count = notificationQueue.size() + 1
                            ttsEngine.speak("$count notifications waiting.")
                            stateMachine.transition(AdamState.IDLE)
                        }
                    }
                }
            }

            AdamState.NOTIFY_READ -> {
                announceTimeoutJob?.cancel()
                currentNotification?.let { notif ->
                    conversationContext.setLastSpoken(notif, 0)
                    NotificationCaptureService.dismissAndMarkRead(notif.key)
                    ttsEngine.speak(notif.toSpokenText()) {
                        // After reading, offer options
                        stateMachine.transition(AdamState.NOTIFY_OPTIONS)
                    }
                }
                // (interrupt detection removed — mic picks up TTS with on-device pipeline)
            }

            AdamState.NOTIFY_OPTIONS -> {
                val options = buildString {
                    append("You can say repeat")
                    if (currentNotification?.hasReply == true) {
                        append(", reply")
                    }
                    append(", next, or dismiss.")
                }
                ttsEngine.speak(options) {
                    listenForNotificationCommand()
                }
            }

            AdamState.LISTENING -> {
                ttsEngine.stop()
                vibrate(50) // Short buzz: "I'm listening"
                startVoiceCapture()
            }

            AdamState.PROCESSING -> {
                ttsEngine.speak("Processing.")
            }

            AdamState.CONFIRMING -> {
                // Action description is spoken by the action preparation code
                // Listen for voice confirmation
                listenForConfirmation()
            }

            AdamState.EXECUTING -> {
                executeConfirmedAction()
            }
        }
    }

    // --- Notification Handling ---

    private fun handleNotificationBroadcast(intent: Intent) {
        val notif = NotificationData(
            key = intent.getStringExtra(ServiceBridge.EXTRA_NOTIFICATION_KEY) ?: return,
            packageName = intent.getStringExtra(ServiceBridge.EXTRA_PACKAGE_NAME) ?: "",
            appName = intent.getStringExtra(ServiceBridge.EXTRA_APP_NAME) ?: "Unknown",
            title = intent.getStringExtra(ServiceBridge.EXTRA_TITLE) ?: "",
            text = intent.getStringExtra(ServiceBridge.EXTRA_TEXT) ?: "",
            hasReply = intent.getBooleanExtra(ServiceBridge.EXTRA_HAS_REPLY, false)
        )

        conversationContext.addNotification(notif)

        // Respect Do Not Disturb — silently queue notifications
        if (isDoNotDisturbActive()) {
            Log.d(TAG, "DND active, queuing notification from ${notif.appName}")
            notificationQueue.enqueue(notif)
            return
        }

        // If idle, announce it. Otherwise queue for later.
        if (stateMachine.currentState == AdamState.IDLE) {
            currentNotification = notif
            stateMachine.transition(AdamState.NOTIFY_ANNOUNCE)
        } else {
            notificationQueue.enqueue(notif)
        }
    }

    private fun processNextNotification() {
        val next = notificationQueue.dequeue()
        if (next != null) {
            // Don't announce during Do Not Disturb
            if (isDoNotDisturbActive()) {
                notificationQueue.enqueue(next)
                return
            }
            serviceScope.launch {
                delay(1000) // Brief pause between notifications
                if (stateMachine.currentState == AdamState.IDLE) {
                    currentNotification = next
                    stateMachine.transition(AdamState.NOTIFY_ANNOUNCE)
                } else {
                    // Re-queue if state changed
                    notificationQueue.enqueue(next)
                }
            }
        }
    }

    // --- Button Handling ---

    private fun handleButtonBroadcast(intent: Intent) {
        val eventName = intent.getStringExtra(ServiceBridge.EXTRA_BUTTON_EVENT) ?: return
        val event = try {
            ButtonEvent.valueOf(eventName)
        } catch (e: IllegalArgumentException) {
            return
        }

        stateMachine.handleButtonEvent(event)
    }

    // --- Voice Response Handling ---

    private fun listenForNotificationCommand() {
        if (stateMachine.currentState != AdamState.NOTIFY_OPTIONS) return

        serviceScope.launch {
            val result = sttClient.transcribe()
            if (stateMachine.currentState != AdamState.NOTIFY_OPTIONS) return@launch

            val speech = result.getOrNull()?.lowercase() ?: ""
            Log.d(TAG, "Notification command: $speech")

            val repeatPatterns = listOf("repeat", "again", "read it again", "say it again")
            val replyPatterns = listOf("reply", "respond", "send", "write back", "tell them", "say")
            val dismissPatterns = listOf("dismiss", "done", "clear", "close", "delete")
            val nextPatterns = listOf("next", "skip", "move on")
            val stopPatterns = listOf("stop", "shut up", "quiet", "enough", "cancel")

            when {
                stopPatterns.any { speech.contains(it) } -> {
                    ttsEngine.stop()
                    stateMachine.transition(AdamState.IDLE)
                }
                repeatPatterns.any { speech.contains(it) } -> {
                    stateMachine.transition(AdamState.NOTIFY_READ)
                }
                replyPatterns.any { speech.contains(it) } && currentNotification?.hasReply == true -> {
                    // Treat as a voice command so Claude can parse the reply message
                    handleVoiceCommandFromOptions(speech)
                }
                nextPatterns.any { speech.contains(it) } -> {
                    stateMachine.transition(AdamState.IDLE)
                }
                dismissPatterns.any { speech.contains(it) } -> {
                    stateMachine.transition(AdamState.IDLE)
                }
                else -> {
                    // Might be a reply or command — send to Claude for parsing
                    handleVoiceCommandFromOptions(speech)
                }
            }
        }
    }

    private fun handleVoiceCommandFromOptions(speech: String) {
        if (claudeParser == null) {
            stateMachine.transition(AdamState.IDLE)
            return
        }

        stateMachine.transition(AdamState.PROCESSING)
        serviceScope.launch {
            val parseResult = claudeParser!!.parse(
                userCommand = speech,
                context = conversationContext,
                contactNames = contactResolver.getContactNames()
            )
            val intentResult = parseResult.getOrNull()
            if (intentResult == null) {
                ttsEngine.speak("I couldn't understand that. Try again.") {
                    stateMachine.transition(AdamState.IDLE)
                }
                return@launch
            }
            handleParsedIntent(intentResult)
        }
    }

    private fun listenForInterrupt() {
        serviceScope.launch {
            val result = sttClient.transcribe()
            val speech = result.getOrNull()?.lowercase() ?: return@launch

            val stopPatterns = listOf("stop", "adam stop", "shut up", "quiet", "enough", "cancel", "nevermind", "never mind")
            if (stopPatterns.any { speech.contains(it) }) {
                Log.d(TAG, "Interrupt detected: $speech")
                ttsEngine.stop()
                pendingAction = null
                pendingNotifications = emptyList()
                stateMachine.transition(AdamState.IDLE)
            }
        }
    }

    private fun listenForAppSelection() {
        if (stateMachine.currentState == AdamState.IDLE) return

        serviceScope.launch {
            val result = sttClient.transcribe()
            if (stateMachine.currentState == AdamState.IDLE) return@launch

            val speech = result.getOrNull()?.lowercase() ?: ""
            Log.d(TAG, "App selection: $speech")

            val stopPatterns = listOf("stop", "cancel", "nevermind", "never mind", "nothing", "none", "nope", "dismiss", "i'm good", "that's it", "that's all")
            val words = speech.replace(Regex("[.,!?]"), "").split(" ").map { it.trim() }
            if (stopPatterns.any { pattern -> words.any { it == pattern } }) {
                pendingNotifications = emptyList()
                ttsEngine.speak("Okay.") {
                    stateMachine.transition(AdamState.IDLE)
                }
                return@launch
            }

            val allPatterns = listOf("all", "everything", "all of them", "read them all", "read all")
            if (allPatterns.any { speech.contains(it) }) {
                pendingNotifications.forEach { NotificationCaptureService.dismissAndMarkRead(it.key) }
                val summary = buildString {
                    pendingNotifications.forEachIndexed { index, notif ->
                        append("${index + 1}. From ${notif.appName}: ${notif.toSpokenText()}. ")
                    }
                }
                ttsEngine.speak(summary) {
                    pendingNotifications = emptyList()
                    stateMachine.transition(AdamState.IDLE)
                }
                return@launch
            }

            // Match app name from speech
            Log.d(TAG, "Matching against ${pendingNotifications.size} pending notifications:")
            pendingNotifications.forEach { Log.d(TAG, "  appName='${it.appName}'") }
            val matched = pendingNotifications.filter { notif ->
                speech.contains(notif.appName.lowercase())
            }

            if (matched.isNotEmpty()) {
                matched.forEach { NotificationCaptureService.dismissAndMarkRead(it.key) }
                val summary = buildString {
                    append("${matched.size} from ${matched.first().appName}. ")
                    matched.forEachIndexed { index, notif ->
                        append("${index + 1}. ${notif.toSpokenText()}. ")
                    }
                }
                ttsEngine.speak(summary) {
                    pendingNotifications = emptyList()
                    stateMachine.transition(AdamState.IDLE)
                }
            } else {
                ttsEngine.speak("I didn't catch which app. Say the app name, all, or cancel.") {
                    listenForAppSelection()
                }
            }
        }
    }

    private fun listenForConfirmation() {
        if (stateMachine.currentState != AdamState.CONFIRMING) return

        serviceScope.launch {
            val result = sttClient.transcribe()
            if (stateMachine.currentState != AdamState.CONFIRMING) return@launch

            val speech = result.getOrNull()?.lowercase() ?: ""
            Log.d(TAG, "Confirmation response: $speech")

            val yesPatterns = listOf("yes", "yeah", "yep", "sure", "go ahead", "do it", "confirm", "okay", "ok", "send it", "send")
            val noPatterns = listOf("no", "nah", "nope", "cancel", "stop", "never mind", "nevermind", "don't")

            when {
                yesPatterns.any { speech.contains(it) } -> {
                    stateMachine.transition(AdamState.EXECUTING)
                }
                noPatterns.any { speech.contains(it) } -> {
                    pendingAction = null
                    ttsEngine.speak("Cancelled.") {
                        stateMachine.transition(AdamState.IDLE)
                    }
                }
                else -> {
                    ttsEngine.speak("Say yes to confirm or no to cancel.") {
                        listenForConfirmation()
                    }
                }
            }
        }
    }

    private fun listenForYesNo() {
        if (stateMachine.currentState != AdamState.NOTIFY_ANNOUNCE) return

        serviceScope.launch {
            val result = sttClient.transcribe()
            // State may have changed via volume buttons while listening
            if (stateMachine.currentState != AdamState.NOTIFY_ANNOUNCE) return@launch

            val speech = result.getOrNull()?.lowercase() ?: ""
            Log.d(TAG, "Yes/No response: $speech")

            val yesPatterns = listOf("yes", "yeah", "yep", "sure", "go ahead", "read it", "okay", "ok", "please")
            val noPatterns = listOf("no", "nah", "nope", "skip", "next", "dismiss", "ignore")

            when {
                yesPatterns.any { speech.contains(it) } -> {
                    stateMachine.transition(AdamState.NOTIFY_READ)
                }
                noPatterns.any { speech.contains(it) } -> {
                    stateMachine.transition(AdamState.IDLE)
                }
                else -> {
                    // Didn't understand, try again
                    listenForYesNo()
                }
            }
        }
    }

    // --- Voice Capture & Processing ---

    private fun startVoiceCapture() {
        if (claudeParser == null) {
            ttsEngine.speak("API key not configured. Please open the Adam app to set it up.") {
                stateMachine.transition(AdamState.IDLE)
            }
            return
        }

        serviceScope.launch {
            // Step 1: Transcribe with Android SpeechRecognizer
            val transcriptionResult = sttClient.transcribe()
            vibrate(50) // Short buzz: "Done recording"

            val transcription = transcriptionResult.getOrNull()
            if (transcription.isNullOrBlank()) {
                pendingSmsContact = null
                pendingSmsFirstChunk = null
                pendingClarification = null
                ttsEngine.speak("I didn't catch that. Try again.") {
                    stateMachine.transition(AdamState.IDLE)
                }
                return@launch
            }

            Log.d(TAG, "Transcription: $transcription")

            // If we were collecting a message body for SMS, use dictation mode
            val smsContact = pendingSmsContact
            if (smsContact != null) {
                pendingSmsContact = null
                val firstChunk = pendingSmsFirstChunk
                pendingSmsFirstChunk = null

                // Build parts list: any prior chunk from Claude + this transcription
                val allParts = mutableListOf<String>()
                if (firstChunk != null) allParts.add(firstChunk)

                // Check if this chunk has an end phrase
                val stripped = OnDeviceSTTClient.stripEndPhrase(transcription)
                if (stripped != null) {
                    if (stripped.isNotBlank()) allParts.add(stripped)
                    val fullMessage = allParts.joinToString(" ")
                    vibrate(50)
                    if (fullMessage.isBlank()) {
                        ttsEngine.speak("I didn't catch a message.") {
                            stateMachine.transition(AdamState.IDLE)
                        }
                    } else {
                        // Message complete — go straight to confirmation
                        val action = actionExecutor.prepare(
                            IntentResult.SendSms(smsContact, fullMessage),
                            conversationContext.getRecentNotifications()
                        )
                        if (action != null) {
                            pendingAction = action
                            ttsEngine.speak(action.description) {
                                stateMachine.transition(AdamState.CONFIRMING)
                            }
                        }
                    }
                    return@launch
                }

                // No end phrase yet — add this chunk and continue dictation
                allParts.add(transcription)
                val dictationResult = sttClient.transcribeDictation()
                val restOfMessage = dictationResult.getOrNull() ?: ""
                if (restOfMessage.isNotBlank()) allParts.add(restOfMessage)

                val fullMessage = allParts.joinToString(" ")
                vibrate(50)
                if (fullMessage.isBlank()) {
                    ttsEngine.speak("I didn't catch a message.") {
                        stateMachine.transition(AdamState.IDLE)
                    }
                } else {
                    val action = actionExecutor.prepare(
                        IntentResult.SendSms(smsContact, fullMessage),
                        conversationContext.getRecentNotifications()
                    )
                    if (action != null) {
                        pendingAction = action
                        ttsEngine.speak(action.description) {
                            stateMachine.transition(AdamState.CONFIRMING)
                        }
                    }
                }
                return@launch
            }

            // If this is a follow-up to a clarification, give Claude context
            val clarification = pendingClarification
            pendingClarification = null
            val commandForParsing = if (clarification != null) {
                "I was asked: \"$clarification\" My answer: \"$transcription\""
            } else {
                transcription
            }

            stateMachine.transition(AdamState.PROCESSING)

            // Step 2: Parse intent with Claude
            val parseResult = claudeParser!!.parse(
                userCommand = commandForParsing,
                context = conversationContext,
                contactNames = contactResolver.getContactNames()
            )

            val intentResult = parseResult.getOrNull()
            if (intentResult == null) {
                ttsEngine.speak("I couldn't understand that command. Try again.") {
                    stateMachine.transition(AdamState.IDLE)
                }
                return@launch
            }

            handleParsedIntent(intentResult)
        }
    }

    private fun handleParsedIntent(intent: IntentResult) {
        // Handle SMS intents
        if (intent is IntentResult.SendSms) {
            if (intent.message.isNotBlank()) {
                // Message already provided (by Claude or user) — strip any end phrase and confirm
                val message = OnDeviceSTTClient.stripEndPhrase(intent.message) ?: intent.message
                val action = actionExecutor.prepare(
                    IntentResult.SendSms(intent.contactName, message),
                    conversationContext.getRecentNotifications()
                )
                if (action != null) {
                    pendingAction = action
                    ttsEngine.speak(action.description) {
                        stateMachine.transition(AdamState.CONFIRMING)
                    }
                } else {
                    ttsEngine.speak("I couldn't prepare that action.") {
                        stateMachine.transition(AdamState.IDLE)
                    }
                }
                return
            }

            // No message — enter dictation mode
            pendingSmsContact = intent.contactName
            pendingSmsFirstChunk = null

            ttsEngine.speak("What would you like to say to ${intent.contactName}? Say end message when you're done.") {
                stateMachine.transition(AdamState.LISTENING)
            }
            return
        }

        when (intent) {
            is IntentResult.ReadNotifications -> {
                val active = NotificationCaptureService.getAllActiveNotificationData()
                if (active.isEmpty()) {
                    ttsEngine.speak("No notifications.") {
                        stateMachine.transition(AdamState.IDLE)
                    }
                } else {
                    // Group by app and just list app names with counts
                    val grouped = active.groupBy { it.appName }
                    val summary = buildString {
                        append("You have ${active.size} notification${if (active.size > 1) "s" else ""}. ")
                        grouped.entries.forEachIndexed { index, (appName, notifs) ->
                            if (index > 0) append(", ")
                            append("${notifs.size} from $appName")
                        }
                        append(". Which app would you like to hear?")
                    }
                    pendingNotifications = active
                    ttsEngine.speak(summary) {
                        listenForAppSelection()
                    }
                }
            }

            is IntentResult.Repeat -> {
                val last = conversationContext.getLastSpoken()
                if (last != null) {
                    ttsEngine.speak(last.toSpokenText()) {
                        stateMachine.transition(AdamState.NOTIFY_OPTIONS)
                    }
                } else {
                    ttsEngine.speak("Nothing to repeat.") {
                        stateMachine.transition(AdamState.IDLE)
                    }
                }
            }

            is IntentResult.Unknown -> {
                pendingClarification = intent.clarification
                ttsEngine.speak(intent.clarification) {
                    stateMachine.transition(AdamState.LISTENING)
                }
            }

            else -> {
                // Actions that need confirmation
                val action = actionExecutor.prepare(
                    intent,
                    conversationContext.getRecentNotifications()
                )

                if (action == null) {
                    ttsEngine.speak("I couldn't prepare that action.") {
                        stateMachine.transition(AdamState.IDLE)
                    }
                    return
                }

                pendingAction = action
                ttsEngine.speak(action.description) {
                    stateMachine.transition(AdamState.CONFIRMING)
                }
            }
        }
    }

    // --- Action Execution ---

    private fun executeConfirmedAction() {
        val action = pendingAction
        if (action == null) {
            stateMachine.transition(AdamState.IDLE)
            return
        }

        // For calls: release audio before dialing so the phone app gets clean audio routing
        if (action.isCall) {
            ttsEngine.stop()
            audioFocusManager.abandonFocus()
            restoreAudioState()
        }

        serviceScope.launch {
            val success = withContext(Dispatchers.IO) { action.execute() }
            pendingAction = null

            if (action.isCall) {
                // Don't speak over the call — just go idle silently
                stateMachine.transition(AdamState.IDLE)
            } else if (success) {
                ttsEngine.speak("Done.") {
                    stateMachine.transition(AdamState.IDLE)
                }
            } else {
                ttsEngine.speak("That didn't work. Try again.") {
                    stateMachine.transition(AdamState.IDLE)
                }
            }
        }
    }

    // --- Audio State Management ---

    private fun saveAudioState() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedRingerMode = audioManager.ringerMode
            savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            Log.d(TAG, "Saved audio state: ringer=$savedRingerMode, alarm=$savedAlarmVolume")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save audio state", e)
        }
    }

    private fun isDoNotDisturbActive(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun restoreAudioState() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (savedAlarmVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
            }
            if (savedRingerMode >= 0) {
                audioManager.ringerMode = savedRingerMode
            }
            Log.d(TAG, "Restored audio state: ringer=$savedRingerMode, alarm=$savedAlarmVolume")
            savedRingerMode = -1
            savedAlarmVolume = -1
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore audio state", e)
        }
    }

    // --- Utilities ---

    private fun vibrate(durationMs: Long) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(
            VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, SetupActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AdamApplication.CHANNEL_ID)
            .setContentTitle("Adam")
            .setContentText("Listening for commands")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
