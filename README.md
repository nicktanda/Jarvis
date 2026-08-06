# Jarvis

A screen-off, voice-controlled Android assistant for people who cannot look at a screen (motion sickness, visual impairment, etc.). The phone speaks through its speaker and listens through its mic — no Bluetooth headphones, no screen interaction required.

## What It Does

- **Reads notifications aloud** — announces incoming notifications and asks if you want to hear them
- **Voice commands** — natural language commands like "reply saying I'll be late", "text Mom I'm on my way", "call Dad"
- **Notification replies** — replies directly through notification actions (works with WhatsApp, SMS, Gmail, Slack, etc.)
- **Sends SMS** — composes and sends text messages by voice
- **Makes calls** — dials contacts by name with fuzzy matching
- **Auto-updates** — checks GitHub Releases hourly and prompts to install new versions

## How It Works

The app runs as a foreground service with the screen off. Three Android services work together:

| Service | Role |
|---------|------|
| **JarvisService** | The brain — foreground service that orchestrates everything, holds the wake lock |
| **NotificationCaptureService** | Intercepts all notifications via `NotificationListenerService` |
| **VolumeButtonService** | Captures volume button presses via `AccessibilityService` |

**Voice pipeline:** Volume button → AudioRecord (16kHz WAV) → Whisper API (speech-to-text) → Claude API (intent parsing) → Action execution

## Controls

All interaction happens via volume buttons with the screen off:

| State | Volume Up | Volume Down | Long Press Vol Down |
|-------|-----------|-------------|---------------------|
| Notification announced | Hear it | Skip | Voice command |
| Notification read | — | — | Voice command |
| Options ("repeat, reply, dismiss") | Repeat | Dismiss | Voice command (e.g. "reply saying...") |
| Confirming action | Yes, execute | Cancel | — |
| Any state | — | — | Activate voice command |

## Requirements

- Android 8.0+ (API 26)
- [Anthropic API key](https://console.anthropic.com/) (for Claude intent parsing)
- [OpenAI API key](https://platform.openai.com/) (for Whisper speech-to-text)

## Setup

### Install

```bash
# Build from source
./gradlew assembleDebug

# Install via ADB
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or download the latest APK from [GitHub Releases](https://github.com/nicktanda/Jarvis/releases/tag/latest).

### First Run

1. Open the Jarvis app
2. Grant each permission by tapping each button:
   - **Notification Access** — redirects to system settings
   - **Accessibility Service** — redirects to system settings
   - **Microphone**, **Phone**, **SMS**, **Contacts** — runtime permission prompts
   - **Battery Optimization** — exempt from Doze mode
3. Enter your Anthropic (Claude) and OpenAI (Whisper) API keys
4. Tap **Start Jarvis**
5. Turn off the screen — you'll hear "Jarvis is ready"

### Permissions Explained

| Permission | Why |
|-----------|-----|
| `FOREGROUND_SERVICE` + `WAKE_LOCK` | Keep running with screen off |
| `RECORD_AUDIO` | Voice command input |
| `SEND_SMS` | Send text messages by voice |
| `CALL_PHONE` | Make phone calls by voice |
| `READ_CONTACTS` | Resolve spoken names to contacts |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Read incoming notifications |
| `BIND_ACCESSIBILITY_SERVICE` | Intercept volume buttons |
| `REQUEST_INSTALL_PACKAGES` | Self-update from GitHub Releases |

## Architecture

```
┌─────────────────────────────────────────────┐
│              JarvisService                   │
│         (Foreground Service)                 │
│                                              │
│  StateMachine ←→ TTSEngine (speaker output)  │
│  AudioCaptureManager (mic input)             │
│  WhisperSTTClient → ClaudeIntentParser       │
│  ActionExecutor (SMS, call, reply)           │
│  ContactResolver (fuzzy name matching)       │
│  UpdateChecker (GitHub Releases polling)     │
│  NotificationQueue                           │
└──────────────────────────────────────────────┘
         ▲                  ▲
         │                  │
┌────────┴───────┐  ┌──────┴──────────┐
│ Notification   │  │ Volume Button   │
│ Capture        │  │ Service         │
│ Service        │  │ (Accessibility) │
└────────────────┘  └─────────────────┘
```

### State Machine

```
IDLE → NOTIFY_ANNOUNCE → NOTIFY_READ → NOTIFY_OPTIONS → IDLE
IDLE → LISTENING → PROCESSING → CONFIRMING → EXECUTING → IDLE
```

## Project Structure

```
app/src/main/java/com/jarvis/app/
├── JarvisApplication.kt          # Notification channel setup
├── setup/
│   └── SetupActivity.kt          # One-time permission/key setup UI
├── core/
│   ├── JarvisService.kt          # Main service — the brain
│   ├── StateMachine.kt           # State enum + transitions
│   ├── WakeLockManager.kt        # Partial wake lock
│   └── ServiceBridge.kt          # Inter-service communication
├── notifications/
│   ├── NotificationCaptureService.kt  # NotificationListenerService
│   ├── NotificationData.kt           # Notification model
│   └── NotificationQueue.kt          # Thread-safe FIFO queue
├── accessibility/
│   └── VolumeButtonService.kt    # Volume button interception
├── speech/
│   ├── TTSEngine.kt              # Text-to-speech with queue
│   ├── AudioCaptureManager.kt    # AudioRecord + silence detection
│   └── WhisperSTTClient.kt       # OpenAI Whisper API client
├── ai/
│   ├── ClaudeIntentParser.kt     # Claude API for command parsing
│   ├── IntentResult.kt           # Sealed class for parsed intents
│   └── ConversationContext.kt    # Notification/conversation history
├── actions/
│   ├── ActionExecutor.kt         # Dispatches actions + confirmation
│   └── ContactResolver.kt       # Fuzzy contact name matching
├── audio/
│   └── AudioFocusManager.kt     # Audio focus for TTS
├── boot/
│   └── BootReceiver.kt          # Auto-start on reboot
└── updater/
    └── UpdateChecker.kt          # GitHub Releases auto-updater
```

## Auto-Updates

A GitHub Actions workflow builds the APK on every push to `main` and uploads it to the `latest` release. The app checks this release hourly:

1. Fetches `https://api.github.com/repos/nicktanda/Jarvis/releases/tags/latest`
2. Compares the commit SHA against the last installed version
3. If different, downloads the new APK
4. Announces "Update available. Volume up to install."
5. Opens the Android package installer

## API Costs

- **Whisper:** ~$0.006/min of audio. A 5-second command costs ~$0.0005.
- **Claude Haiku:** ~$0.00025 per command parse.
- **Estimated at 100 commands/day:** ~$2.25/month.

## Development

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# View logs
adb logcat -s JarvisService:D TTSEngine:D AudioCapture:D WhisperSTT:D ClaudeParser:D VolumeButtonService:D NotificationCapture:D UpdateChecker:D

# Build release APK (unsigned)
./gradlew assembleRelease
```

## Known Limitations

- **Phone calls briefly turn the screen on** — the proximity sensor turns it off once you hold the phone to your ear
- **Email composition** requires the email app to support notification reply actions (Gmail does)
- **Background noise** can affect voice recognition accuracy — keep commands short and clear
- **Some OEMs aggressively kill background services** — battery optimization exemption helps, but MIUI/EMUI may need additional steps
