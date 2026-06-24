package com.example.maxforwarder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    public static final String BOT_TOKEN = "5085657849:AAFZJRZtrIUBOUXMFtcuYR_to491szyrpeY";
    public static final String CHAT_ID = "-1004363923060";

    private ListView lvApps;
    private SharedPreferences prefs;
    private AppAdapter adapter;
    private boolean isPolling = false;
    private int lastUpdateId = 0;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        prefs = getSharedPreferences("MaxForwarderPrefs", Context.MODE_PRIVATE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        tvStatus = new TextView(this);
        tvStatus.setText("Опрос Telegram запущен...");
        tvStatus.setTextSize(14);
        layout.addView(tvStatus);

        Button btnAccess = new Button(this);
        btnAccess.setText("1. Включить доступ к уведомлениям");
        layout.addView(btnAccess);

        Button btnSave = new Button(this);
        btnSave.setText("2. Сохранить выбранные приложения");
        layout.addView(btnSave);

        TextView tvList = new TextView(this);
        tvList.setText("\nВыберите приложения для пересылки:");
        tvList.setTextSize(16);
        layout.addView(tvList);

        lvApps = new ListView(this);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        lvApps.setLayoutParams(listParams);
        layout.addView(lvApps);

        setContentView(layout);

        btnAccess.setOnClickListener(v -> {
            try {
                Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show();
            }
        });

        btnSave.setOnClickListener(v -> {
            if (adapter != null) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putStringSet("allowed_packages", adapter.getCheckedPackages());
                editor.apply();
                Toast.makeText(this, "Приложения сохранены!", Toast.LENGTH_SHORT).show();
            }
        });

        loadInstalledApps();
        startTelegramPolling();
    }

private void loadInstalledApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages;
        
        try {
            // Запрашиваем абсолютно все приложения, включая скрытые и системные компоненты
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION.SDK_INT) {
                packages = pm.getInstalledApplications(PackageManager.MATCH_ALL);
            } else {
                packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            }
        } catch (Exception e) {
            packages = pm.getInstalledApplications(0);
        }

        List<AppInfo> appList = new ArrayList<>();
        Set<String> savedPackages = prefs.getStringSet("allowed_packages", new HashSet<String>());

        for (ApplicationInfo packageInfo : packages) {
            // Убираем жесткий фильтр, чтобы показать вообще ВСЕ приложения (и системные, и пользовательские)
            String label = packageInfo.loadLabel(pm).toString();
            boolean isChecked = savedPackages.contains(packageInfo.packageName);
            appList.add(new AppInfo(label, packageInfo.packageName, isChecked));
        }
        
        // Сортируем список по алфавиту для удобства поиска
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            appList.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        }

        adapter = new AppAdapter(this, appList);
        lvApps.setAdapter(adapter);
    }

    private void startTelegramPolling() {
        if (isPolling) return;
        isPolling = true;

        new Thread(() -> {
            while (isPolling) {
                try {
                    checkTelegramUpdates(BOT_TOKEN, CHAT_ID);
                } catch (Exception e) {
                    Log.e("MaxForwarder", "Ошибка сети", e);
                }
                try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
            }
        }).start();
    }

    private void checkTelegramUpdates(String botToken, String myChatId) {
        try {
            String urlString = "https://api.telegram.org/bot" + botToken + "/getUpdates?offset=" + (lastUpdateId + 1);
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);

            if (conn.getResponseCode() == 200) {
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
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e("MaxForwarder", "Ошибка API", e);
        }
    }

    private String getPhoneStatus() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
            
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            String netType = activeNetwork != null && activeNetwork.isConnectedOrConnecting() ? activeNetwork.getTypeName() : "Нет сети";

            return "🤖 <b>Статус POCO:</b>\n🔋 Заряд: " + level + "%\n🌐 Сеть: " + netType;
        } catch (Exception e) { return "🤖 Ошибка чтения датчиков"; }
    }

    public static void sendRawMessage(final String message, final String botToken, final String chatId, final int threadId) {
        new Thread(() -> {
            try {
                String urlString = "https://api.telegram.org/bot" + botToken + "/sendMessage";
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                String postData = "chat_id=" + chatId + "&text=" + URLEncoder.encode(message, "UTF-8") + "&parse_mode=HTML";
                if (threadId > 0) postData += "&message_thread_id=" + threadId;

                OutputStream os = conn.getOutputStream();
                os.write(postData.getBytes("UTF-8"));
                os.flush(); os.close();
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) { Log.e("MaxForwarder", "Ошибка отправки", e); }
        }).start();
    }

    @Override
    protected void onDestroy() {
        isPolling = false;
        super.onDestroy();
    }

    private static class AppInfo {
        String name; String packageName; boolean isChecked;
        AppInfo(String n, String p, boolean c) { name = n; packageName = p; isChecked = c; }
    }

    private static class AppAdapter extends ArrayAdapter<AppInfo> {
        private final List<AppInfo> list; private final Activity context;
        AppAdapter(Activity context, List<AppInfo> list) {
            super(context, android.R.layout.simple_list_item_multiple_choice, list);
            this.context = context; this.list = list;
        }
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convertView;
            if (row == null) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(16, 16, 16, 16);
                CheckBox cb = new CheckBox(context); cb.setId(View.generateViewId()); row.addView(cb);
                TextView tv = new TextView(context); tv.setId(View.generateViewId()); tv.setTextSize(16); row.addView(tv);
            }
            final AppInfo info = list.get(position);
            final CheckBox cb = row.findViewById(row.getChildAt(0).getId());
            TextView tv = row.findViewById(row.getChildAt(1).getId());
            tv.setText(info.name + "\n(" + info.packageName + ")");
            cb.setOnCheckedChangeListener(null); cb.setChecked(info.isChecked);
            cb.setOnCheckedChangeListener((buttonView, isChecked) -> info.isChecked = isChecked);
            row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
            return row;
        }
        Set<String> getCheckedPackages() {
            Set<String> checked = new HashSet<>();
            for (AppInfo info : list) if (info.isChecked) checked.add(info.packageName);
            return checked;
        }
    }
}
