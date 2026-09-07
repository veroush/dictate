package com.dictate;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

public class TranscriptionActivity extends Activity implements TextToSpeech.OnInitListener {

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;
    private TextView transcriptionText;
    private Button toggleButton;
    private Button settingsButton;
    private String currentPartial = "";
    private boolean listening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transcription);

        textToSpeech = new TextToSpeech(this, this);

        transcriptionText = findViewById(R.id.transcriptionText);
        toggleButton = findViewById(R.id.toggleButton);
        settingsButton = findViewById(R.id.settingsButton);

        toggleButton.setOnClickListener(v -> {
            if (listening) {
                stopListening();
            } else {
                startListening();
            }
        });

        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

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
                    WebhookClient.send(TranscriptionActivity.this, finalText,
                            new WebhookClient.AnswerCallback() {
                                @Override
                                public void onAnswer(String answer) {
                                    runOnUiThread(() -> {
                                        transcriptionText.setText(answer);
                                        speak(answer);
                                    });
                                }

                                @Override
                                public void onError(String message) {
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
    }

    private void beginRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
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
                    if (listening) {
                        runOnUiThread(() -> restartListening());
                    }
                }

                @Override
                public void onError(String utteranceId) {
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
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
    }
}