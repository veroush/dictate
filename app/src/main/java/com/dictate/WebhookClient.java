package com.dictate;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class WebhookClient {

    private static final String TAG = "DictateWebhook";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient client = new OkHttpClient();
    private static String cachedUrl = "";

    public static void init(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        cachedUrl = prefs.getString(SettingsActivity.KEY_WEBHOOK_URL, "");
    }

    public static void send(Context context, String text) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String url = prefs.getString(SettingsActivity.KEY_WEBHOOK_URL, "");

        if (url.isEmpty()) {
            Log.w(TAG, "No webhook URL configured. Text: " + text);
            return;
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("timestamp", System.currentTimeMillis() / 1000);
            payload.put("text", text);
        } catch (JSONException e) {
            Log.e(TAG, "JSON construction failed", e);
            return;
        }

        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Webhook request failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                Log.d(TAG, "Webhook response: " + response.code() + " body=" + body);
                response.close();
            }
        });
    }
}
