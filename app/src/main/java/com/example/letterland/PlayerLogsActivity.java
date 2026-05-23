package com.example.letterland;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerLogsActivity extends AppCompatActivity {

    private RecyclerView rvPlayerLogs;
    private TextView tvEmptyLogs;
    private PlayerLogAdapter adapter;
    private final List<LogEntry> allLogs = new ArrayList<>();

    private MaterialButton btnFilterAll, btnFilterDeleted, btnFilterAdded, btnFilterEdited;
    private long lastFilterTouchTime = 0;

    // FIX: Managed workflow worker service handles filter conversions and safe database processing
    private ExecutorService playerLogExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player_logs);

        playerLogExecutor = Executors.newSingleThreadExecutor();

        setSafeTouchListener(findViewById(R.id.btnBackPlayerLogs), this::finish);

        tvEmptyLogs = findViewById(R.id.tvEmptyLogs);
        rvPlayerLogs = findViewById(R.id.rvPlayerLogs);
        rvPlayerLogs.setLayoutManager(new LinearLayoutManager(this));

        adapter = new PlayerLogAdapter(new ArrayList<>());
        rvPlayerLogs.setAdapter(adapter);

        btnFilterAll = findViewById(R.id.btnFilterAll);
        btnFilterDeleted = findViewById(R.id.btnFilterDeleted);
        btnFilterAdded = findViewById(R.id.btnFilterAdded);
        btnFilterEdited = findViewById(R.id.btnFilterEdited);

        setSafeTouchListener(btnFilterAll, () -> applyFilter("ALL"));
        setSafeTouchListener(btnFilterDeleted, () -> applyFilter("DELETED"));
        setSafeTouchListener(btnFilterAdded, () -> applyFilter("ADDED"));
        setSafeTouchListener(btnFilterEdited, () -> applyFilter("EDITED"));

        loadLogsFromDatabase();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setSafeTouchListener(View view, Runnable action) {
        if (view == null) return;
        view.setOnClickListener(null);
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastFilterTouchTime < 500) {
                    return true;
                }
                lastFilterTouchTime = currentTime;
                SoundManager.getInstance(PlayerLogsActivity.this).playClick();
                action.run();
                return true;
            }
            return false;
        });
    }

    private void loadLogsFromDatabase() {
        // FIX: Replaced raw thread executions with explicit background service configurations
        playerLogExecutor.execute(() -> {
            // FIX: Prevent context reference leakage using getApplicationContext() points
            List<LogEntry> rawLogs = AppDatabase.getInstance(this.getApplicationContext()).logDao().getAllLogs();

            final List<LogEntry> updatedPlayerLogs = new ArrayList<>();
            for (LogEntry log : rawLogs) {
                if ("PLAYER_LOG".equals(log.action)) {
                    updatedPlayerLogs.add(log);
                }
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                allLogs.clear();
                allLogs.addAll(updatedPlayerLogs);
                applyFilter("ALL");
            });
        });
    }

    private void applyFilter(String filterType) {
        List<LogEntry> filteredList = new ArrayList<>();

        for (LogEntry log : allLogs) {
            String[] parts = log.details.split("\\|");
            if (parts.length < 2) continue;
            String type = parts[0];

            if (filterType.equals("ALL")) {
                filteredList.add(log);
            } else if (filterType.equals("DELETED") && type.equals("DELETED")) {
                filteredList.add(log);
            } else if (filterType.equals("ADDED") && (type.equals("ADDED") || type.equals("RESTORED"))) {
                filteredList.add(log);
            } else if (filterType.equals("EDITED") && (type.equals("EDITED") || type.equals("RENAMED_FROM"))) {
                filteredList.add(log);
            }
        }

        if (filteredList.isEmpty()) {
            tvEmptyLogs.setVisibility(View.VISIBLE);
            rvPlayerLogs.setVisibility(View.GONE);
        } else {
            tvEmptyLogs.setVisibility(View.GONE);
            rvPlayerLogs.setVisibility(View.VISIBLE);
            adapter.updateData(filteredList);
        }
    }

    @Override
    protected void onDestroy() {
        if (playerLogExecutor != null) {
            playerLogExecutor.shutdown();
        }
        super.onDestroy();
    }

    private class PlayerLogAdapter extends RecyclerView.Adapter<PlayerLogAdapter.LogViewHolder> {
        private List<LogEntry> logs;
        private final SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.US);
        private long lastRestoreTouchTime = 0;

        public PlayerLogAdapter(List<LogEntry> logs) {
            this.logs = logs;
        }

        public void updateData(List<LogEntry> newLogs) {
            this.logs = newLogs;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_player_log, parent, false);
            return new LogViewHolder(view);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
            LogEntry log = logs.get(position);

            String[] parts = log.details.split("\\|");
            String type = parts.length > 0 ? parts[0] : "UNKNOWN";
            String playerName = parts.length > 1 ? parts[1] : "Unknown Player";

            holder.tvLogPlayerName.setText(playerName);
            holder.tvLogDate.setText(sdf.format(new Date(log.timestamp)));

            if (type.equals("DELETED")) {
                holder.tvLogAction.setText("Profile was deleted");
                holder.tvLogAction.setTextColor(Color.parseColor("#F44336"));
                holder.ivLogIcon.setColorFilter(Color.parseColor("#F44336"));
                holder.ivLogIcon.setImageResource(android.R.drawable.ic_menu_delete);
                holder.btnRestorePlayer.setVisibility(View.VISIBLE);

                SharedPreferences prefs = getSharedPreferences("LetterLandMemory", MODE_PRIVATE);
                Set<String> currentProfiles = prefs.getStringSet("ALL_PROFILES", new HashSet<>());

                if (currentProfiles.contains(playerName)) {
                    holder.btnRestorePlayer.setEnabled(false);
                    holder.btnRestorePlayer.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#E0E0E0")));
                    holder.btnRestorePlayer.setTextColor(Color.parseColor("#4CAF50"));
                    holder.btnRestorePlayer.setIconResource(0);
                    holder.btnRestorePlayer.setText("RESTORED");
                } else {
                    holder.btnRestorePlayer.setEnabled(true);
                    holder.btnRestorePlayer.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4CAF50")));
                    holder.btnRestorePlayer.setTextColor(Color.WHITE);
                    holder.btnRestorePlayer.setIconResource(android.R.drawable.ic_menu_revert);
                    holder.btnRestorePlayer.setIconTint(ColorStateList.valueOf(Color.WHITE));
                    holder.btnRestorePlayer.setText("RESTORE");
                }

            } else if (type.equals("ADDED")) {
                holder.tvLogAction.setText("Added as a new profile");
                holder.tvLogAction.setTextColor(Color.parseColor("#4CAF50"));
                holder.ivLogIcon.setColorFilter(Color.parseColor("#4CAF50"));
                holder.ivLogIcon.setImageResource(android.R.drawable.ic_menu_add);
                holder.btnRestorePlayer.setVisibility(View.GONE);

            } else if (type.equals("RESTORED")) {
                holder.tvLogAction.setText("Profile was restored");
                holder.tvLogAction.setTextColor(Color.parseColor("#4CAF50"));
                holder.ivLogIcon.setColorFilter(Color.parseColor("#4CAF50"));
                holder.ivLogIcon.setImageResource(android.R.drawable.ic_menu_revert);
                holder.btnRestorePlayer.setVisibility(View.GONE);

            } else if (type.equals("EDITED")) {
                holder.tvLogAction.setText("Changed avatar image");
                holder.tvLogAction.setTextColor(Color.parseColor("#FF9800"));
                holder.ivLogIcon.setColorFilter(Color.parseColor("#FF9800"));
                holder.ivLogIcon.setImageResource(android.R.drawable.ic_menu_edit);
                holder.btnRestorePlayer.setVisibility(View.GONE);

            } else if (type.equals("RENAMED_FROM")) {
                String oldName = parts.length > 2 ? parts[2] : "Unknown";
                holder.tvLogPlayerName.setText(playerName);
                holder.tvLogAction.setText("Renamed: " + playerName + " ➔ " + oldName);
                holder.tvLogAction.setTextColor(Color.parseColor("#FF9800"));
                holder.ivLogIcon.setColorFilter(Color.parseColor("#FF9800"));
                holder.ivLogIcon.setImageResource(android.R.drawable.ic_menu_edit);
                holder.btnRestorePlayer.setVisibility(View.GONE);
            }

            holder.btnRestorePlayer.setOnClickListener(null);
            holder.btnRestorePlayer.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP && holder.btnRestorePlayer.isEnabled()) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastRestoreTouchTime < 1000) {
                        return true;
                    }
                    lastRestoreTouchTime = currentTime;

                    SoundManager.getInstance(PlayerLogsActivity.this).playClick();

                    holder.btnRestorePlayer.setEnabled(false);
                    holder.btnRestorePlayer.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#E0E0E0")));
                    holder.btnRestorePlayer.setTextColor(Color.parseColor("#4CAF50"));
                    holder.btnRestorePlayer.setIconResource(0);
                    holder.btnRestorePlayer.setText("RESTORED");

                    SharedPreferences sharedPrefs = getSharedPreferences("LetterLandMemory", MODE_PRIVATE);
                    Set<String> oldProfiles = sharedPrefs.getStringSet("ALL_PROFILES", new HashSet<>());
                    Set<String> newProfiles = new HashSet<>(oldProfiles);

                    if (newProfiles.contains(playerName)) {
                        Toast.makeText(PlayerLogsActivity.this, "This profile already exists!", Toast.LENGTH_SHORT).show();
                        return true;
                    }

                    newProfiles.add(playerName);
                    sharedPrefs.edit().putStringSet("ALL_PROFILES", newProfiles).apply();

                    Toast.makeText(PlayerLogsActivity.this, playerName + " has been RESTORED!", Toast.LENGTH_LONG).show();

                    // FIX: Transitioned thread management onto the safety pool
                    playerLogExecutor.execute(() -> {
                        LogEntry restoredLog = new LogEntry("PLAYER_LOG", "RESTORED|" + playerName, System.currentTimeMillis());
                        AppDatabase.getInstance(PlayerLogsActivity.this.getApplicationContext()).logDao().insertLog(restoredLog);

                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                loadLogsFromDatabase();
                            }
                        });
                    });

                    return true;
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return logs.size();
        }

        class LogViewHolder extends RecyclerView.ViewHolder {
            ImageView ivLogIcon;
            TextView tvLogPlayerName, tvLogAction, tvLogDate;
            MaterialButton btnRestorePlayer;

            public LogViewHolder(@NonNull View itemView) {
                super(itemView);
                ivLogIcon = itemView.findViewById(R.id.ivLogIcon);
                tvLogPlayerName = itemView.findViewById(R.id.tvLogPlayerName);
                tvLogAction = itemView.findViewById(R.id.tvLogAction);
                tvLogDate = itemView.findViewById(R.id.tvLogDate);
                btnRestorePlayer = itemView.findViewById(R.id.btnRestorePlayer);
            }
        }
    }
}