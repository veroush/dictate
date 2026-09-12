package com.dictate;

import android.app.Activity;
import android.content.Intent;
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

import java.util.ArrayList;
import java.util.Locale;

public class TranscriptionActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final long HEARTBEAT_INTERVAL_MS = 8000;

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

    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());

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
                startListening();
            }
        });

        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        transcriptionText.setOnClickListener(v -> {
            if (isSpeaking) {
                textToSpeech.stop();
                isSpeaking = false;
                restartListening();
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
            // Apply immediately if we're mid-session, rather than
            // waiting for the current recognition to time out on its own.
            if (listening) {
                restartListening();
            }
        });

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
            }

            @Override
            public void onBeginningOfSpeech() {
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
                if (!listening) return;
                // Recognizer times out on silence -- just restart and keep going.
                restartListening();
            }

            @Override
            public void onResults(Bundle results) {
                if (!listening) return;
                ArrayList<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String finalText = matches.get(0);
                    currentPartial = "";
                    transcriptionText.setText(finalText);
                    // Stop listening now -- don't resume until the answer has
                    // been spoken, or the mic will pick up our own voice and
                    // loop forever.
                    speechRecognizer.cancel();
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
                                    // not an error, just resume listening quietly.
                                    awaitingAnswer = false;
                                    runOnUiThread(TranscriptionActivity.this::restartListening);
                                }

                                @Override
                                public void onError(String message) {
                                    awaitingAnswer = false;
                                    runOnUiThread(() -> {
                                        transcriptionText.setText("Error: " + message);
                                        restartListening();
                                    });
                                }
                            });
                    return;
                }
                restartListening();
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
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

    private void startListening() {
        listening = true;
        toggleButton.setText("Stop");
        transcriptionText.setText("Listening...");
        beginRecognition();
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
    }

    private void beginRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        // Manual toggle (languageButton) rather than auto-detection --
        // Android's bilingual auto-switch APIs require API 34+ (Android
        // 14); this device is on Android 13, so they're unavailable.
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage);
        speechRecognizer.startListening(intent);
    }

    private void restartListening() {
        if (!listening) return;
        speechRecognizer.cancel();
        beginRecognition();
    }

    private void stopListening() {
        listening = false;
        toggleButton.setText("Start");
        speechRecognizer.stopListening();
        speechRecognizer.cancel();
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
                    // Only resume listening if the user hasn't tapped Stop
                    // in the meantime.
                    isSpeaking = false;
                    if (listening) {
                        runOnUiThread(() -> restartListening());
                    }
                }

                @Override
                public void onError(String utteranceId) {
                    isSpeaking = false;
                    if (listening) {
                        runOnUiThread(() -> restartListening());
                    }
                }
            });
            ttsReady = true;
        }
    }

    private void speak(String text) {
        if (!ttsReady) return;
        // Match the TTS voice to whichever language is currently
        // toggled, so Dutch answers aren't read out in an English accent.
        // If the device has no Dutch voice data installed, this returns
        // LANG_MISSING_DATA/LANG_NOT_SUPPORTED and silently keeps using
        // whatever voice was already active -- worth checking for
        // explicitly rather than treating a wrong-sounding voice as a
        // mystery later.
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
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }
}