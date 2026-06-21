package com.dictate;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;

public class TranscriptionActivity extends Activity {

    private static TranscriptionActivity instance;

    private SpeechRecognizer speechRecognizer;
    private TextView transcriptionText;
    private StringBuilder transcript = new StringBuilder();
    private String currentPartial = "";
    private boolean finishing = false;

    public static TranscriptionActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        setShowWhenLocked(true);
        setTurnScreenOn(true);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(R.layout.activity_transcription);

        transcriptionText = findViewById(R.id.transcriptionText);
        transcriptionText.setText("Listening...");

        findViewById(R.id.rootLayout).setOnClickListener(v -> stopTranscription());
        Button stopButton = findViewById(R.id.stopButton);
        stopButton.setOnClickListener(v -> stopTranscription());

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
                if (finishing) return;
                updateDisplay();
                restartListening();
            }

            @Override
            public void onResults(Bundle results) {
                if (finishing) return;
                ArrayList<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    if (transcript.length() > 0) {
                        transcript.append(" ");
                    }
                    transcript.append(matches.get(0));
                }
                currentPartial = "";
                updateDisplay();
                restartListening();
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    currentPartial = matches.get(0);
                    updateDisplay();
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        startListening();
    }

    private void startListening() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechRecognizer.startListening(intent);
    }

    private void restartListening() {
        speechRecognizer.cancel();
        startListening();
    }

    private void updateDisplay() {
        String display = transcript.toString();
        if (!currentPartial.isEmpty()) {
            if (!display.isEmpty()) {
                display += " ";
            }
            display += currentPartial;
        }
        transcriptionText.setText(display.isEmpty() ? "Listening..." : display);
    }

    @Override
    public void onBackPressed() {
    }

    public void stopTranscription() {
        if (finishing) return;
        finishing = true;
        ButtonInterceptorService.transcriptionEnded();
        speechRecognizer.stopListening();
        String text = transcript.toString();
        if (!text.isEmpty()) {
            transcriptionText.setText(text);
            WebhookClient.send(this, text);
        } else {
            transcriptionText.setText("Nothing recorded");
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ButtonInterceptorService.transcriptionEnded();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        instance = null;
    }

}
