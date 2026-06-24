package com.example.maxforwarder;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
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
    // Хранилище созданных топиков в формате Ключ: Название_Чата, Значение: ID_Топика
    private static final Map<String, Integer> topicCache = new HashMap<>();
    
    private boolean isPolling = false;
    private int lastUpdateId = 0;
    private static final String CHANNEL_ID = "MaxForwarderServiceChannel";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        
        // Включаем постоянное уведомление (Foreground), чтобы Android не закрывал службу
        Notification notification = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("MAX Forwarder активен")
                    .setContentText("Фоновая пересылка запущена и защищена от закрытия")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setContentTitle("MAX Forwarder активен")
                    .setContentText("Фоновая пересылка запущена")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build();
        }
        
        // Идентификатор службы 101
        startForeground(101, notification);
        startTelegramPolling();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // Перезапускать службу, если она всё же упадет
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String currentPackage = sbn.getPackageName();
        
        SharedPreferences prefs = getSharedPreferences("MaxForwarderPrefs", Context.MODE_PRIVATE);
        Set<String> allowedPackages = prefs.getStringSet("allowed_packages", new HashSet<String>());

        if (!allowedPackages.contains(currentPackage)) {
            return;
        }

        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;

        String title = extras.getString(Notification.EXTRA_TITLE, "");
        CharSequence textChar = extras.getCharSequence(Notification.EXTRA_TEXT);
        String text = (textChar != null) ? textChar.toString() : "";

        if (!text.isEmpty() && !title.isEmpty()) {
            activeNotifications.put(title, notification);

            String botToken = prefs.getString("tg_bot_token", "");
            String chatId = prefs.getString("tg_chat_id", "");

            if (botToken.isEmpty() || chatId.isEmpty()) return;

            String appLabel = "Приложение";
            try {
                appLabel = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(currentPackage, 0)).toString();
            } catch (Exception e) {
                if (currentPackage.contains("mms") || currentPackage.contains("messaging")) {
                    appLabel = "SMS";
                }
            }

            // Имя топика (группировка по приложениям: например "MAX" или "SMS")
            String topicName = appLabel;
            String messageToSend = "<b>" + title + ":</b>\n\n" + text;
            
            // Отправляем в конкретный топик
            sendToTelegramAsync(messageToSend, botToken, chatId, topicName, title);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        if (extras != null) {
            String title = extras.getString(Notification.EXTRA_TITLE);
            if (title != null) {
                activeNotifications.remove(title);
            }
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

                        String text = message.optString("text", "").trim();
                        int topicId = message.has("message_thread_id") ? message.getInt("message_thread_id") : 0;

                        // ОБРАБОТКА КОМАНДЫ СТАТУСА
                        if (text.equalsIgnoreCase("/status")) {
                            String statusReport = getPhoneStatus();
                            sendRawMessage(statusReport, botToken, myChatId, topicId);
                            continue;
                        }

                        // ОБРАБОТКА ОТВЕТОВ РЕПЛАЕМ
                        if (message.has("reply_to_message") && !text.isEmpty()) {
                            JSONObject replyTo = message.getJSONObject("reply_to_message");
                            String replyText = replyTo.optString("text", "");

                            if (replyText.contains(":") && replyText.startsWith("<b>")) {
                                int endOfName = replyText.indexOf(":</b>");
                                if (endOfName != -1) {
                                    String chatTitle = replyText.substring(7, endOfName).trim();

                                    boolean success = replyToMax(chatTitle, text);
                                    String status = success ? "✅ Ответ отправлен" : "❌ Ошибка: Уведомление уже исчезло";
                                    sendRawMessage(status, botToken, myChatId, topicId);
                                }
                            }
                        }
                    }
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e("MaxForwarder", "Ошибка обработки обновлений", e);
        }
    }

    // Метод получения статуса смартфона
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

            return "🤖 <b>Статус телефона:</b>\n" +
                   "🔋 Заряд батареи: " + level + "%\n" +
                   "🌐 Тип сети: " + netType + "\n" +
                   "🚀 Служба пересылки: Активна";
        } catch (Exception e) {
            return "🤖 Служба активна, не удалось считать датчики.";
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
                            Log.e("MaxForwarder", "Ошибка ввода", e);
                        }
                    }
                }
            }
        }
        return false;
    }

    // Асинхронная отправка с динамическим созданием топиков под каждое приложение
    private void sendToTelegramAsync(final String message, final String botToken, final String chatId, final String topicName, final String senderName) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int threadId = 0;
                    
                    if (topicCache.containsKey(topicName)) {
                        threadId = topicCache.get(topicName);
                    } else {
                        // Запрос на создание топика в группе Telegram
                        String urlString = "https://api.telegram.org/bot" + botToken + "/createForumTopic";
                        URL url = new URL(urlString);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                        String postData = "chat_id=" + chatId + "&name=" + URLEncoder.encode(topicName, "UTF-8");
                        OutputStream os = conn.getOutputStream();
                        os.write(postData.getBytes("UTF-8"));
                        os.flush(); os.close();

                        if (conn.getResponseCode() == 200) {
                            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                            StringBuilder res = new StringBuilder(); String line;
                            while ((line = in.readLine()) != null) res.append(line);
                            in.close();

                            JSONObject json = new JSONObject(res.toString());
                            threadId = json.getJSONObject("result").getInt("message_thread_id");
                            topicCache.put(topicName, threadId);
                        }
                        conn.disconnect();
                    }

                    // Отправка самого сообщения в созданный топик
                    String msgUrl = "https://api.telegram.org/bot" + botToken + "/sendMessage";
                    URL url = new URL(msgUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                    String postData = "chat_id=" + chatId 
                                    + "&text=" + URLEncoder.encode("<b>" + senderName + ":</b>\n\n" + message.substring(message.indexOf("\n\n") + 2), "UTF-8") 
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
                    Log.e("MaxForwarder", "Ошибка отправки в топик", e);
                }
            }
        }).start();
    }

    // Простая прямая отправка без создания топиков (для статусов и системных ответов)
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
                    Log.e("MaxForwarder", "Ошибка отправки системного сообщения", e);
                }
            }
        }).start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Max Forwarder Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        isPolling = false;
        super.onDestroy();
    }
}
