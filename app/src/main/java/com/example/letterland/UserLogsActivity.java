package com.example.letterland;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UserLogsActivity extends AppCompatActivity {

    private RecyclerView rvLogsList;
    private LogAdapter logAdapter;

    // FIX: Managed background work task pool eliminates uncontrolled loop leak threats
    private ExecutorService logExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_logs);

        logExecutor = Executors.newSingleThreadExecutor();

        MaterialButton btnBack = findViewById(R.id.btnLogsBack);
        rvLogsList = findViewById(R.id.rvUserLogsList);

        btnBack.setOnClickListener(v -> {
            SoundManager.getInstance(this).playClick();
            finish();
        });

        rvLogsList.setLayoutManager(new LinearLayoutManager(this));

        logAdapter = new LogAdapter(this, new ArrayList<>());
        rvLogsList.setAdapter(logAdapter);

        loadDataFromDatabase();
    }

    private void loadDataFromDatabase() {
        // FIX: Shifted from raw anonymous background threads onto the managed execution worker pool
        logExecutor.execute(() -> {
            // FIX: Bound database extraction to the global application context structure
            AppDatabase db = AppDatabase.getInstance(this.getApplicationContext());
            List<LogEntry> historyLogs = db.logDao().getHistoryLogs();

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                logAdapter.updateData(historyLogs);
            });
        });
    }

    @Override
    protected void onDestroy() {
        if (logExecutor != null) {
            logExecutor.shutdown();
        }
        super.onDestroy();
    }
}