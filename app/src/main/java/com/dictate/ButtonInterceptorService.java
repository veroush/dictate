package com.dictate;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

public class ButtonInterceptorService extends AccessibilityService {

    private static final String TAG = "DictateService";
    private static final long COMBO_WINDOW_MS = 300;

    private static ButtonInterceptorService serviceInstance;

    private long lastVolUpTime = 0;
    private long lastVolDownTime = 0;
    private boolean transcriptionActive = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceInstance = this;
        Log.i(TAG, "Service started");
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }

        if (transcriptionActive) {
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            long now = SystemClock.uptimeMillis();
            boolean isUp = keyCode == KeyEvent.KEYCODE_VOLUME_UP;

            if (isUp) {
                lastVolUpTime = now;
            } else {
                lastVolDownTime = now;
            }

            long otherTime = isUp ? lastVolDownTime : lastVolUpTime;
            if (otherTime != 0 && now - otherTime <= COMBO_WINDOW_MS) {
                Log.i(TAG, "Both volume keys pressed within window — launching transcription");
                lastVolUpTime = 0;
                lastVolDownTime = 0;
                startTranscription();
                return true;
            }
        }

        return false;
    }

    private void startTranscription() {
        if (transcriptionActive) return;
        transcriptionActive = true;
        Toast.makeText(this, "Dictate: Listening...", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, TranscriptionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        handler.postDelayed(this::dismissVolumeUI, 200);
    }

    private void dismissVolumeUI() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public static void transcriptionEnded() {
        if (serviceInstance != null) {
            serviceInstance.transcriptionActive = false;
        }
    }
}
