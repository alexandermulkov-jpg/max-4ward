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
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public class MaxNotificationListener extends NotificationListenerService {

    // Хранилище активных уведомлений для возможности ответа
    // Ключ: Имя отправителя (Title), Значение: Объект уведомления
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
        if ("ru.oneme.app".equals(sbn.getPackageName())) {
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;

            String title = extras.getString(Notification.EXTRA_TITLE, "MAX Сообщение");
            CharSequence textChar = extras.getCharSequence(Notification.EXTRA_TEXT);
            String text = (textChar != null) ? textChar.toString() : "";

            if (!text.isEmpty()) {
                // Сохраняем уведомление, чтобы знать, куда отвечать
                activeNotifications.put(title, notification);

                SharedPreferences prefs = getSharedPreferences("MaxForwarderPrefs", Context.MODE_PRIVATE);
                String botToken = prefs.getString("tg_bot_token", "");
                String chatId = prefs.getString("tg_chat_id", "");

                if (botToken.isEmpty() || chatId.isEmpty()) return;

                String messageToSend = "📩 <b>Новое в MAX (" + title + "):</b>\n\n" + text;
                sendToTelegramAsync(messageToSend, botToken, chatId);
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if ("ru.oneme.app".equals(sbn.getPackageName())) {
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;
            String title = extras.getString(Notification.EXTRA_TITLE);
            if (title != null) {
                activeNotifications.remove(title);
            }
        }
    }

    // Фоновый поток для опроса Telegram-бота
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
                        Thread.sleep(4000); // Проверяем Telegram каждые 4 секунды
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
                        
                        // Проверяем, что сообщение написали именно вы, а не левый человек
                        if (!fromId.equals(myChatId)) continue;

                        String text = message.optString("text", "");

                        // Проверяем, является ли это ответом (Reply) на пересланное сообщение
                        if (message.has("reply_to_message") && !text.isEmpty()) {
                            JSONObject replyTo = message.getJSONObject("reply_to_message");
                            String replyText = replyTo.optString("text", "");

                            // Пытаемся вытащить имя чата из формата "Новое в MAX (Имя):"
                            if (replyText.contains("Новое в MAX (") && replyText.contains("):")) {
                                int start = replyText.indexOf("Новое в MAX (") + 13;
                                int end = replyText.indexOf("):");
                                String chatTitle = replyText.substring(start, end);

                                // Вызываем функцию отправки ответа в MAX
                                boolean success = replyToMax(chatTitle, text);
                                
                                // Отправляем отчет в ТГ об успешности
                                String status = success ? "✅ Отправлено в MAX" : "❌ Ошибка: Уведомление этого чата уже исчезло с экрана телефона";
                                sendToTelegramAsync(status, botToken, myChatId);
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

    // Функция симуляции «Быстрого ответа» из шторки уведомлений Android
    private boolean replyToMax(String chatTitle, String replyText) {
        Notification notification = activeNotifications.get(chatTitle);
        if (notification == null) return false;

        // Ищем во входящем уведомлении кнопку ответа (RemoteInput)
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                if (action.getRemoteInputs() != null) {
                    for (RemoteInput remoteInput : action.getRemoteInputs()) {
                        // Создаем намерение ответа
                        Intent intent = new Intent();
                        Bundle bundle = new Bundle();
                        bundle.putCharSequence(remoteInput.getResultKey(), replyText);
                        RemoteInput.addResultsToIntent(action.getRemoteInputs(), intent, bundle);
                        try {
                            // Отправляем ответ обратно в приложение MAX
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
