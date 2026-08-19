# Adam

A screen-off, voice-controlled Android assistant for people who cannot look at a screen (motion sickness, visual impairment, etc.). All interaction happens through the speaker and microphone — no screen interaction required.

## Features

- **Configurable wake word** — choose your assistant's name (default "Adam"), with fuzzy matching for reliable detection
- **Notification management** — announces incoming notifications, reads them aloud, reply or react with emoji by voice
- **Text messages** — compose and send SMS by voice with automatic punctuation
- **Phone calls** — dial contacts by name with fuzzy matching
- **Conversations** — multi-turn voice chat with persistent history, pause and resume later
- **Web search** — answers questions using Claude's built-in web search
- **News briefing** — reads today's headlines and gives 30-second summaries on demand
- **Calendar** — create events and read today's schedule
- **Alarms & timers** — set alarms and countdown timers by voice
- **Do Not Disturb** — toggle DND mode by voice
- **Auto-updates** — checks GitHub Releases hourly and installs new versions

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                         AdamService                              │
│                    (Foreground Service)                           │
│                                                                  │
│  ┌─────────────┐    ┌──────────────┐    ┌─────────────────────┐  │
│  │ StateMachine │    │  TTSEngine   │    │   AudioPipeline     │  │
│  │             │    │  (speaker)   │    │   (16kHz mono mic)  │  │
│  └──────┬──────┘    └──────────────┘    └────────┬────────────┘  │
│         │                                        │               │
│         ▼                                  ┌─────┴──────┐        │
│  ┌─────────────┐                           │            │        │
│  │   Action    │    ┌──────────────┐  ┌────▼─────┐ ┌────▼─────┐  │
│  │  Executor   │    │  Silero VAD  │  │ Wake Word│ │  STT     │  │
│  │ (SMS, call, │    │  (ONNX)      │  │ Detector │ │  Client  │  │
│  │  calendar,  │    └──────────────┘  └──────────┘ └────┬─────┘  │
│  │  alarm,     │                                        │        │
│  │  timer,     │                                   ┌────▼─────┐  │
│  │  DND)       │                                   │ Whisper  │  │
│  └─────────────┘                                   │ (on-     │  │
│                                                    │ device)  │  │
│  ┌──────────────────────────────────────────────┐  └──────────┘  │
│  │                 AI Layer                      │               │
│  │  ClaudeIntentParser  (command → action)       │               │
│  │  ConversationEngine  (multi-turn + web search)│               │
│  │  WebSearcher         (one-shot search)        │               │
│  │  NewsReader          (headlines + summaries)   │               │
│  │  TextProcessor       (punctuation cleanup)    │               │
│  └──────────────────────────────────────────────┘               │
│                                                                  │
│  ┌──────────────────────────────────────────────┐               │
│  │              Data Layer                       │               │
│  │  Room DB  (conversations + messages)          │               │
│  │  EncryptedSharedPreferences  (API key)        │               │
│  │  ContactResolver  (fuzzy name matching)       │               │
│  └──────────────────────────────────────────────┘               │
└──────────────────────────────────────────────────────────────────┘
         ▲                    ▲                    ▲
         │                    │                    │
┌────────┴───────┐   ┌───────┴────────┐   ┌──────┴──────────┐
│  Notification  │   │ Volume Button  │   │  Package Update │
│  Capture       │   │ Service        │   │  Receiver       │
│  Service       │   │ (Accessibility)│   │  (auto-restart) │
└────────────────┘   └────────────────┘   └─────────────────┘
```

### On-Device Speech Pipeline

All speech recognition runs locally on the device — no audio leaves the phone:

1. **AudioPipeline** continuously captures 16kHz mono audio
2. **Silero VAD** (ONNX Runtime) detects speech vs. silence in real-time
3. **whisper.cpp** (C++ via JNI) transcribes detected speech on-device
4. Wake word detector listens for the configured name using fuzzy matching (Levenshtein distance)

### State Machine

```
IDLE ──── wake word ────► LISTENING ───► PROCESSING ───► CONFIRMING ───► EXECUTING ───► IDLE
  │                                          │
  │                                          ├───► CONVERSING (multi-turn loop) ───► IDLE
  │                                          │
  ▼                                          ▼
NOTIFY_ANNOUNCE ──► NOTIFY_READ ──► NOTIFY_OPTIONS ───► IDLE
```

### Services

| Service | Role |
|---------|------|
| **AdamService** | Foreground service — the brain that orchestrates everything |
| **NotificationCaptureService** | Intercepts notifications via `NotificationListenerService` |
| **VolumeButtonService** | Captures volume button presses via `AccessibilityService` (optional) |

## Installation

### From GitHub Releases

Download the latest APK from [GitHub Releases](https://github.com/nicktanda/Jarvis/releases/tag/latest) and install it on your device.

### Build from Source

Requirements: Android SDK, NDK 27.2.12479018, CMake

```bash
git clone --recursive https://github.com/nicktanda/Jarvis.git
cd Jarvis
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

> The `--recursive` flag is required to pull the whisper.cpp submodule.

### First Run

1. Open the **Adam** app
2. Grant each permission by tapping the buttons:
   - **Notification Access** — required, redirects to system settings
   - **Microphone** — required for voice commands
   - **Phone Calls**, **SMS**, **Contacts** — for calling, texting, and name resolution
   - **Calendar** — for creating and reading events
   - **Do Not Disturb** — for toggling DND mode, redirects to system settings
   - **Battery Optimization** — exempt from Doze so the service stays alive
   - **Accessibility Service** — optional, enables volume button controls
3. Set your preferred **assistant name** (wake word) — default is "Adam"
4. Enter your [Anthropic API key](https://console.anthropic.com/)
5. Tap **Start Adam**

## Usage

### Activating

Say **"Hey [Name]"** (e.g. "Hey Adam", "Hey Jarvis") — you'll hear a short vibration and "Yes?" to indicate it's listening. The name is configured in the setup screen.

### Voice Commands

| Category | Example Commands |
|----------|-----------------|
| **Notifications** | "Read my notifications", "Reply saying I'll be there soon", "React with thumbs up" |
| **Text messages** | "Send a text to Mom saying I'll be home late", "Text John" (enters dictation mode) |
| **Phone calls** | "Call Dad", "Call Sarah" |
| **Conversations** | "Tell me about black holes", "Let's talk about meal planning" |
| **Resume conversation** | "Continue conversation", "Continue conversation about meal planning" |
| **Web search** | "Search for the weather in Sydney" |
| **News** | "What's in the news?", "Tech news" (then pick a headline for a summary) |
| **Calendar** | "Add a meeting with John tomorrow at 3pm", "What's on my calendar today?" |
| **Alarms** | "Set an alarm for 7:30am", "Wake me up at 6" |
| **Timers** | "Set a 5 minute timer", "Set a timer for 90 seconds for eggs" |
| **Do Not Disturb** | "Turn on do not disturb", "Turn off silent mode" |

### Conversations

Conversations are multi-turn — after the assistant responds, it automatically listens for your follow-up. No need to say the wake word between turns.

- **Pause** — say "hold on", "pause", or just go silent for 15 seconds
- **End** — say "end conversation", "stop", "cancel", or "goodbye"
- **Resume later** — say "Hey [Name], continue conversation"
- Conversations are saved to a local database and can be resumed anytime

### Notification Flow

1. Notification arrives — Adam announces it: *"Message from WhatsApp: Mom"*
2. Say "yes" to hear it, or "no" / stay silent to skip
3. After hearing it, options are offered: *"You can say repeat, reply, react, next, or dismiss"*

### Controls

While voice is the primary interaction, volume buttons also work (requires Accessibility Service):

| State | Volume Up | Volume Down |
|-------|-----------|-------------|
| Notification announced | Hear it | Skip |
| Confirming action | Confirm | Cancel |
| Conversation / Listening | — | Cancel / End |

## API Costs

Adam uses **Claude Haiku** for all AI tasks (intent parsing, conversations, web search, news, punctuation).

- ~$0.00025 per voice command parsed
- ~$0.001-0.005 per conversation turn or web search
- Estimated at 100 commands/day: **~$3-5/month**

## Development

```bash
# Build and install
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# View logs
adb logcat -s AdamService:D OnDeviceSTT:D OnDeviceWakeWord:D ClaudeParser:D ConversationEngine:D TTSEngine:D

# Build release APK
./gradlew assembleRelease
```

## Auto-Updates

A GitHub Actions workflow builds the APK on every push to `main` and uploads it to the `latest` release. The app checks hourly:

1. Compares the release commit SHA against the running version
2. Downloads the new APK if different
3. Announces "Update available" and prompts install
4. After install, `PackageUpdateReceiver` automatically restarts the service

## Project Structure

```
app/src/main/
├── java/com/adam/app/
│   ├── AdamApplication.kt              # Notification channel setup
│   ├── setup/
│   │   └── SetupActivity.kt            # Permission and API key setup
│   ├── core/
│   │   ├── AdamService.kt              # Main service — the brain
│   │   ├── StateMachine.kt             # State enum + transitions
│   │   ├── ServiceBridge.kt            # Inter-service LocalBroadcast
│   │   └── WakeLockManager.kt          # Partial wake lock
│   ├── ai/
│   │   ├── ClaudeIntentParser.kt       # Voice command → structured intent
│   │   ├── ConversationEngine.kt       # Multi-turn chat with web search
│   │   ├── WebSearcher.kt              # One-shot web search
│   │   ├── NewsReader.kt               # Headlines + article summaries
│   │   ├── TextProcessor.kt            # Punctuation via Claude
│   │   ├── IntentResult.kt             # Sealed class for all intents
│   │   └── ConversationContext.kt      # Notification history context
│   ├── data/
│   │   ├── AppDatabase.kt              # Room database
│   │   ├── ConversationEntity.kt       # Conversation model
│   │   ├── MessageEntity.kt            # Message model
│   │   ├── ConversationDao.kt          # Conversation queries
│   │   └── MessageDao.kt               # Message queries
│   ├── speech/
│   │   ├── AudioPipeline.kt            # Continuous 16kHz audio capture
│   │   ├── SileroVadDetector.kt        # Voice activity detection (ONNX)
│   │   ├── WhisperEngine.kt            # JNI bridge to whisper.cpp
│   │   ├── OnDeviceSTTClient.kt        # Speech-to-text orchestration
│   │   ├── OnDeviceWakeWordDetector.kt # Wake word detection (fuzzy)
│   │   └── TTSEngine.kt               # Text-to-speech with queue
│   ├── actions/
│   │   ├── ActionExecutor.kt           # SMS, call, calendar, alarm, timer, DND
│   │   └── ContactResolver.kt          # Fuzzy contact name matching
│   ├── notifications/
│   │   ├── NotificationCaptureService.kt
│   │   ├── NotificationData.kt
│   │   └── NotificationQueue.kt
│   ├── accessibility/
│   │   └── VolumeButtonService.kt      # Volume button interception
│   ├── boot/
│   │   ├── BootReceiver.kt             # Auto-start on reboot
│   │   └── PackageUpdateReceiver.kt    # Auto-restart after update
│   ├── audio/
│   │   └── AudioFocusManager.kt
│   └── updater/
│       └── UpdateChecker.kt            # GitHub Releases polling
├── cpp/
│   ├── CMakeLists.txt
│   └── whisper_jni.cpp                 # JNI bridge to whisper.cpp
├── assets/
│   ├── ggml-base.en-q5_1.bin          # Whisper model (quantized)
│   └── silero_vad.onnx                # Voice activity detection model
└── res/
    └── layout/
        └── activity_setup.xml          # Setup screen
```
