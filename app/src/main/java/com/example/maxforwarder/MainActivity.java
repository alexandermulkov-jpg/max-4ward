package com.example.maxforwarder;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Кнопка для перехода в настройки спец. прав Windows/Android
        Button btn = new Button(this);
        btn.setText("Дать разрешение на чтение уведомлений");
        setContentView(btn);

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Открываем системное окно "Доступ к уведомлениям"
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                startActivity(intent);
                Toast.makeText(MainActivity.this, "Найдите в списке 'MAX To TG Forwarder' и включите его", Toast.LENGTH_LONG).show();
            }
        });
    }
}
