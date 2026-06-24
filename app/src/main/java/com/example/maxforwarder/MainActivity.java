package com.example.maxforwarder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final String BOT_TOKEN = "5085657849:AAFZJRZtrIUBOUXMFtcuYR_to491szyrpeY";
    private static final String CHAT_ID = "-1004363923060";

    private boolean isPolling = false;
    private int lastUpdateId = 0;
    private int requestCounter = 0;
    
    private TextView tvStatus;
    private TextView tvCounter;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("MAX Бот-Контроллер\n");
        tvTitle.setTextSize(22);
        tvTitle.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        layout.addView(tvTitle);

        tvStatus = new TextView(this);
        tvStatus.setText("Статус: Инициализация...");
        tvStatus.setTextSize(16);
        layout.addView(tvStatus);

        tvCounter = new TextView(this);
        tvCounter.setText("Успешных запросов к TG: 0");
        tvCounter.setTextSize(14);
        layout.addView(tvCounter);

        setContentView(layout);

        // Отправим тестовое сообщение в группу при запуске, чтобы проверить, рабочий ли ID чата и токен
        sendRawMessage("🚀 Контроллер запущен на POCO!", BOT_TOKEN, CHAT_ID, 0);

        startTelegramPolling();
    }

    private void startTelegramPolling() {
        if (isPolling) return;
        isPolling = true;
        tvStatus.setText("Статус: Опрос Telegram активен");

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (isPolling) {
                    try {
                        checkTelegramUpdates(BOT_TOKEN, CHAT_ID);
                    } catch (Exception e) {
                        Log.e("MaxForwarder", "Ошибка в потоке пуллинга", e);
                    }
                    try {
                        Thread.sleep(3000); // Опрашиваем каждые 3 секунды
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }).start();
    }

    private void checkTelegramUpdates(String botToken, String myChatId) {
        try {
            String urlString = "https://api.telegram.org/bot" + botToken + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=10";
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                requestCounter++;
                runOnUiThread(() -> tvCounter.setText("Успешных запросов к TG: " + requestCounter));

                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) response.append(line);
                in.close();

                JSONObject jsonObj = new JSONObject(response.toString());
                if (jsonObj.has("result")) {
                    JSONArray results = jsonObj.getJSONArray("result");

                    for (int i = 0; i < results.length(); i++) {
                        JSONObject update = results.getJSONObject(i);
                        lastUpdateId = update.getInt("update_id");

                        if (update.has("message")) {
                            JSONObject message = update.getJSONObject("message");
                            String text = message.optString("text", "").trim();
                            int topicId = message.has("message_thread_id") ? message.getInt("message_thread_id") : 0;

                            if (text.equalsIgnoreCase("/status") || text.startsWith("/status@")) {
                                String statusReport = getPhoneStatus();
                                sendRawMessage(statusReport, botToken, myChatId, topicId);
                            }
                        }
                    }
                }
            } else {
                runOnUiThread(() -> tvStatus.setText("Статус: Ошибка сервера TG (" + responseCode + ")"));
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e("MaxForwarder", "Ошибка запроса", e);
            runOnUiThread(() -> tvStatus.setText("Статус: Ошибка сети/блокировка"));
        }
    }

    private String getPhoneStatus() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
            
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            String netType = "Нет сети";
            if (activeNetwork != null && activeNetwork.isConnectedOrConnecting()) {
                netType = activeNetwork.getTypeName();
            }

            return "🤖 <b>Статус телефона POCO:</b>\n" +
                   "🔋 Заряд батареи: " + level + "%\n" +
                   "🌐 Тип сети: " + netType + "\n" +
                   "🚀 Система управления: Работает напрямую";
        } catch (Exception e) {
            return "🤖 Бот на связи, датчики временно недоступны.";
        }
    }

    private void sendRawMessage(final String message, final String botToken, final String chatId, final int threadId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String urlString = "https://api.telegram.org/bot" + botToken + "/sendMessage";
                    URL url = new URL(urlString);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                    String postData = "chat_id=" + chatId 
                                    + "&text=" + URLEncoder.encode(message, "UTF-8") 
                                    + "&parse_mode=HTML";
                    if (threadId > 0) {
                        postData += "&message_thread_id=" + threadId;
                    }

                    OutputStream os = conn.getOutputStream();
                    os.write(postData.getBytes("UTF-8"));
                    os.flush(); os.close();
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Exception e) {
                    Log.e("MaxForwarder", "Ошибка отправки", e);
                }
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        isPolling = false;
        super.onDestroy();
    }
}
