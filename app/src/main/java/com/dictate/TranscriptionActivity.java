package com.dictate;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class TranscriptionActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 1001;

    private TextView transcriptionText;
    private Button languageButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transcription);

        transcriptionText = findViewById(R.id.transcriptionText);
        languageButton = findViewById(R.id.languageButton);
        Button stopButton = findViewById(R.id.stopButton);
        Button settingsButton = findViewById(R.id.settingsButton);

        transcriptionText.setText(WakeWordListenerService.lastDisplay);
        updateLanguageButton();

        // Tap the text to cut Jarvis off mid-sentence.
        transcriptionText.setOnClickListener(v -> startService(
                new Intent(this, WakeWordListenerService.class)
                        .setAction(WakeWordListenerService.ACTION_INTERRUPT)));

        stopButton.setOnClickListener(v -> {
            if (WakeWordListenerService.running) {
                startService(new Intent(this, WakeWordListenerService.class)
                        .setAction(WakeWordListenerService.ACTION_STOP));
            }
            WakeWordListenerService.lastDisplay = "Stopped. Reopen the app to start Jarvis.";
            transcriptionText.setText(WakeWordListenerService.lastDisplay);
        });

        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        languageButton.setOnClickListener(v -> {
            SharedPreferences p = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
            String cur = p.getString(WakeWordListenerService.KEY_LANGUAGE, "en-US");
            p.edit().putString(WakeWordListenerService.KEY_LANGUAGE,
                    cur.equals("en-US") ? "nl-NL" : "en-US").apply();
            updateLanguageButton();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        WakeWordListenerService.uiListener = text -> transcriptionText.setText(text);
        transcriptionText.setText(WakeWordListenerService.lastDisplay);
        ensurePermissionsThenStartService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        WakeWordListenerService.uiListener = null;
    }

    private void updateLanguageButton() {
        SharedPreferences p = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String cur = p.getString(WakeWordListenerService.KEY_LANGUAGE, "en-US");
        languageButton.setText(cur.equals("en-US") ? "Language: English" : "Language: Dutch");
    }

    private void ensurePermissionsThenStartService() {
        if (WakeWordListenerService.running) return;

        List<String> missing = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (missing.isEmpty()) {
            ContextCompat.startForegroundService(this,
                    new Intent(this, WakeWordListenerService.class));
        } else {
            ActivityCompat.requestPermissions(this,
                    missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            ensurePermissionsThenStartService();
        } else {
            transcriptionText.setText("Microphone permission is required to use Jarvis.");
        }
    }
}