package com.jarvis.app.core

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
import com.jarvis.app.JarvisApplication
import com.jarvis.app.R
import com.jarvis.app.actions.ActionExecutor
import com.jarvis.app.actions.ContactResolver
import com.jarvis.app.ai.ClaudeIntentParser
import com.jarvis.app.ai.ConversationContext
import com.jarvis.app.ai.IntentResult
import com.jarvis.app.audio.AudioFocusManager
import com.jarvis.app.notifications.NotificationCaptureService
import com.jarvis.app.notifications.NotificationData
import com.jarvis.app.notifications.NotificationQueue
import com.jarvis.app.setup.SetupActivity
import com.jarvis.app.speech.AndroidSTTClient
import com.jarvis.app.speech.TTSEngine
import com.jarvis.app.speech.WakeWordDetector
import com.jarvis.app.updater.UpdateChecker
import kotlinx.coroutines.*

class JarvisService : Service(), StateMachine.StateListener {

    private lateinit var stateMachine: StateMachine
    private lateinit var ttsEngine: TTSEngine
    private lateinit var androidSTTClient: AndroidSTTClient
    private lateinit var wakeLockManager: WakeLockManager
    private lateinit var audioFocusManager: AudioFocusManager
    private lateinit var notificationQueue: NotificationQueue
    private lateinit var conversationContext: ConversationContext
    private lateinit var contactResolver: ContactResolver
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var updateChecker: UpdateChecker
    private lateinit var wakeWordDetector: WakeWordDetector

    private var claudeParser: ClaudeIntentParser? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentNotification: NotificationData? = null
    private var pendingAction: ActionExecutor.ActionDescription? = null
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
        private const val TAG = "JarvisService"
        private const val NOTIFICATION_ID = 1
        private const val ANNOUNCE_TIMEOUT_MS = 10000L
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "JarvisService created")

        // Initialize all components
        stateMachine = StateMachine(this)
        ttsEngine = TTSEngine(this)
        androidSTTClient = AndroidSTTClient(this)
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
        wakeWordDetector = WakeWordDetector(this) {
            serviceScope.launch(Dispatchers.Main) {
                if (stateMachine.currentState == JarvisState.IDLE) {
                    vibrate(100)
                    ttsEngine.speak("Yes?") {
                        stateMachine.transition(JarvisState.LISTENING)
                    }
                }
            }
        }

        // Load API keys and initialize clients
        initializeApiClients()

        // Acquire wake lock
        wakeLockManager.acquire()

        // Start foreground
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        // Register for broadcasts from other services
        ServiceBridge.registerReceiver(
            this, broadcastReceiver,
            ServiceBridge.ACTION_NOTIFICATION,
            ServiceBridge.ACTION_BUTTON_EVENT
        )

        // Mark as running for boot receiver
        getSharedPreferences("jarvis_prefs", MODE_PRIVATE)
            .edit().putBoolean("service_running", true).apply()

        // Start periodic update checks
        updateChecker.startPeriodicChecks()

        // Start wake word detection silently
        wakeWordDetector.start()
    }

    private fun initializeApiClients() {
        try {
            val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                "jarvis_keys",
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
        Log.d(TAG, "JarvisService destroyed")

        serviceScope.cancel()
        announceTimeoutJob?.cancel()
        wakeWordDetector.destroy()
        updateChecker.destroy()
        ServiceBridge.unregisterReceiver(this, broadcastReceiver)
        ttsEngine.shutdown()
        wakeLockManager.release()
        audioFocusManager.abandonFocus()
        restoreAudioState()

        getSharedPreferences("jarvis_prefs", MODE_PRIVATE)
            .edit().putBoolean("service_running", false).apply()
    }

    // --- State Machine Listener ---

    override fun onStateChanged(oldState: JarvisState, newState: JarvisState) {
        Log.d(TAG, "State: $oldState -> $newState")

        // Stop wake word detection when leaving IDLE to free the mic
        if (newState != JarvisState.IDLE) {
            wakeWordDetector.stop()
        }

        // Save audio state when leaving IDLE, restore when returning
        if (oldState == JarvisState.IDLE && newState != JarvisState.IDLE) {
            saveAudioState()
        }

        // Never intercept volume buttons — all interaction is voice-controlled
        ServiceBridge.interceptVolumeButtons = false

        when (newState) {
            JarvisState.IDLE -> {
                announceTimeoutJob?.cancel()
                audioFocusManager.abandonFocus()
                restoreAudioState()
                // Start listening for "Hey Jarvis" when idle
                wakeWordDetector.start()
            }

            JarvisState.NOTIFY_ANNOUNCE -> {
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
                        if (stateMachine.currentState == JarvisState.NOTIFY_ANNOUNCE) {
                            val count = notificationQueue.size() + 1
                            ttsEngine.speak("$count notifications waiting.")
                            stateMachine.transition(JarvisState.IDLE)
                        }
                    }
                }
            }

            JarvisState.NOTIFY_READ -> {
                announceTimeoutJob?.cancel()
                currentNotification?.let { notif ->
                    conversationContext.setLastSpoken(notif, 0)
                    ttsEngine.speak(notif.toSpokenText()) {
                        // After reading, offer options
                        stateMachine.transition(JarvisState.NOTIFY_OPTIONS)
                    }
                }
                // Listen in parallel — "stop" will cut the TTS short
                listenForInterrupt()
            }

            JarvisState.NOTIFY_OPTIONS -> {
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

            JarvisState.LISTENING -> {
                ttsEngine.stop()
                vibrate(50) // Short buzz: "I'm listening"
                startVoiceCapture()
            }

            JarvisState.PROCESSING -> {
                ttsEngine.speak("Processing.")
                listenForInterrupt()
            }

            JarvisState.CONFIRMING -> {
                // Action description is spoken by the action preparation code
                // Listen for voice confirmation
                listenForConfirmation()
            }

            JarvisState.EXECUTING -> {
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

        // Store for later — only read when the user asks
        conversationContext.addNotification(notif)
    }

    private fun processNextNotification() {
        val next = notificationQueue.dequeue()
        if (next != null) {
            serviceScope.launch {
                delay(1000) // Brief pause between notifications
                if (stateMachine.currentState == JarvisState.IDLE) {
                    currentNotification = next
                    stateMachine.transition(JarvisState.NOTIFY_ANNOUNCE)
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
        if (stateMachine.currentState != JarvisState.NOTIFY_OPTIONS) return

        serviceScope.launch {
            val result = androidSTTClient.transcribe()
            if (stateMachine.currentState != JarvisState.NOTIFY_OPTIONS) return@launch

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
                    stateMachine.transition(JarvisState.IDLE)
                }
                repeatPatterns.any { speech.contains(it) } -> {
                    stateMachine.transition(JarvisState.NOTIFY_READ)
                }
                replyPatterns.any { speech.contains(it) } && currentNotification?.hasReply == true -> {
                    // Treat as a voice command so Claude can parse the reply message
                    handleVoiceCommandFromOptions(speech)
                }
                nextPatterns.any { speech.contains(it) } -> {
                    stateMachine.transition(JarvisState.IDLE)
                }
                dismissPatterns.any { speech.contains(it) } -> {
                    stateMachine.transition(JarvisState.IDLE)
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
            stateMachine.transition(JarvisState.IDLE)
            return
        }

        stateMachine.transition(JarvisState.PROCESSING)
        serviceScope.launch {
            val parseResult = claudeParser!!.parse(
                userCommand = speech,
                context = conversationContext,
                contactNames = contactResolver.getContactNames()
            )
            val intentResult = parseResult.getOrNull()
            if (intentResult == null) {
                ttsEngine.speak("I couldn't understand that. Try again.") {
                    stateMachine.transition(JarvisState.IDLE)
                }
                return@launch
            }
            handleParsedIntent(intentResult)
        }
    }

    private fun listenForInterrupt() {
        serviceScope.launch {
            val result = androidSTTClient.transcribe()
            val speech = result.getOrNull()?.lowercase() ?: return@launch

            val stopPatterns = listOf("stop", "jarvis stop", "shut up", "quiet", "enough", "cancel", "nevermind", "never mind")
            if (stopPatterns.any { speech.contains(it) }) {
                Log.d(TAG, "Interrupt detected: $speech")
                ttsEngine.stop()
                pendingAction = null
                pendingNotifications = emptyList()
                stateMachine.transition(JarvisState.IDLE)
            }
        }
    }

    private fun listenForAppSelection() {
        if (stateMachine.currentState == JarvisState.IDLE) return

        serviceScope.launch {
            val result = androidSTTClient.transcribe()
            if (stateMachine.currentState == JarvisState.IDLE) return@launch

            val speech = result.getOrNull()?.lowercase() ?: ""
            Log.d(TAG, "App selection: $speech")

            val stopPatterns = listOf("stop", "cancel", "nevermind", "never mind", "nothing", "no", "nope", "dismiss")
            if (stopPatterns.any { speech.contains(it) }) {
                pendingNotifications = emptyList()
                stateMachine.transition(JarvisState.IDLE)
                return@launch
            }

            val allPatterns = listOf("all", "everything", "all of them", "read them all", "read all")
            if (allPatterns.any { speech.contains(it) }) {
                val summary = buildString {
                    pendingNotifications.forEachIndexed { index, notif ->
                        append("${index + 1}. From ${notif.appName}: ${notif.toSpokenText()}. ")
                    }
                }
                ttsEngine.speak(summary) {
                    pendingNotifications = emptyList()
                    stateMachine.transition(JarvisState.IDLE)
                }
                listenForInterrupt()
                return@launch
            }

            // Match app name from speech
            val matched = pendingNotifications.filter { notif ->
                speech.contains(notif.appName.lowercase())
            }

            if (matched.isNotEmpty()) {
                val summary = buildString {
                    append("${matched.size} from ${matched.first().appName}. ")
                    matched.forEachIndexed { index, notif ->
                        append("${index + 1}. ${notif.toSpokenText()}. ")
                    }
                }
                ttsEngine.speak(summary) {
                    pendingNotifications = emptyList()
                    stateMachine.transition(JarvisState.IDLE)
                }
                listenForInterrupt()
            } else {
                ttsEngine.speak("I didn't catch which app. Say the app name, all, or cancel.") {
                    listenForAppSelection()
                }
            }
        }
    }

    private fun listenForConfirmation() {
        if (stateMachine.currentState != JarvisState.CONFIRMING) return

        serviceScope.launch {
            val result = androidSTTClient.transcribe()
            if (stateMachine.currentState != JarvisState.CONFIRMING) return@launch

            val speech = result.getOrNull()?.lowercase() ?: ""
            Log.d(TAG, "Confirmation response: $speech")

            val yesPatterns = listOf("yes", "yeah", "yep", "sure", "go ahead", "do it", "confirm", "okay", "ok", "send it", "send")
            val noPatterns = listOf("no", "nah", "nope", "cancel", "stop", "never mind", "nevermind", "don't")

            when {
                yesPatterns.any { speech.contains(it) } -> {
                    stateMachine.transition(JarvisState.EXECUTING)
                }
                noPatterns.any { speech.contains(it) } -> {
                    pendingAction = null
                    ttsEngine.speak("Cancelled.") {
                        stateMachine.transition(JarvisState.IDLE)
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
        if (stateMachine.currentState != JarvisState.NOTIFY_ANNOUNCE) return

        serviceScope.launch {
            val result = androidSTTClient.transcribe()
            // State may have changed via volume buttons while listening
            if (stateMachine.currentState != JarvisState.NOTIFY_ANNOUNCE) return@launch

            val speech = result.getOrNull()?.lowercase() ?: ""
            Log.d(TAG, "Yes/No response: $speech")

            val yesPatterns = listOf("yes", "yeah", "yep", "sure", "go ahead", "read it", "okay", "ok", "please")
            val noPatterns = listOf("no", "nah", "nope", "skip", "next", "dismiss", "ignore")

            when {
                yesPatterns.any { speech.contains(it) } -> {
                    stateMachine.transition(JarvisState.NOTIFY_READ)
                }
                noPatterns.any { speech.contains(it) } -> {
                    stateMachine.transition(JarvisState.IDLE)
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
            ttsEngine.speak("API key not configured. Please open the Jarvis app to set it up.") {
                stateMachine.transition(JarvisState.IDLE)
            }
            return
        }

        serviceScope.launch {
            // Step 1: Transcribe with Android SpeechRecognizer
            val transcriptionResult = androidSTTClient.transcribe()
            vibrate(50) // Short buzz: "Done recording"

            val transcription = transcriptionResult.getOrNull()
            if (transcription.isNullOrBlank()) {
                ttsEngine.speak("I didn't catch that. Try again.") {
                    stateMachine.transition(JarvisState.IDLE)
                }
                return@launch
            }

            stateMachine.transition(JarvisState.PROCESSING)
            Log.d(TAG, "Transcription: $transcription")

            // Step 2: Parse intent with Claude
            val parseResult = claudeParser!!.parse(
                userCommand = transcription,
                context = conversationContext,
                contactNames = contactResolver.getContactNames()
            )

            val intentResult = parseResult.getOrNull()
            if (intentResult == null) {
                ttsEngine.speak("I couldn't understand that command. Try again.") {
                    stateMachine.transition(JarvisState.IDLE)
                }
                return@launch
            }

            handleParsedIntent(intentResult)
        }
    }

    private fun handleParsedIntent(intent: IntentResult) {
        when (intent) {
            is IntentResult.ReadNotifications -> {
                val active = NotificationCaptureService.getAllActiveNotificationData()
                if (active.isEmpty()) {
                    ttsEngine.speak("No notifications.") {
                        stateMachine.transition(JarvisState.IDLE)
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
                    listenForInterrupt()
                }
            }

            is IntentResult.Repeat -> {
                val last = conversationContext.getLastSpoken()
                if (last != null) {
                    ttsEngine.speak(last.toSpokenText()) {
                        stateMachine.transition(JarvisState.NOTIFY_OPTIONS)
                    }
                } else {
                    ttsEngine.speak("Nothing to repeat.") {
                        stateMachine.transition(JarvisState.IDLE)
                    }
                }
            }

            is IntentResult.Unknown -> {
                ttsEngine.speak(intent.clarification) {
                    stateMachine.transition(JarvisState.IDLE)
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
                        stateMachine.transition(JarvisState.IDLE)
                    }
                    return
                }

                pendingAction = action
                ttsEngine.speak(action.description)
                stateMachine.transition(JarvisState.CONFIRMING)
            }
        }
    }

    // --- Action Execution ---

    private fun executeConfirmedAction() {
        val action = pendingAction
        if (action == null) {
            stateMachine.transition(JarvisState.IDLE)
            return
        }

        serviceScope.launch {
            val success = withContext(Dispatchers.IO) { action.execute() }
            pendingAction = null

            if (success) {
                ttsEngine.speak("Done.") {
                    stateMachine.transition(JarvisState.IDLE)
                }
            } else {
                ttsEngine.speak("That didn't work. Try again.") {
                    stateMachine.transition(JarvisState.IDLE)
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

        return NotificationCompat.Builder(this, JarvisApplication.CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText("Listening for commands")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
