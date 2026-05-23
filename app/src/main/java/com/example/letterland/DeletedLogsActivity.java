package com.example.letterland;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeletedLogsActivity extends AppCompatActivity {

    private RecyclerView rvDeletedLogs;
    private DeletedLogAdapter adapter;

    // FIX: Assigned window builder reference
    private AlertDialog restoreConfirmationDialog;
    private ExecutorService deletedLogExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_deleted_logs);

        deletedLogExecutor = Executors.newSingleThreadExecutor();

        MaterialButton btnBack = findViewById(R.id.btnDeletedLogsBack);
        rvDeletedLogs = findViewById(R.id.rvDeletedLogs);
        rvDeletedLogs.setLayoutManager(new LinearLayoutManager(this));

        btnBack.setOnClickListener(v -> {
            SoundManager.getInstance(this).playClick();
            finish();
        });

        loadLogs();
    }

    private void loadLogs() {
        // FIX: Swapped unmanaged background
        deletedLogExecutor.execute(() -> {
            List<LogEntry> allLogs = AppDatabase.getInstance(this.getApplicationContext()).logDao().getAllLogs();
            List<LogEntry> deletedLogs = new ArrayList<>();

            for (LogEntry log : allLogs) {
                if ("DELETED WORD".equals(log.action)) {
                    deletedLogs.add(log);
                }
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter = new DeletedLogAdapter(deletedLogs);
                rvDeletedLogs.setAdapter(adapter);
            });
        });
    }

    private void showRestoreDialog(LogEntry log, String wordName, String imagePath, String profileName, String deleterName) {
        if (isFinishing() || isDestroyed()) return;

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_restore_item, null);

        restoreConfirmationDialog = new AlertDialog.Builder(this)
                .setView(view)
                .create();

        if (restoreConfirmationDialog.getWindow() != null) {
            restoreConfirmationDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        ImageView ivRestoreImage = view.findViewById(R.id.ivRestoreImage);
        TextView tvRestoreDetails = view.findViewById(R.id.tvRestoreDetails);

        // FIX: Pass standard local File definitions rather than bare text strings to ensure platform consistency
        if (imagePath != null && !imagePath.isEmpty()) {
            Glide.with(this).load(new File(imagePath)).into(ivRestoreImage);
        }

        tvRestoreDetails.setText("Object: " + wordName + "\nPROFILE: " + profileName + "\nPROFILE: " + deleterName);

        view.findViewById(R.id.btnCancelRestore).setOnClickListener(v -> {
            SoundManager.getInstance(this).playClick();
            restoreConfirmationDialog.dismiss();
        });

        view.findViewById(R.id.btnConfirmRestore).setOnClickListener(v -> {
            SoundManager.getInstance(this).playClick();
            restoreConfirmationDialog.dismiss();

            deletedLogExecutor.execute(() -> {
                AppDatabase db = AppDatabase.getInstance(this.getApplicationContext());

                WordEntry existingWord = db.wordDao().findWordForProfile(wordName, profileName);
                if (existingWord != null) {
                    runOnUiThread(() -> Toast.makeText(this, profileName + " already has a word named " + wordName + "!", Toast.LENGTH_LONG).show());
                    return;
                }

                WordEntry restoredWord = new WordEntry(wordName, profileName, imagePath);
                db.wordDao().insert(restoredWord);

                db.logDao().deleteLog(log);
                db.logDao().insertLog(new LogEntry("PLAYER_LOG", "RESTORED_WORD|" + wordName + " for " + profileName, System.currentTimeMillis()));

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, wordName + " restored successfully for " + profileName + "!", Toast.LENGTH_SHORT).show();
                    loadLogs();
                });
            });
        });

        restoreConfirmationDialog.show();
    }

    @Override
    protected void onDestroy() {
        if (restoreConfirmationDialog != null && restoreConfirmationDialog.isShowing()) {
            restoreConfirmationDialog.dismiss();
        }
        if (deletedLogExecutor != null) {
            deletedLogExecutor.shutdown();
        }
        super.onDestroy();
    }

    private class DeletedLogAdapter extends RecyclerView.Adapter<DeletedLogAdapter.LogViewHolder> {
        private final List<LogEntry> logs;
        private final SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.getDefault());

        public DeletedLogAdapter(List<LogEntry> logs) {
            this.logs = logs;
        }

        @NonNull
        @Override
        public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_deleted_log, parent, false);
            return new LogViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
            LogEntry log = logs.get(position);
            holder.tvDeletedTime.setText("Time: " + sdf.format(new Date(log.timestamp)));

            String[] parts = log.details.split("\\|");

            if (parts.length >= 2) {
                String wordName = parts[0];
                String imagePath = parts[1];
                String profileName = (parts.length >= 3) ? parts[2] : "Unknown User";
                String deleterName = (parts.length >= 4) ? parts[3] : profileName;

                holder.tvDeletedWord.setText("Deleted '" + wordName + "'\nPROFILE: " + deleterName);

                // FIX: Glide parsing
                if (imagePath != null && !imagePath.isEmpty()) {
                    Glide.with(DeletedLogsActivity.this)
                            .load(new File(imagePath))
                            .override(150, 150)
                            .placeholder(R.drawable.admin_pic)
                            .into(holder.ivDeletedImage);
                } else {
                    holder.ivDeletedImage.setImageResource(R.drawable.admin_pic);
                }

                holder.itemView.setOnClickListener(v -> {
                    SoundManager.getInstance(DeletedLogsActivity.this).playClick();
                    showRestoreDialog(log, wordName, imagePath, profileName, deleterName);
                });

            } else {
                holder.tvDeletedWord.setText(log.details);
                holder.ivDeletedImage.setImageResource(R.drawable.admin_pic);
                holder.itemView.setOnClickListener(null);
            }
        }

        @Override
        public int getItemCount() {
            return logs.size();
        }

        class LogViewHolder extends RecyclerView.ViewHolder {
            ImageView ivDeletedImage;
            TextView tvDeletedWord, tvDeletedTime;

            public LogViewHolder(@NonNull View itemView) {
                super(itemView);
                ivDeletedImage = itemView.findViewById(R.id.ivDeletedImage);
                tvDeletedWord = itemView.findViewById(R.id.tvDeletedWord);
                tvDeletedTime = itemView.findViewById(R.id.tvDeletedTime);
            }
        }
    }
}