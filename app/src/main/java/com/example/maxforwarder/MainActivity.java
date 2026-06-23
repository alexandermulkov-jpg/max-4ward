package com.example.maxforwarder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    private EditText etBotToken;
    private EditText etChatId;
    private LinearLayout appsContainer;
    private SharedPreferences prefs;
    private PackageManager packageManager;
    private List<AppInfo> installedApps = new ArrayList<>();

    private static class AppInfo {
        String label;
        String packageName;
        boolean isChecked;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("MaxForwarderPrefs", Context.MODE_PRIVATE);
        packageManager = getPackageManager();

        ScrollView scrollView = new ScrollView(this);
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(32, 32, 32, 32);

        TextView tvToken = new TextView(this);
        tvToken.setText("Telegram Bot Token:");
        mainLayout.addView(tvToken);

        etBotToken = new EditText(this);
        etBotToken.setHint("5085657849:AAFZJR...");
        etBotToken.setText(prefs.getString("tg_bot_token", ""));
        mainLayout.addView(etBotToken);

        TextView tvChatId = new TextView(this);
        tvChatId.setText("Telegram User ID:");
        tvChatId.setPadding(0, 24, 0, 0);
        mainLayout.addView(tvChatId);

        etChatId = new EditText(this);
        etChatId.setHint("123456789");
        etChatId.setText(prefs.getString("tg_chat_id", ""));
        mainLayout.addView(etChatId);

        Button btnSave = new Button(this);
        btnSave.setText("Сохранить настройки и доступ");
        mainLayout.addView(btnSave);

        TextView tvAppsTitle = new TextView(this);
        tvAppsTitle.setText("Выберите приложения для пересылки:");
        tvAppsTitle.setTextSize(16);
        tvAppsTitle.setPadding(0, 48, 0, 16);
        mainLayout.addView(tvAppsTitle);

        appsContainer = new LinearLayout(this);
        appsContainer.setOrientation(LinearLayout.VERTICAL);
        mainLayout.addView(appsContainer);

        scrollView.addView(mainLayout);
        setContentView(scrollView);

        new LoadAppsTask().execute();

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String token = etBotToken.getText().toString().trim();
                String chatId = etChatId.getText().toString().trim();

                if (token.isEmpty() || chatId.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Заполните поля токена и ID", Toast.LENGTH_SHORT).show();
                    return;
                }

                Set<String> selectedPackages = new HashSet<>();
                for (int i = 0; i < appsContainer.getChildCount(); i++) {
                    View child = appsContainer.getChildAt(i);
                    if (child instanceof CheckBox) {
                        CheckBox cb = (CheckBox) child;
                        if (cb.isChecked()) {
                            selectedPackages.add((String) cb.getTag());
                        }
                    }
                }

                prefs.edit()
                     .putString("tg_bot_token", token)
                     .putString("tg_chat_id", chatId)
                     .putStringSet("allowed_packages", selectedPackages)
                     .apply();

                Toast.makeText(MainActivity.this, "Настройки успешно сохранены!", Toast.LENGTH_SHORT).show();

                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                startActivity(intent);
            }
        });
    }

    private class LoadAppsTask extends AsyncTask<Void, Void, List<AppInfo>> {
        @Override
        protected List<AppInfo> doInBackground(Void... voids) {
            Map<String, AppInfo> appsMap = new HashMap<>();
            Set<String> savedPackages = prefs.getStringSet("allowed_packages", new HashSet<String>());

            // 1. Ищем все приложения, которые отображаются в меню телефона (содержат LAUNCHER)
            Intent intent = new Intent(Intent.ACTION_MAIN, null);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> viewableApps = packageManager.queryIntentActivities(intent, 0);

            for (ResolveInfo info : viewableApps) {
                ApplicationInfo appInfo = info.activityInfo.applicationInfo;
                if (appInfo != null && appInfo.packageName != null) {
                    String label = appInfo.loadLabel(packageManager).toString();
                    
                    // Фильтруем системные, но если это наше собственное приложение, оставляем
                    boolean isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    if (isSystem && !appInfo.packageName.equals(getPackageName())) {
                        continue;
                    }

                    AppInfo app = new AppInfo();
                    app.label = label;
                    app.packageName = appInfo.packageName;
                    app.isChecked = savedPackages.contains(appInfo.packageName);
                    appsMap.put(app.packageName, app);
                }
            }

            // 2. Принудительно добавляем известные системные пакеты для СМС и мессенджеров
            // (на случай, если они скрыты политиками ОС как "сервисы")
            String[] customPackages = {
                "ru.oneme.app",                               // Оригинальный MAX
                "com.android.mms",                            // Стандартные СМС на многих прошивках
                "com.google.android.apps.messaging",          // Гугл СМС (Xiaomi, Pixel и др.)
                "com.android.messaging"                       // Чистый Android СМС
            };

            for (String pkg : customPackages) {
                try {
                    ApplicationInfo ai = packageManager.getApplicationInfo(pkg, 0);
                    if (!appsMap.containsKey(pkg)) {
                        AppInfo app = new AppInfo();
                        app.label = ai.loadLabel(packageManager).toString();
                        app.packageName = pkg;
                        app.isChecked = savedPackages.contains(pkg);
                        appsMap.put(pkg, app);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    // Если такого приложения физически нет на устройстве — просто пропускаем
                }
            }

            // Переводим карту в список и сортируем
            List<AppInfo> sortedList = new ArrayList<>(appsMap.values());
            Collections.sort(sortedList, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo o1, AppInfo o2) {
                    return o1.label.compareToIgnoreCase(o2.label);
                }
            });

            return sortedList;
        }

        @Override
        protected void onPostExecute(List<AppInfo> apps) {
            installedApps = apps;
            appsContainer.removeAllViews();
            for (AppInfo app : installedApps) {
                CheckBox cb = new CheckBox(MainActivity.this);
                cb.setText(app.label + " (" + app.packageName + ")");
                cb.setTag(app.packageName);
                cb.setChecked(app.isChecked);
                appsContainer.addView(cb);
            }
        }
    }
}
