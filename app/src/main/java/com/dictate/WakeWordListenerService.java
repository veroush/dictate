package com.dictate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

public class WakeWordListenerService extends Service {

    private static final String TAG = "DictateWakeWordSvc";
    private static final String CHANNEL_ID = "sidenote_listener";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_STOP = "com.dictate.action.STOP_LISTENING";
    public static final String ACTION_INTERRUPT = "com.dictate.action.INTERRUPT_SPEECH";
    public static final String KEY_LANGUAGE = "language"; // "en-US" or "nl-NL"

    private static final long UNLOCK_TIMEOUT_MS = 60000;
    private static final Pattern GREETING = Pattern.compile(
            "^(hey |hi |hello |ok |okay )?(good (morning|afternoon|evening) )?jarvis$");

    // Delay before the wake-word mic goes live again. Longer after a stop
    // phrase, plus a short window where detections are ignored, to fight the
    // false "Hey Jarvis" re-trigger seen after "goodbye jarvis". TUNE THESE.
    private static final long WAKE_RESTART_DELAY_MS = 500;
    private static final long WAKE_RESTART_DELAY_AFTER_STOP_MS = 2500;
    private static final long WAKE_IGNORE_WINDOW_AFTER_STOP_MS = 1500;
    // Wake-word engine fires a false detection ~0.4s after every start (seen in logs).
    // Ignore detections for this long after each start. TUNE THIS.
    private static final long WAKE_IGNORE_AFTER_START_MS = 1500;

    // ---- UI bridge (same process, so a static listener is enough) ----
    public interface UiListener {
        void onDisplay(String text);
    }

    public static volatile UiListener uiListener;
    public static volatile String lastDisplay = "Starting...";
    public static volatile boolean running = false;

    private final Handler main = new Handler(Looper.getMainLooper());

    private WakeWordBridge wakeWordBridge;
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;

    private boolean unlocked = false;
    private boolean commandModeActive = false;
    private boolean awaitingAnswer = false;
    private boolean speaking = false;
    private boolean shuttingDown = false;
    private String lastQuestion = "";
    private String notifText = "Say \"Hey Jarvis\"";
    private long wakeIgnoreUntil = 0;

    private final Runnable lockRunnable = this::onLockTimeout;
    private final Runnable wakeStartRunnable = new Runnable() {
        @Override
        public void run() {
            wakeIgnoreUntil = Math.max(wakeIgnoreUntil,
                    SystemClock.uptimeMillis() + WAKE_IGNORE_AFTER_START_MS);
            Log.d(TAG, "wakeWordBridge.start() now running");
            wakeWordBridge.start();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createNotificationChannel();
        wakeWordBridge = new WakeWordBridge(this, () -> main.post(this::onWakeWordDetected));

        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
            } else {
                Log.e(TAG, "TextToSpeech init FAILED, status=" + status);
            }
        });
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }

            @Override
            public void onDone(String utteranceId) {
                main.post(() -> onSpeechFinished());
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "TTS onError");
                main.post(() -> onSpeechFinished());
            }
        });

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Recognition not available");
        } else {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }

                @Override
                public void onBeginningOfSpeech() {
                    resetLockTimer(); // real speech proves the user is here
                }

                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }

                @Override
                public void onError(int error) {
                    if (!commandModeActive) return;
                    Log.d(TAG, "Recognizer onError code=" + error);
                    commandModeActive = false;
                    if (unlocked) {
                        // just a no-speech timeout; listen again shortly
                        main.postDelayed(() -> {
                            if (unlocked && !speaking && !awaitingAnswer && !commandModeActive) {
                                beginRecognition();
                            }
                        }, 400);
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    if (!commandModeActive) return;
                    commandModeActive = false;
                    ArrayList<String> matches =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches == null || matches.isEmpty()) {
                        if (unlocked) beginRecognition();
                        return;
                    }
                    handleCommand(matches.get(0));
                }
            });
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_INTERRUPT.equals(intent.getAction())) {
            if (speaking) {
                textToSpeech.stop();
                onSpeechFinished();
            }
            return START_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        show("Say \"Hey Jarvis\"...");
        switchToWakeWordListening(WAKE_RESTART_DELAY_MS);
        return START_STICKY;
    }

    // ------------------------------------------------------------------

    private void onWakeWordDetected() {
        if (unlocked) return;
        if (SystemClock.uptimeMillis() < wakeIgnoreUntil) {
            Log.d(TAG, "Wake detection ignored (post-stop window)");
            return;
        }
        wakeWordBridge.stop();
        unlocked = true;
        setNotif("Listening");
        resetLockTimer();
        show("Yes?");
        speak("Yes?");
    }

    private void handleCommand(String finalText) {
        Log.d(TAG, "Recognized: " + finalText);
        String normalized = finalText.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim();

        if (isShutdown(normalized)) {
            Log.d(TAG, "Shutdown phrase matched");
            shutDown();
            return;
        }
        if (GREETING.matcher(normalized).matches()) {
            Log.d(TAG, "Greeting matched -- treating as wake word");
            show("Yes?");
            speak("Yes?");
            return;
        }
        if (isLogOff(normalized)) {
            Log.d(TAG, "Log off phrase matched -- logging off locally");
            endSession("Bye!", true);
            return;
        }
        lastQuestion = finalText;
        show("You: " + finalText);
        awaitingAnswer = true;
        WebhookClient.send(this, finalText, new WebhookClient.AnswerCallback() {
            @Override
            public void onAnswer(String answer) {
                main.post(() -> {
                    awaitingAnswer = false;
                    show("You: " + lastQuestion + "\n\nJarvis: " + answer);
                    speak(answer);
                });
            }

            @Override
            public void onIgnored() {
                main.post(() -> {
                    awaitingAnswer = false;
                    onSpeechFinished();
                });
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Webhook error: " + message);
                main.post(() -> {
                    awaitingAnswer = false;
                    show("Error: " + message);
                    speak("Sorry, something went wrong.");
                });
            }
        });
    }

    /** Ends the session. speakGoodbye non-null = say it, then go to wake mode. */
    private void endSession(String goodbye, boolean fromStopPhrase) {
        main.removeCallbacks(lockRunnable);
        unlocked = false;
        commandModeActive = false;
        if (speechRecognizer != null) speechRecognizer.cancel();
        setNotif("Jarvis logged off -- say \"Hey Jarvis\"");
        show("Logged off -- say \"Hey Jarvis\"");
        if (fromStopPhrase) {
            wakeIgnoreUntil = SystemClock.uptimeMillis()
                    + WAKE_RESTART_DELAY_AFTER_STOP_MS + WAKE_IGNORE_WINDOW_AFTER_STOP_MS;
        }
        if (goodbye != null) {
            speak(goodbye); // onSpeechFinished -> wake mode (unlocked is false)
        } else {
            switchToWakeWordListening(WAKE_RESTART_DELAY_MS);
        }
    }
    private boolean isShutdown(String n) {
        return n.contains("jarvis") && (n.contains("shutdown") || n.contains("shut down"));
    }

    /** Short utterances only, so "how do I log off my laptop" doesn't end the session. */
    private boolean isLogOff(String n) {
        if (n.contains("goodbye jarvis") || n.contains("you may go")) return true;
        int words = n.isEmpty() ? 0 : n.split(" +").length;
        if (words > 6) return false;
        return n.contains("log off") || n.contains("logoff") || n.contains("go now");
    }

    private void shutDown() {
        shuttingDown = true;
        main.removeCallbacks(lockRunnable);
        unlocked = false;
        commandModeActive = false;
        if (speechRecognizer != null) speechRecognizer.cancel();
        show("Shutting down. Reopen the app to start Jarvis.");
        speak("Shutting down.");
    }

    /** Called after any utterance finishes (or is interrupted). */
    private void onSpeechFinished() {
        speaking = false;
        if (shuttingDown) {
            stopSelf();
            return;
        }
        if (unlocked) {
            setNotif("Listening");
            resetLockTimer();
            beginRecognition();
        } else {
            setNotif("Jarvis logged off -- say \"Hey Jarvis\"");
            long delay = SystemClock.uptimeMillis() < wakeIgnoreUntil
                    ? WAKE_RESTART_DELAY_AFTER_STOP_MS : WAKE_RESTART_DELAY_MS;
            switchToWakeWordListening(delay);
        }
    }

    private void beginRecognition() {
        if (speechRecognizer == null) {
            Log.e(TAG, "No recognizer available");
            return;
        }
        commandModeActive = true;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, getLanguage());
        speechRecognizer.startListening(i);
    }

    private void switchToWakeWordListening(long delayMs) {
        commandModeActive = false;
        if (speechRecognizer != null) speechRecognizer.cancel();
        main.removeCallbacks(wakeStartRunnable);
        Log.d(TAG, "Scheduling wake-word start in " + delayMs + "ms");
        main.postDelayed(wakeStartRunnable, delayMs);
    }

    private void speak(String text) {
        if (!ttsReady) {
            Log.e(TAG, "speak() before TTS ready");
            onSpeechFinished();
            return;
        }
        speaking = true;
        setNotif("Jarvis is talking");
        applyVoice();
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer");
    }

    private void applyVoice() {
        String lang = getLanguage();
        if (lang.equals("en-US")) {
            if (textToSpeech.getDefaultVoice() != null) {
                textToSpeech.setVoice(textToSpeech.getDefaultVoice());
            }
        } else {
            textToSpeech.setLanguage(Locale.forLanguageTag(lang));
        }
    }

    private void resetLockTimer() {
        main.removeCallbacks(lockRunnable);
        main.postDelayed(lockRunnable, UNLOCK_TIMEOUT_MS);
    }

    /** 60s of silence: log off silently (notification only). */
    private void onLockTimeout() {
        if (awaitingAnswer || speaking) {
            resetLockTimer();
            return;
        }
        endSession(null, false);
    }

    private String getLanguage() {
        SharedPreferences p = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        return p.getString(KEY_LANGUAGE, "en-US");
    }

    private void show(String text) {
        lastDisplay = text;
        UiListener l = uiListener;
        if (l != null) l.onDisplay(text);
    }

    private void setNotif(String text) {
        notifText = text;
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) m.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, TranscriptionActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Jarvis")
                .setContentText(notifText)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Jarvis", NotificationManager.IMPORTANCE_LOW);
            NotificationManager m = getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        main.removeCallbacksAndMessages(null);
        if (wakeWordBridge != null) {
            wakeWordBridge.stop();
            wakeWordBridge.release();
        }
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}