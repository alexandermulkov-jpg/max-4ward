package com.example.maxforwarder;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.HashSet;
import java.util.Set;

public class MaxNotificationListener extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String currentPackage = sbn.getPackageName();
        SharedPreferences prefs = getSharedPreferences("MaxForwarderPrefs", Context.MODE_PRIVATE);
        Set<String> allowedPackages = prefs.getStringSet("allowed_packages", new HashSet<String>());

        // Проверяем, стоит ли галочка для этого приложения
        if (!allowedPackages.contains(currentPackage)) {
            return;
        }

        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;

        String title = extras.getString(Notification.EXTRA_TITLE, "");
        CharSequence textChar = extras.getCharSequence(Notification.EXTRA_TEXT);
        String text = (textChar != null) ? textChar.toString() : "";

        if (!text.isEmpty() && !title.isEmpty()) {
            String appLabel = "Приложение";
            try {
                appLabel = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(currentPackage, 0)).toString();
            } catch (Exception e) {
                if (currentPackage.contains("mms") || currentPackage.contains("messaging")) {
                    appLabel = "SMS";
                }
            }

            String messageToSend = "<b>" + title + " (" + appLabel + "):</b>\n\n" + text;
            
            // Отправляем сообщение напрямую в группу, без создания топиков (чтобы упростить код и не злить систему)
            MainActivity.sendRawMessage(messageToSend, MainActivity.BOT_TOKEN, MainActivity.CHAT_ID, 0);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Оставляем пустым, нам не нужно отслеживать удаление
    }
}
