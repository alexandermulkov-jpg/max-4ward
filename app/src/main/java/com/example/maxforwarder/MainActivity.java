package com.example.maxforwarder;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Создаем кнопку стандартными средствами чистого Android
        Button btn = new Button(this);
        btn.setText("Дать разрешение на чтение уведомлений");
        setContentView(btn);

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Открываем системный экран разрешений к пушам
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                startActivity(intent);
                Toast.makeText(MainActivity.this, "Найдите в списке 'MAX To TG Forwarder' и включите его", Toast.LENGTH_LONG).show();
            }
        });
    }
}
