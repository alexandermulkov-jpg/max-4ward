package com.example.maxforwarder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private EditText etBotToken;
    private EditText etChatId;
    private ListView lvApps;
    private SharedPreferences prefs;
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        prefs = getSharedPreferences("MaxForwarderPrefs", Context.MODE_PRIVATE);

        // Динамическое создание интерфейса
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        TextView tvToken = new TextView(this);
        tvToken.setText("Telegram Bot Token:");
        tvToken.setTextSize(16);
        layout.addView(tvToken);

        etBotToken = new EditText(this);
        // Если в памяти пусто, подставляем ваш токен по умолчанию
        etBotToken.setText(prefs.getString("tg_bot_token", "5085657849:AAFZJRZtrIUBOUXMFtcuYR_to491szyrpeY"));
        layout.addView(etBotToken);

        TextView tvChat = new TextView(this);
        tvChat.setText("Telegram Group ID:");
        tvChat.setTextSize(16);
        layout.addView(tvChat);

        etChatId = new EditText(this);
        // Если в памяти пусто, подставляем ваш ID группы по умолчанию
        etChatId.setText(prefs.getString("tg_chat_id", "-1004363923060"));
        layout.addView(etChatId);

        Button btnSave = new Button(this);
        btnSave.setText("Сохранить настройки и доступ");
        layout.addView(btnSave);

        TextView tvList = new TextView(this);
        tvList.setText("\nВыберите приложения для пересылки:");
        layout.addView(tvList);

        lvApps = new ListView(this);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        lvApps.setLayoutParams(listParams);
        layout.addView(lvApps);

        setContentView(layout);

        // Кнопка сохранения
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String token = etBotToken.getText().toString().trim();
                String chat = etChatId.getText().toString().trim();

                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("tg_bot_token", token);
                editor.putString("tg_chat_id", chat);

                if (adapter != null) {
                    editor.putStringSet("allowed_packages", adapter.getCheckedPackages());
                }
                editor.apply();

                Toast.makeText(MainActivity.this, "Настройки сохранены!", Toast.LENGTH_SHORT).show();

                // Открываем настройки доступа к уведомлениям Android
                try {
                    Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Откройте доступ к уведомлениям вручную", Toast.LENGTH_LONG).show();
                }
            }
        });

        // Загрузка списка приложений
        loadInstalledApps();
    }

    private void loadInstalledApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<AppInfo> appList = new ArrayList<>();
        Set<String> savedPackages = prefs.getStringSet("allowed_packages", new HashSet<String>());

        for (ApplicationInfo packageInfo : packages) {
            // Пропускаем системный хлам, оставляем только пользовательские приложения и SMS
            if ((packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0 || 
                packageInfo.packageName.contains("telephony") || 
                packageInfo.packageName.contains("mms") || 
                packageInfo.packageName.contains("messaging")) {
                
                String label = packageInfo.loadLabel(pm).toString();
                boolean isChecked = savedPackages.contains(packageInfo.packageName);
                appList.add(new AppInfo(label, packageInfo.packageName, isChecked));
            }
        }

        adapter = new AppAdapter(this, appList);
        lvApps.setAdapter(adapter);
    }

    // Класс для хранения инфо о приложении
    private static class AppInfo {
        String name;
        String packageName;
        boolean isChecked;

        AppInfo(String name, String packageName, boolean isChecked) {
            this.name = name;
            this.packageName = packageName;
            this.isChecked = isChecked;
        }
    }

    // Кастомный адаптер для списка с чекбоксами
    private static class AppAdapter extends ArrayAdapter<AppInfo> {
        private final List<AppInfo> list;
        private final Activity context;

        AppAdapter(Activity context, List<AppInfo> list) {
            super(context, android.R.layout.simple_list_item_multiple_choice, list);
            this.context = context;
            this.list = list;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convertView;
            if (row == null) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(16, 16, 16, 16);

                CheckBox cb = new CheckBox(context);
                cb.setId(View.generateViewId());
                row.addView(cb);

                TextView tv = new TextView(context);
                tv.setId(View.generateViewId());
                tv.setTextSize(16);
                row.addView(tv);
            }

            final AppInfo info = list.get(position);
            final CheckBox cb = row.findViewById(row.getChildAt(0).getId());
            TextView tv = row.findViewById(row.getChildAt(1).getId());

            tv.setText(info.name + "\n(" + info.packageName + ")");
            
            // Снимаем слушатель перед установкой статуса, чтобы избежать зацикливания
            cb.setOnCheckedChangeListener(null);
            cb.setChecked(info.isChecked);

            cb.setOnCheckedChangeListener((buttonView, isChecked) -> info.isChecked = isChecked);

            // Клик по всей строке переключает чекбокс
            row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));

            return row;
        }

        Set<String> getCheckedPackages() {
            Set<String> checked = new HashSet<>();
            for (AppInfo info : list) {
                if (info.isChecked) {
                    checked.add(info.packageName);
                }
            }
            return checked;
        }
    }
}
