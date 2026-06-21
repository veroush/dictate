package com.dictate;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    public static final String PREFS_NAME = "dictate_prefs";
    public static final String KEY_WEBHOOK_URL = "webhook_url";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        EditText urlInput = findViewById(R.id.webhookUrlInput);
        Button saveButton = findViewById(R.id.saveButton);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        urlInput.setText(prefs.getString(KEY_WEBHOOK_URL, ""));

        saveButton.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            prefs.edit().putString(KEY_WEBHOOK_URL, url).apply();
            Toast.makeText(this, "Webhook URL saved", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
