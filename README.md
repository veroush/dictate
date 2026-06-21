# Dictate

A native Android app that turns a volume-button combo into a lock-screen voice
transcription pipeline. Press **both volume keys within 300 ms** while the
screen is locked (or unlocked) to launch a transparent overlay that captures
speech via Android's built-in `SpeechRecognizer`, shows the live partial
transcript, and posts the final text as JSON to a webhook you configure.

## How it works

```
[Vol+ & Vol- within 300ms] -> ButtonInterceptorService (AccessibilityService)
                                          |
                                 TranscriptionActivity (overlay above lock screen)
                                          |
                                SpeechRecognizer (live partial results)
                                          |
                                 [Stop & Send] -> WebhookClient (OkHttp POST)
```

The webhook receives a JSON body:

```json
{
  "timestamp": 1717701830,
  "text": "The transcribed text goes here."
}
```

## Requirements

- Android device running **API 26 (Android 8.0)** or higher
- Android Studio (or just the Android SDK + JDK 11) for building
- An internet-reachable endpoint that accepts a JSON POST (your webhook)

## Building

The project includes the Gradle wrapper, so you don't need Gradle installed
locally. From the project root:

```sh
# Debug build
./gradlew assembleDebug

# Release build (configure signing as needed)
./gradlew assembleRelease
```

The resulting APK is at `app/build/outputs/apk/debug/app-debug.apk`.

> On first run Gradle will download the distribution and dependencies, which
> can take a few minutes.

### Building with Android Studio

Open the project folder in Android Studio and pick **Run > Run 'app'** with a
connected device selected. Android Studio handles the SDK path via
`local.properties` automatically.

## Installing on a device

Enable USB debugging on the device and connect it, then:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First-time setup

1. Open the **Dictate** app from the launcher.
2. Enter your webhook URL (e.g. `https://your-server.com/api/transcribe`) and
   tap **Save**.
3. Grant the **Record audio** permission when prompted.
4. Enable the **Dictate** accessibility service:
   - Go to **Settings > Accessibility > Installed services > Dictate** and turn
     it on. This is required so the app can intercept hardware key events while
     the device is locked.

Optional but recommended: disable battery optimizations for Dictate so Android's
Doze mode doesn't kill the background service:

- **Settings > Apps > Dictate > Battery > Unrestricted**

## Using it

1. Lock the device (or leave it unlocked).
2. Press **Volume Up** and **Volume Down** within 300 ms of each other. A toast
   ("Dictate: Listening...") appears and a translucent overlay shows the live
   transcription.
3. Speak; partial results update on screen in real time. Recognition restarts
   automatically after each phrase so you can dictate multiple sentences.
4. Tap **Stop & Send** (or anywhere on the overlay) to stop, send the assembled
   transcript to your webhook, and dismiss the overlay.

## Permissions

| Permission | Why it's needed |
|---|---|
| `RECORD_AUDIO` | Access the microphone for speech recognition. |
| `INTERNET` | POST the transcript to your webhook. |
| `BIND_ACCESSIBILITY_SERVICE` | Intercept hardware volume key events globally, including from the lock screen. |

## Project layout

```
app/src/main/java/com/dictate/
  ButtonInterceptorService.java   # AccessibilityService: detects the volume combo
  TranscriptionActivity.java      # Lock-screen overlay + SpeechRecognizer lifecycle
  WebhookClient.java              # OkHttp async POST of the transcript
  SettingsActivity.java           # Configure and persist the webhook URL
app/src/main/res/                 # Layouts, strings, themes, accessibility config
spec.txt                          # Original technical specification
```

## Notes & caveats

- `SpeechRecognizer` can be slow to initialize when the device is deeply asleep.
  If you see initialization lag, a temporary `WakeLock` acquired in the
  accessibility service right before launching the activity usually helps.
- The accessibility service consumes volume key events only while transcription
  is not active, so normal volume changes are unaffected outside of a session.
- `local.properties` is intentionally git-ignored because it contains the
  absolute path to your local Android SDK install.
