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

    public interface AnswerCallback {
        void onAnswer(String answer);
        void onIgnored(); // session gate swallowed this on purpose -- not an error
        void onError(String message);
    }

    public interface StatusCallback {
        void onStatus(boolean timedOut);
        void onError(String message);
    }

    private static String getAnswerUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(SettingsActivity.KEY_WEBHOOK_URL, "");
    }

    public static void send(Context context, String text, AnswerCallback callback) {
        String url = getAnswerUrl(context);

        if (url.isEmpty()) {
            Log.w(TAG, "No webhook URL configured. Text: " + text);
            if (callback != null) callback.onError("No server URL configured");
            return;
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("question", text);
        } catch (JSONException e) {
            Log.e(TAG, "JSON construction failed", e);
            if (callback != null) callback.onError("Failed to build request");
            return;
        }

        Log.d(TAG, "Sending question: " + text);
        RequestBody body = RequestBody.create(payload.toString(), JSON);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Webhook request failed", e);
                if (callback != null) callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyStr = response.body() != null ? response.body().string() : "";
                Log.d(TAG, "Webhook response: " + response.code() + " body=" + bodyStr);
                response.close();

                if (callback == null) return;

                if (!response.isSuccessful()) {
                    callback.onError("Server responded with " + response.code());
                    return;
                }

                try {
                    JSONObject json = new JSONObject(bodyStr);
                    String source = json.optString("source", "");
                    boolean hasAnswer = json.has("answer") && !json.isNull("answer");
                    String answer = hasAnswer ? json.optString("answer", "") : "";

                    if (!hasAnswer || answer.isEmpty()) {
                        if ("session".equals(source)) {
                            // Session gate deliberately swallowed this -- not an error.
                            callback.onIgnored();
                        } else {
                            callback.onError("Empty answer in response");
                        }
                    } else {
                        callback.onAnswer(answer);
                    }
                } catch (JSONException e) {
                    callback.onError("Couldn't parse server response");
                }
            }
        });
    }

    public static void checkSessionStatus(Context context, StatusCallback callback) {
        String answerUrl = getAnswerUrl(context);
        if (answerUrl.isEmpty()) {
            if (callback != null) callback.onError("No server URL configured");
            return;
        }

        String statusUrl = answerUrl.endsWith("/answer")
                ? answerUrl.substring(0, answerUrl.length() - "/answer".length()) + "/session/status"
                : answerUrl;

        Request request = new Request.Builder().url(statusUrl).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Session status check failed: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyStr = response.body() != null ? response.body().string() : "";
                response.close();
                if (callback == null) return;

                if (!response.isSuccessful()) {
                    callback.onError("Server responded with " + response.code());
                    return;
                }

                try {
                    JSONObject json = new JSONObject(bodyStr);
                    boolean timedOut = json.optBoolean("timed_out", false);
                    callback.onStatus(timedOut);
                } catch (JSONException e) {
                    callback.onError("Couldn't parse status response");
                }
            }
        });
    }
}