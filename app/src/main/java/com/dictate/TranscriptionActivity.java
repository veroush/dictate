package com.dictate;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Locale;

public class TranscriptionActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final long HEARTBEAT_INTERVAL_MS = 8000;
    private static final int REQUEST_RECORD_AUDIO = 1001;

    // How long you can stay silent while unlocked before the wake word is
    // required again. Reset on real speech beginning, or on a successfully
    // recognized command -- NOT on SpeechRecognizer's own short internal
    // no-speech timeout, which fires every few seconds during normal
    // pauses and would otherwise prevent the lock from ever happening.
    private static final long UNLOCK_TIMEOUT_MS = 60000;

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;
    private TextView transcriptionText;
    private Button toggleButton;
    private Button settingsButton;
    private Button languageButton;
    private String currentLanguage = "en-US";
    private String currentPartial = "";
    private boolean listening = false;
    private boolean isSpeaking = false;
    private boolean awaitingAnswer = false;

    // --- wake word engine + a flag tracking which "mode" we're in ---
    private WakeWordBridge wakeWordBridge;
    // true = wake word engine has the mic, listening for "Hey Jarvis"
    // false = SpeechRecognizer has the mic (either capturing a command, or
    //         idling in "unlocked" mode waiting for you to speak again)
    private boolean wakeWordActive = false;

    // --- unlock/lock state ---
    // true once the wake word has been heard; stays true across multiple
    // follow-up questions with no wake word needed, until UNLOCK_TIMEOUT_MS
    // of real silence passes.
    private boolean unlocked = false;

    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Handler lockHandler = new Handler(Looper.getMainLooper());
    private final Runnable lockRunnable = this::lockSession;

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!listening) return; // Stop pressed -- let the loop die here.
            WebhookClient.checkSessionStatus(TranscriptionActivity.this,
                    new WebhookClient.StatusCallback() {
                        @Override
                        public void onStatus(boolean timedOut) {
                            runOnUiThread(() -> {
                                if (timedOut && !isSpeaking && !awaitingAnswer) {
                                    speechRecognizer.cancel();
                                    transcriptionText.setText("Aborting.");
                                    speak("Aborting.");
                                }
                                heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
                            });
                        }

                        @Override
                        public void onError(String message) {
                            Log.w("DictateHeartbeat", "Status check failed: " + message);
                            heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
                        }
                    });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transcription);

        textToSpeech = new TextToSpeech(this, this);

        transcriptionText = findViewById(R.id.transcriptionText);
        toggleButton = findViewById(R.id.toggleButton);
        settingsButton = findViewById(R.id.settingsButton);
        languageButton = findViewById(R.id.languageButton);

        toggleButton.setOnClickListener(v -> {
            if (listening) {
                stopListening();
            } else {
                requestMicPermissionThenStart();
            }
        });

        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        transcriptionText.setOnClickListener(v -> {
            if (isSpeaking) {
                textToSpeech.stop();
                isSpeaking = false;
                resumeListening();
            }
        });

        languageButton.setOnClickListener(v -> {
            if (currentLanguage.equals("en-US")) {
                currentLanguage = "nl-NL";
                languageButton.setText("Language: Dutch");
            } else {
                currentLanguage = "en-US";
                languageButton.setText("Language: English");
            }
            // Apply immediately if we're mid-command-capture, rather than
            // waiting for the current recognition to time out on its own.
            if (listening && !wakeWordActive) {
                restartListening();
            }
        });

        // --- Stage 1: temporary test button for the background service ---
        Button backgroundToggleButton = findViewById(R.id.backgroundToggleButton);
        backgroundToggleButton.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, WakeWordListenerService.class);
            ContextCompat.startForegroundService(this, serviceIntent);
        });

        // --- set up the wake word engine ---
        wakeWordBridge = new WakeWordBridge(this, () -> runOnUiThread(this::onWakeWordDetected));

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
            }

            @Override
            public void onBeginningOfSpeech() {
                if (unlocked) {
                    resetLockTimer();
                }
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onError(int error) {
                if (!listening || wakeWordActive) return;
                if (unlocked) {
                    // Just SpeechRecognizer's own short no-speech timeout --
                    // normal during a pause. Keep listening without
                    // resetting the lock timer or requiring the wake word.
                    restartListening();
                } else {
                    // Recognizer timed out on silence while capturing a
                    // command that followed the wake word -- give up and
                    // go back to wake-word listening.
                    transcriptionText.setText("Didn't catch a command -- say \"Hey Jarvis\" again.");
                    switchToWakeWordListening();
                }
            }

            @Override
            public void onResults(Bundle results) {
                if (!listening || wakeWordActive) return;
                ArrayList<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String finalText = matches.get(0);
                    currentPartial = "";
                    transcriptionText.setText(finalText);
                    speechRecognizer.cancel();
                    if (unlocked) {
                        resetLockTimer();
                    }
                    awaitingAnswer = true;
                    WebhookClient.send(TranscriptionActivity.this, finalText,
                            new WebhookClient.AnswerCallback() {
                                @Override
                                public void onAnswer(String answer) {
                                    awaitingAnswer = false;
                                    runOnUiThread(() -> {
                                        transcriptionText.setText(answer);
                                        speak(answer);
                                    });
                                }

                                @Override
                                public void onIgnored() {
                                    // Session gate deliberately swallowed this --
                                    // not an error, just go back to listening.
                                    awaitingAnswer = false;
                                    runOnUiThread(TranscriptionActivity.this::resumeListening);
                                }

                                @Override
                                public void onError(String message) {
                                    awaitingAnswer = false;
                                    runOnUiThread(() -> {
                                        transcriptionText.setText("Error: " + message);
                                        resumeListening();
                                    });
                                }
                            });
                    return;
                }
                if (unlocked) {
                    restartListening();
                } else {
                    switchToWakeWordListening();
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (wakeWordActive) return;
                ArrayList<String> matches = partialResults.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    currentPartial = matches.get(0);
                    transcriptionText.setText(currentPartial);
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
    }

    private void requestMicPermissionThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening();
            } else {
                transcriptionText.setText("Microphone permission is required to use Sidenote.");
            }
        }
    }

    // --- called when the wake word engine detects "Hey Jarvis" ---
    private void onWakeWordDetected() {
        Log.d("DictateWakeWord", "onWakeWordDetected() called, listening=" + listening + ", unlocked=" + unlocked);
        if (!listening) return; // Stop was pressed, ignore stray detections.
        wakeWordBridge.stop();
        wakeWordActive = false;
        unlocked = true;
        transcriptionText.setText("Yes?");
        resetLockTimer();
        speak("Yes?");
    }

    /** Called after a real turn completes (an answer was spoken, a question
     * was ignored, an error happened, etc). Stays in command-listening mode
     * with no wake word needed if still unlocked; otherwise falls back to
     * wake-word mode. */
    private void resumeListening() {
        if (!listening) return;
        if (unlocked) {
            resetLockTimer();
            beginRecognition();
        } else {
            switchToWakeWordListening();
        }
    }

    private void resetLockTimer() {
        lockHandler.removeCallbacks(lockRunnable);
        lockHandler.postDelayed(lockRunnable, UNLOCK_TIMEOUT_MS);
    }

    private void lockSession() {
        if (!listening) return;
        unlocked = false;
        speechRecognizer.cancel();
        transcriptionText.setText("Locked -- say \"Hey Jarvis\" to unlock.");
        switchToWakeWordListening();
    }

    // --- hand the mic back to the wake word engine ---
    private void switchToWakeWordListening() {
        if (!listening) return;
        speechRecognizer.cancel();
        wakeWordActive = true;
        if (!unlocked) {
            transcriptionText.setText("Say \"Hey Jarvis\"...");
        }
        wakeWordBridge.start();
    }

    private void startListening() {
        listening = true;
        toggleButton.setText("Stop");
        // start in wake-word mode, not straight into command capture
        switchToWakeWordListening();
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
    }

    private void beginRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage);
        speechRecognizer.startListening(intent);
    }

    private void restartListening() {
        if (!listening || wakeWordActive) return;
        speechRecognizer.cancel();
        beginRecognition();
    }

    private void stopListening() {
        listening = false;
        toggleButton.setText("Start");
        speechRecognizer.stopListening();
        speechRecognizer.cancel();
        // also stop the wake word engine
        wakeWordBridge.stop();
        wakeWordActive = false;
        unlocked = false;
        lockHandler.removeCallbacks(lockRunnable);
        textToSpeech.stop();
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        transcriptionText.setText("Tap Start to begin");
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech.setVoice(textToSpeech.getDefaultVoice());
            textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    isSpeaking = false;
                    if (listening) {
                        runOnUiThread(TranscriptionActivity.this::resumeListening);
                    }
                }

                @Override
                public void onError(String utteranceId) {
                    isSpeaking = false;
                    if (listening) {
                        runOnUiThread(TranscriptionActivity.this::resumeListening);
                    }
                }
            });
            ttsReady = true;
        }
    }

    private void speak(String text) {
        if (!ttsReady) return;
        int result = textToSpeech.setLanguage(Locale.forLanguageTag(currentLanguage));
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            transcriptionText.append("\n[No Dutch voice installed on this device]");
        }
        isSpeaking = true;
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        lockHandler.removeCallbacks(lockRunnable);
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        // release the wake word engine's resources
        if (wakeWordBridge != null) {
            wakeWordBridge.release();
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }
}