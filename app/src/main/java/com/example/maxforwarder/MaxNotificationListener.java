package com.example.maxforwarder;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class MaxNotificationListener extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if ("ru.oneme.app".equals(sbn.getPackageName())) {
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;

            String title = extras.getString(Notification.EXTRA_TITLE, "MAX Сообщение");
            CharSequence textChar = extras.getCharSequence(Notification.EXTRA_TEXT);
            String text = (textChar != null) ? textChar.toString() : "";

            if (!text.isEmpty()) {
                // Извлекаем сохраненные пользователем настройки из SharedPreferences
                SharedPreferences prefs = getSharedPreferences("MaxForwarderPrefs", Context.MODE_PRIVATE);
                String botToken = prefs.getString("tg_bot_token", "");
                String chatId = prefs.getString("tg_chat_id", "");

                // Если данные не заполнены, прерываем выполнение
                if (botToken.isEmpty() || chatId.isEmpty()) {
                    Log.e("MaxForwarder", "Ошибка: токены Telegram не настроены.");
                    return;
                }

                String messageToSend = "📩 <b>Новое в MAX (" + title + "):</b>\n\n" + text;
                sendToTelegramAsync(messageToSend, botToken, chatId);
            }
        }
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

                    int responseCode = conn.getResponseCode();
                    Log.d("MaxForwarder", "TG Response Code: " + responseCode);
                    conn.disconnect();
                } catch (Exception e) {
                    Log.e("MaxForwarder", "Ошибка отправки в Telegram", e);
                }
            }
        }).start();
    }
}
