package com.example.letterland;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProfilesActivity extends AppCompatActivity {

    private RecyclerView rvProfiles;
    private ProfileAdapter adapter;
    private SharedPreferences prefs;
    private String profileAwaitingImage = "";

    // FIX: Replaced raw thread models with a managed background task executor service
    private ExecutorService profileExecutor;

    // FIX: Scoped Storage optimized avatar processing using non-deprecated ImageDecoder API
    private final ActivityResultLauncher<String> pickAvatarLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null && !profileAwaitingImage.isEmpty()) {
                    try {
                        ImageDecoder.Source source = ImageDecoder.createSource(this.getContentResolver(), uri);
                        Bitmap bitmap = ImageDecoder.decodeBitmap(source);
                        saveAvatarToStorage(profileAwaitingImage, bitmap);
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profiles);

        profileExecutor = Executors.newSingleThreadExecutor();
        prefs = getSharedPreferences("LetterLandMemory", MODE_PRIVATE);
        rvProfiles = findViewById(R.id.rvProfiles);
        rvProfiles.setLayoutManager(new LinearLayoutManager(this));

        ExtendedFloatingActionButton fabAddProfile = findViewById(R.id.fabAddProfile);
        ImageButton btnBack = findViewById(R.id.btnBackProfiles);
        boolean isMandatoryLogin = getIntent().getBooleanExtra("IS_MANDATORY_LOGIN", false);

        if (isMandatoryLogin) {
            btnBack.setVisibility(View.GONE);
        } else {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> {
                SoundManager.getInstance(this).playClick();
                finish();
            });
        }

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isMandatoryLogin) {
                    finishAffinity();
                } else {
                    finish();
                }
            }
        });

        loadProfiles();

        fabAddProfile.setOnClickListener(v -> {
            SoundManager.getInstance(ProfilesActivity.this).playClick();
            showAddProfileDialog();
        });
    }

    private void loadProfiles() {
        Set<String> savedProfiles = prefs.getStringSet("ALL_PROFILES", new HashSet<>());
        List<String> profileList = new ArrayList<>(savedProfiles);
        adapter = new ProfileAdapter(profileList);
        rvProfiles.setAdapter(adapter);
    }

    private void saveAvatarToStorage(String profileName, Bitmap bitmap) {
        String fileName = "avatar_" + profileName + ".jpg";
        java.io.File file = new java.io.File(getExternalFilesDir(null), fileName);

        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            prefs.edit().putString("AVATAR_" + profileName, file.getAbsolutePath()).apply();

            recordPlayerLog(profileName, "EDITED");

            runOnUiThread(() -> {
                Toast.makeText(this, "Profile picture updated!", Toast.LENGTH_SHORT).show();
                loadProfiles();
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showAddProfileDialog() {
        EditText input = new EditText(this);
        input.setHint("Enter your Name");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});

        new AlertDialog.Builder(this)
                .setTitle("New Player")
                .setView(input)
                .setPositiveButton("Add", (dialog, which) -> {
                    String newName = input.getText().toString().trim().toUpperCase();

                    if (!newName.isEmpty()) {
                        Set<String> oldProfiles = prefs.getStringSet("ALL_PROFILES", new HashSet<>());

                        if (oldProfiles.contains(newName)) {
                            Toast.makeText(this, "A player with this name already exists!", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        Set<String> newProfiles = new HashSet<>(oldProfiles);
                        newProfiles.add(newName);

                        prefs.edit().putStringSet("ALL_PROFILES", newProfiles).apply();
                        recordPlayerLog(newName, "ADDED");
                        loadProfiles();
                    } else {
                        Toast.makeText(this, "Name cannot be empty!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRenameProfileDialog(String oldProfileName) {
        EditText input = new EditText(this);
        input.setText(oldProfileName);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle("Rename Player")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newName = input.getText().toString().trim().toUpperCase();

                    if (newName.isEmpty() || newName.equals(oldProfileName)) return;

                    Set<String> oldProfiles = prefs.getStringSet("ALL_PROFILES", new HashSet<>());
                    if (oldProfiles.contains(newName)) {
                        Toast.makeText(this, "A player with this name already exists!", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Set<String> newProfiles = new HashSet<>(oldProfiles);
                    newProfiles.remove(oldProfileName);
                    newProfiles.add(newName);

                    prefs.edit().putStringSet("ALL_PROFILES", newProfiles).apply();

                    if (oldProfileName.equals(prefs.getString("ACTIVE_PROFILE", ""))) {
                        prefs.edit().putString("ACTIVE_PROFILE", newName).apply();
                    }

                    String avatarPath = prefs.getString("AVATAR_" + oldProfileName, null);
                    if (avatarPath != null) {
                        prefs.edit().putString("AVATAR_" + newName, avatarPath).apply();
                        prefs.edit().remove("AVATAR_" + oldProfileName).apply();
                    }

                    // FIX: Replaced raw thread structures with executor bindings linked to Application Context references
                    profileExecutor.execute(() -> {
                        AppDatabase db = AppDatabase.getInstance(this.getApplicationContext());
                        db.wordDao().updateProfileName(newName, oldProfileName);
                        db.quizRecordDao().updateProfileName(newName, oldProfileName);
                        db.logDao().updateProfileNameInLogs(oldProfileName, newName);

                        recordPlayerLog(newName, "RENAMED_FROM|" + oldProfileName);

                        runOnUiThread(() -> {
                            Toast.makeText(this, oldProfileName + " renamed to " + newName, Toast.LENGTH_SHORT).show();
                            loadProfiles();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void recordPlayerLog(String playerName, String logType) {
        profileExecutor.execute(() -> {
            long timestamp = System.currentTimeMillis();
            LogEntry log = new LogEntry("PLAYER_LOG", logType + "|" + playerName, timestamp);
            AppDatabase.getInstance(this.getApplicationContext()).logDao().insertLog(log);
        });
    }

    @Override
    protected void onDestroy() {
        if (profileExecutor != null) {
            profileExecutor.shutdown();
        }
        super.onDestroy();
    }

    private class ProfileAdapter extends RecyclerView.Adapter<ProfileAdapter.ProfileViewHolder> {
        private final List<String> profiles;

        public ProfileAdapter(List<String> profiles) {
            this.profiles = profiles;
        }

        @NonNull
        @Override
        public ProfileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_profile, parent, false);
            return new ProfileViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ProfileViewHolder holder, int position) {
            String profileName = profiles.get(position);
            holder.tvProfileName.setText(profileName);

            String avatarPath = prefs.getString("AVATAR_" + profileName, null);

            // FIX: Implemented Glide to parse absolute sandboxed file directory path values seamlessly
            if (avatarPath != null && !avatarPath.isEmpty()) {
                Glide.with(holder.ivAvatar.getContext())
                        .load(new File(avatarPath))
                        .placeholder(R.drawable.admin_pic)
                        .error(R.drawable.admin_pic)
                        .into(holder.ivAvatar);
            } else {
                holder.ivAvatar.setImageResource(R.drawable.admin_pic);
            }

            String activePlayer = prefs.getString("ACTIVE_PROFILE", "");
            com.google.android.material.card.MaterialCardView cardView = (com.google.android.material.card.MaterialCardView) holder.itemView;

            if (profileName.equals(activePlayer)) {
                cardView.setStrokeColor(android.graphics.Color.parseColor("#4CAF50"));
                cardView.setStrokeWidth(8);
            } else {
                cardView.setStrokeColor(android.graphics.Color.parseColor("#29B6F6"));
                cardView.setStrokeWidth(3);
            }

            holder.ivAvatar.setOnClickListener(v -> {
                SoundManager.getInstance(ProfilesActivity.this).playClick();
                profileAwaitingImage = profileName;
                pickAvatarLauncher.launch("image/*");
            });

            holder.itemView.setOnClickListener(v -> {
                SoundManager.getInstance(ProfilesActivity.this).playClick();
                prefs.edit().putString("ACTIVE_PROFILE", profileName).apply();
                Toast.makeText(ProfilesActivity.this, profileName + " is now playing!", Toast.LENGTH_SHORT).show();
                finish();
            });

            holder.btnEditProfile.setOnClickListener(v -> {
                SoundManager.getInstance(ProfilesActivity.this).playClick();
                showRenameProfileDialog(profileName);
            });

            holder.btnDeleteProfile.setOnClickListener(v -> {
                SoundManager.getInstance(ProfilesActivity.this).playClick();
                new AlertDialog.Builder(ProfilesActivity.this)
                        .setTitle("Delete " + profileName + "?")
                        .setMessage("Are you sure?")
                        .setPositiveButton("Delete", (dialog, which) -> {
                            Set<String> oldProfiles = prefs.getStringSet("ALL_PROFILES", new HashSet<>());
                            Set<String> newProfiles = new HashSet<>(oldProfiles);
                            newProfiles.remove(profileName);
                            prefs.edit().putStringSet("ALL_PROFILES", newProfiles).apply();
                            prefs.edit().remove("AVATAR_" + profileName).apply();

                            recordPlayerLog(profileName, "DELETED");

                            if (profileName.equals(prefs.getString("ACTIVE_PROFILE", ""))) {
                                if (!newProfiles.isEmpty()) {
                                    String fallbackProfile = newProfiles.iterator().next();
                                    prefs.edit().putString("ACTIVE_PROFILE", fallbackProfile).apply();
                                } else {
                                    prefs.edit().putString("ACTIVE_PROFILE", "").apply();
                                }
                            }
                            loadProfiles();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }

        @Override
        public int getItemCount() {
            return profiles.size();
        }

        class ProfileViewHolder extends RecyclerView.ViewHolder {
            TextView tvProfileName;
            ImageView ivAvatar;
            ImageButton btnEditProfile;
            ImageButton btnDeleteProfile;

            public ProfileViewHolder(@NonNull View itemView) {
                super(itemView);
                tvProfileName = itemView.findViewById(R.id.tvProfileName);
                ivAvatar = itemView.findViewById(R.id.ivAvatar);
                btnEditProfile = itemView.findViewById(R.id.btnEditProfile);
                btnDeleteProfile = itemView.findViewById(R.id.btnDeleteProfile);
            }
        }
    }
}