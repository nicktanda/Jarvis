package com.jarvis.app.core

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
import com.jarvis.app.notifications.NotificationData
import com.jarvis.app.notifications.NotificationQueue
import com.jarvis.app.setup.SetupActivity
import com.jarvis.app.speech.AudioCaptureManager
import com.jarvis.app.speech.TTSEngine
import com.jarvis.app.speech.WhisperSTTClient
import com.jarvis.app.updater.UpdateChecker
import kotlinx.coroutines.*

class JarvisService : Service(), StateMachine.StateListener {

    private lateinit var stateMachine: StateMachine
    private lateinit var ttsEngine: TTSEngine
    private lateinit var audioCaptureManager: AudioCaptureManager
    private lateinit var wakeLockManager: WakeLockManager
    private lateinit var audioFocusManager: AudioFocusManager
    private lateinit var notificationQueue: NotificationQueue
    private lateinit var conversationContext: ConversationContext
    private lateinit var contactResolver: ContactResolver
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var updateChecker: UpdateChecker

    private var whisperClient: WhisperSTTClient? = null
    private var claudeParser: ClaudeIntentParser? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentNotification: NotificationData? = null
    private var pendingAction: ActionExecutor.ActionDescription? = null
    private var announceTimeoutJob: Job? = null

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
        audioCaptureManager = AudioCaptureManager(this)
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

        // Announce ready
        ttsEngine.speak("Jarvis is ready.")
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

            val whisperKey = prefs.getString("whisper_key", null)
            val claudeKey = prefs.getString("claude_key", null)

            if (whisperKey != null) {
                whisperClient = WhisperSTTClient(whisperKey)
            } else {
                Log.w(TAG, "Whisper API key not set")
            }

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
        updateChecker.destroy()
        ServiceBridge.unregisterReceiver(this, broadcastReceiver)
        ttsEngine.shutdown()
        wakeLockManager.release()
        audioFocusManager.abandonFocus()

        getSharedPreferences("jarvis_prefs", MODE_PRIVATE)
            .edit().putBoolean("service_running", false).apply()
    }

    // --- State Machine Listener ---

    override fun onStateChanged(oldState: JarvisState, newState: JarvisState) {
        Log.d(TAG, "State: $oldState -> $newState")

        when (newState) {
            JarvisState.IDLE -> {
                announceTimeoutJob?.cancel()
                audioFocusManager.abandonFocus()
                // Check if there are queued notifications
                processNextNotification()
            }

            JarvisState.NOTIFY_ANNOUNCE -> {
                currentNotification?.let { notif ->
                    audioFocusManager.requestFocus()
                    vibrate(100)
                    ttsEngine.speak(notif.toAnnouncement())

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
            }

            JarvisState.NOTIFY_OPTIONS -> {
                val options = buildString {
                    append("You can repeat")
                    if (currentNotification?.hasReply == true) {
                        append(", reply")
                    }
                    append(", or dismiss.")
                }
                ttsEngine.speak(options)
            }

            JarvisState.LISTENING -> {
                ttsEngine.stop()
                vibrate(50) // Short buzz: "I'm listening"
                startVoiceCapture()
            }

            JarvisState.PROCESSING -> {
                ttsEngine.speak("Processing.")
            }

            JarvisState.CONFIRMING -> {
                // Action description is spoken by the action preparation code
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

        conversationContext.addNotification(notif)

        if (stateMachine.currentState == JarvisState.IDLE) {
            currentNotification = notif
            stateMachine.transition(JarvisState.NOTIFY_ANNOUNCE)
        } else {
            // Queue for later
            notificationQueue.enqueue(notif)
        }
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

    // --- Voice Capture & Processing ---

    private fun startVoiceCapture() {
        if (whisperClient == null || claudeParser == null) {
            ttsEngine.speak("API keys not configured. Please open the Jarvis app to set them up.") {
                stateMachine.transition(JarvisState.IDLE)
            }
            return
        }

        serviceScope.launch {
            val audioFile = audioCaptureManager.startRecording()
            vibrate(50) // Short buzz: "Done recording"

            if (audioFile == null) {
                ttsEngine.speak("I couldn't record audio. Please try again.") {
                    stateMachine.transition(JarvisState.IDLE)
                }
                return@launch
            }

            stateMachine.transition(JarvisState.PROCESSING)

            // Step 1: Transcribe with Whisper
            val transcriptionResult = whisperClient!!.transcribe(audioFile)
            audioFile.delete() // Clean up temp file

            val transcription = transcriptionResult.getOrNull()
            if (transcription.isNullOrBlank()) {
                ttsEngine.speak("I didn't catch that. Try again.") {
                    stateMachine.transition(JarvisState.IDLE)
                }
                return@launch
            }

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
                val count = notificationQueue.size()
                if (count == 0 && currentNotification == null) {
                    ttsEngine.speak("No notifications.") {
                        stateMachine.transition(JarvisState.IDLE)
                    }
                } else {
                    stateMachine.transition(JarvisState.IDLE) // Will trigger processNextNotification
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
