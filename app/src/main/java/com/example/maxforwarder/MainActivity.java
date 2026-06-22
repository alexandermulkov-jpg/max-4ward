package com.example.maxforwarder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private EditText etBotToken;
    private EditText etChatId;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("MaxForwarderPrefs", Context.MODE_PRIVATE);

        // Создаем интерфейс программно (без XML файлов ресурсов)
        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        // Поле ввода токена бота
        TextView tvToken = new TextView(this);
        tvToken.setText("Telegram Bot Token:");
        layout.addView(tvToken);

        etBotToken = new EditText(this);
        etBotToken.setHint("5085657849:AAFZJR...");
        etBotToken.setText(prefs.getString("tg_bot_token", ""));
        layout.addView(etBotToken);

        // Поле ввода ID чата
        TextView tvChatId = new TextView(this);
        tvChatId.setText("Telegram User ID:");
        tvChatId.setPadding(0, 24, 0, 0);
        layout.addView(tvChatId);

        etChatId = new EditText(this);
        etChatId.setHint("123456789");
        etChatId.setText(prefs.getString("tg_chat_id", ""));
        layout.addView(etChatId);

        // Кнопка сохранения данных
        Button btnSave = new Button(this);
        btnSave.setText("Сохранить и настроить доступ");
        btnSave.setPadding(0, 32, 0, 0);
        layout.addView(btnSave);

        scrollView.addView(layout);
        setContentView(scrollView);

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String token = etBotToken.getText().toString().trim();
                String chatId = etChatId.getText().toString().trim();

                if (token.isEmpty() || chatId.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Пожалуйста, заполните все поля", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Сохраняем настройки в память телефона
                prefs.edit()
                     .putString("tg_bot_token", token)
                     .putString("tg_chat_id", chatId)
                     .apply();

                Toast.makeText(MainActivity.this, "Настройки сохранены!", Toast.LENGTH_SHORT).show();

                // Открываем системный экран для активации чтения пушей
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                startActivity(intent);
            }
        });
    }
}
