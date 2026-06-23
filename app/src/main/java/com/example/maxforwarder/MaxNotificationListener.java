package com.example.maxforwarder;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public class MaxNotificationListener extends NotificationListenerService {

    private static final Map<String, Notification> activeNotifications = new HashMap<>();
    private boolean isPolling = false;
    private int lastUpdateId = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        startTelegramPolling();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String currentPackage = sbn.getPackageName();
        
        SharedPreferences prefs = getSharedPreferences("MaxForwarderPrefs", Context.MODE_PRIVATE);
        Set<String> allowedPackages = prefs.getStringSet("allowed_packages", new HashSet<String>());

        // Если пакет текущего уведомления не отмечен галочкой пользователем — игнорируем его
        if (!allowedPackages.contains(currentPackage)) {
            return;
        }

        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;

        String title = extras.getString(Notification.EXTRA_TITLE, "Уведомление");
        CharSequence textChar = extras.getCharSequence(Notification.EXTRA_TEXT);
        String text = (textChar != null) ? textChar.toString() : "";

        if (!text.isEmpty()) {
            activeNotifications.put(title, notification);

            String botToken = prefs.getString("tg_bot_token", "");
            String chatId = prefs.getString("tg_chat_id", "");

            if (botToken.isEmpty() || chatId.isEmpty()) return;

            // Формируем красивое имя источника (название пакета или понятное имя)
            String sourceName = currentPackage.contains("mms") || currentPackage.contains("messaging") ? "💬 SMS" : "📩 " + title;

            String messageToSend = "<b>" + sourceName + ":</b>\n\n" + text;
            sendToTelegramAsync(messageToSend, botToken, chatId);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        String title = extras.getString(Notification.EXTRA_TITLE);
        if (title != null) {
            activeNotifications.remove(title);
        }
    }

    private void startTelegramPolling() {
        if (isPolling) return;
        isPolling = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (isPolling) {
                    try {
                        SharedPreferences prefs = getSharedPreferences("MaxForwarderPrefs", Context.MODE_PRIVATE);
                        String botToken = prefs.getString("tg_bot_token", "");
                        String myChatId = prefs.getString("tg_chat_id", "");

                        if (!botToken.isEmpty()) {
                            checkTelegramUpdates(botToken, myChatId);
                        }
                    } catch (Exception e) {
                        Log.e("MaxForwarder", "Ошибка пуллинга TG", e);
                    }
                    try {
                        Thread.sleep(4000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }).start();
    }

    private void checkTelegramUpdates(String botToken, String myChatId) {
        try {
            String urlString = "https://api.telegram.org/bot" + botToken + "/getUpdates?offset=" + (lastUpdateId + 1);
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) response.append(line);
                in.close();

                JSONObject jsonObj = new JSONObject(response.toString());
                JSONArray results = jsonObj.getJSONArray("result");

                for (int i = 0; i < results.length(); i++) {
                    JSONObject update = results.getJSONObject(i);
                    lastUpdateId = update.getInt("update_id");

                    if (update.has("message")) {
                        JSONObject message = update.getJSONObject("message");
                        String fromId = message.getJSONObject("from").getString("id");

                        if (!fromId.equals(myChatId)) continue;

                        String text = message.optString("text", "");

                        if (message.has("reply_to_message") && !text.isEmpty()) {
                            JSONObject replyTo = message.getJSONObject("reply_to_message");
                            String replyText = replyTo.optString("text", "");

                            // Извлекаем заголовок из формата чата
                            if (replyText.contains(":") && replyText.startsWith("<b>")) {
                                int end = replyText.indexOf(":</b>");
                                if (end != -1) {
                                    String chatTitle = replyText.substring(7, end); // Убираем <b>

                                    // Если это было СМС, то быстрый ответ шторки Android отправит его обратно в СМС-клиент!
                                    boolean success = replyToMax(chatTitle, text);
                                    String status = success ? "✅ Ответ отправлен" : "❌ Ошибка: Уведомление уже удалено из шторки смартфона";
                                    sendToTelegramAsync(status, botToken, myChatId);
                                }
                            }
                        }
                    }
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e("MaxForwarder", "Ошибка парсинга обновлений TG", e);
        }
    }

    private boolean replyToMax(String chatTitle, String replyText) {
        Notification notification = activeNotifications.get(chatTitle);
        if (notification == null) return false;

        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                if (action.getRemoteInputs() != null) {
                    for (RemoteInput remoteInput : action.getRemoteInputs()) {
                        Intent intent = new Intent();
                        Bundle bundle = new Bundle();
                        bundle.putCharSequence(remoteInput.getResultKey(), replyText);
                        RemoteInput.addResultsToIntent(action.getRemoteInputs(), intent, bundle);
                        try {
                            action.actionIntent.send(getApplicationContext(), 0, intent);
                            return true;
                        } catch (Exception e) {
                            Log.e("MaxForwarder", "Не удалось симулировать ввод", e);
                        }
                    }
                }
            }
        }
        return false;
    }

    private void sendToTelegramAsync(final String message, final String botToken, final String chatId) {
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

                    OutputStream os = conn.getOutputStream();
                    os.write(postData.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Exception e) {
                    Log.e("MaxForwarder", "Ошибка отправки в TG", e);
                }
            }
        }).start();
    }

    @Override
    public void onDestroy() {
        isPolling = false;
        super.onDestroy();
    }
}
