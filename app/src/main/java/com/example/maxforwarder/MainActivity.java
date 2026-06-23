package com.example.maxforwarder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private EditText etBotToken;
    private EditText etChatId;
    private LinearLayout appsContainer;
    private SharedPreferences prefs;
    private PackageManager packageManager;
    private List<AppInfo> installedApps = new ArrayList<>();

    // Класс для удобного хранения данных об элементе списка
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

        // Поля ввода Telegram
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

        // Кнопка сохранения данных
        Button btnSave = new Button(this);
        btnSave.setText("Сохранить настройки и доступ");
        mainLayout.addView(btnSave);

        // Заголовок для списка приложений
        TextView tvAppsTitle = new TextView(this);
        tvAppsTitle.setText("Выберите приложения для пересылки:");
        tvAppsTitle.setTextSize(16);
        tvAppsTitle.setPadding(0, 48, 0, 16);
        mainLayout.addView(tvAppsTitle);

        // Контейнер, куда асинхронно добавятся чекбоксы программ
        appsContainer = new LinearLayout(this);
        appsContainer.setOrientation(LinearLayout.VERTICAL);
        mainLayout.addView(appsContainer);

        scrollView.addView(mainLayout);
        setContentView(scrollView);

        // Загружаем список приложений в фоновом потоке, чтобы UI не зависал
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

                // Собираем пакеты всех отмеченных приложений
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

                // Сохраняем всё в SharedPreferences
                prefs.edit()
                     .putString("tg_bot_token", token)
                     .putString("tg_chat_id", chatId)
                     .putStringSet("allowed_packages", selectedPackages)
                     .apply();

                Toast.makeText(MainActivity.this, "Настройки успешно сохранены!", Toast.LENGTH_SHORT).show();

                // Открываем доступ к чтению пушей
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                startActivity(intent);
            }
        });
    }

    // Фоновая задача для чтения списка установленного ПО
    private class LoadAppsTask extends AsyncTask<Void, Void, List<AppInfo>> {
        @Override
        protected List<AppInfo> doInBackground(Void... voids) {
            List<AppInfo> apps = new ArrayList<>();
            Set<String> savedPackages = prefs.getStringSet("allowed_packages", new HashSet<String>());
            
            List<ApplicationInfo> pkgs = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);

            for (ApplicationInfo app : pkgs) {
                // Отсекаем системные утилиты без иконки/запускаемого интерфейса, оставляя пользовательские
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && 
                    !app.packageName.contains("mms") && !app.packageName.contains("messaging")) {
                    continue; 
                }

                AppInfo info = new AppInfo();
                info.label = app.loadLabel(packageManager).toString();
                info.packageName = app.packageName;
                info.isChecked = savedPackages.contains(app.packageName);
                apps.add(info);
            }

            // Сортировка по алфавиту для удобства
            Collections.sort(apps, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo o1, AppInfo o2) {
                    return o1.label.compareToIgnoreCase(o2.label);
                }
            });

            return apps;
        }

        @Override
        protected void onPostExecute(List<AppInfo> apps) {
            installedApps = apps;
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
