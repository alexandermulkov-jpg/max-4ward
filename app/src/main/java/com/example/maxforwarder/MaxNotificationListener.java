package com.example.maxforwarder;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
import android.os.Bundle;
import android.util.Log;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class MaxNotificationListener extends NotificationListenerService {

    // !!! Впишите свои данные Telegram сюда !!!
    private static final String TG_BOT_TOKEN = "ВАШ_ТОКЕН_ТЕЛЕГРАМ_БОТА";
    private static final String TG_CHAT_ID = "ВАШ_ЛИЧНЫЙ_ЧАТ_ID";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Проверяем, что уведомление пришло именно от приложения MAX
        if ("ru.oneme.app".equals(sbn.getPackageName())) {
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;

            // Извлекаем заголовок (имя контакта или группы) и текст сообщения
            String title = extras.getString(Notification.EXTRA_TITLE, "MAX Сообщение");
            CharSequence textChar = extras.getCharSequence(Notification.EXTRA_TEXT);
            String text = (textChar != null) ? textChar.toString() : "";

            if (!text.isEmpty()) {
                String messageToSend = "📩 <b>Новое в MAX (" + title + "):</b>\n\n" + text;
                sendToTelegramAsync(messageToSend);
            }
        }
    }

    private void sendToTelegramAsync(final String message) {
        // Сетевые запросы в Android нельзя делать в главном потоке
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String urlString = "https://api.telegram.org/bot" + TG_BOT_TOKEN + "/sendMessage";
                    URL url = new URL(urlString);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                    String postData = "chat_id=" + TG_CHAT_ID 
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
