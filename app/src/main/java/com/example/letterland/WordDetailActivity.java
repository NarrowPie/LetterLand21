package com.example.letterland;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WordDetailActivity extends AppCompatActivity {

    private TextToSpeech textToSpeech;
    private boolean isTtsReady = false;
    private String wordText;
    private String imagePath;
    private String sourcePage;
    private boolean isNewWord = false;
    private TextView tvWord;
    private ImageView ivPicture;

    // FIX: Managed background task executor instead of dangerous nested threads
    private ExecutorService detailExecutor;

    private final ActivityResultLauncher<Void> takePictureLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicturePreview(),
            bitmap -> {
                if (bitmap != null) {
                    detailExecutor.execute(() -> updateImageInDatabase(bitmap));
                } else {
                    Toast.makeText(this, "No picture taken", Toast.LENGTH_SHORT).show();
                }
            }
    );

    private final ActivityResultLauncher<String> pickImageLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    // FIX: Replaced MediaStore.getBitmap with modern Scoped Storage compliant ImageDecoder framework
                    detailExecutor.execute(() -> {
                        try {
                            ImageDecoder.Source source = ImageDecoder.createSource(this.getContentResolver(), uri);
                            Bitmap bitmap = ImageDecoder.decodeBitmap(source);
                            updateImageInDatabase(bitmap);
                        } catch (Exception e) {
                            e.printStackTrace();
                            runOnUiThread(() -> Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show());
                        }
                    });
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_word_detail);

        detailExecutor = Executors.newSingleThreadExecutor();

        ivPicture = findViewById(R.id.ivDetailPicture);
        tvWord = findViewById(R.id.tvDetailWord);
        View btnBack = findViewById(R.id.btnDetailBack);
        View btnSpeak = findViewById(R.id.btnSpeak);

        View layoutEditButtons = findViewById(R.id.layoutEditButtons);
        View btnRename = findViewById(R.id.btnDetailRename);
        View btnNewImage = findViewById(R.id.btnDetailNewImage);
        View btnDelete = findViewById(R.id.btnDetailDelete);

        View llScanControls = findViewById(R.id.llScanControls);
        View btnScanAgain = findViewById(R.id.btnScanAgain);
        View btnScanDelete = findViewById(R.id.btnScanDelete);

        View llWriteControls = findViewById(R.id.llWriteControls);
        View btnWriteDelete = findViewById(R.id.btnWriteDelete);
        View btnWriteAgain = findViewById(R.id.btnWriteAgain);

        wordText = getIntent().getStringExtra("WORD_TEXT");
        imagePath = getIntent().getStringExtra("IMAGE_PATH");
        sourcePage = getIntent().getStringExtra("SOURCE_PAGE");
        isNewWord = getIntent().getBooleanExtra("IS_NEW_WORD", false);

        if (wordText != null) tvWord.setText(wordText);

        // FIX: Replaced synchronous main thread file path rendering with high performance Glide caching paths
        if (imagePath != null && !imagePath.isEmpty()) {
            Glide.with(this)
                    .load(new File(imagePath))
                    .placeholder(R.drawable.admin_pic)
                    .error(R.drawable.admin_pic)
                    .into(ivPicture);
        }

        if (isNewWord && ("WRITE".equals(sourcePage) || "SCANNER".equals(sourcePage))) {
            detailExecutor.execute(() -> {
                try {
                    String player = getSharedPreferences("LetterLandMemory", MODE_PRIVATE).getString("ACTIVE_PROFILE", "Default");
                    // FIX: Wrapped Room context queries via getApplicationContext() to isolate leaks
                    AppDatabase db = AppDatabase.getInstance(this.getApplicationContext());
                    WordEntry checkExist = db.wordDao().findWordForProfile(wordText, player);

                    if (checkExist == null) {
                        WordEntry newEntry = new WordEntry(wordText, player, imagePath);
                        newEntry.isStarred = false;

                        db.wordDao().insert(newEntry);

                        try {
                            String logDetails = wordText + "|" + imagePath + "|" + player;
                            db.logDao().insertLog(new LogEntry("ADDED WORD", logDetails, System.currentTimeMillis()));
                        } catch (Exception logEx) {
                            logEx.printStackTrace();
                        }

                        runOnUiThread(() -> Toast.makeText(this, wordText + " saved to Almanac!", Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        // Layout controls setup
        if ("ALMANAC".equals(sourcePage)) {
            if (llScanControls != null) llScanControls.setVisibility(View.GONE);
            if (llWriteControls != null) llWriteControls.setVisibility(View.GONE);
            if (layoutEditButtons != null) layoutEditButtons.setVisibility(View.GONE);
            if (btnSpeak != null) btnSpeak.setVisibility(View.VISIBLE);
        } else if ("EDIT_ALMANAC".equals(sourcePage)) {
            if (llScanControls != null) llScanControls.setVisibility(View.GONE);
            if (llWriteControls != null) llWriteControls.setVisibility(View.GONE);
            if (layoutEditButtons != null) layoutEditButtons.setVisibility(View.VISIBLE);
            if (btnSpeak != null) btnSpeak.setVisibility(View.VISIBLE);
        } else if ("SCANNER".equals(sourcePage)) {
            if (layoutEditButtons != null) layoutEditButtons.setVisibility(View.GONE);
            if (llWriteControls != null) llWriteControls.setVisibility(View.GONE);
            if (llScanControls != null) llScanControls.setVisibility(View.VISIBLE);
            if (btnScanDelete != null && btnScanAgain != null) {
                if (isNewWord) {
                    btnScanDelete.setVisibility(View.VISIBLE);
                    btnScanDelete.setClickable(true);
                } else {
                    btnScanDelete.setVisibility(View.GONE);
                    btnScanDelete.setClickable(false);

                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) btnScanAgain.getLayoutParams();
                    params.weight = 2.0f;
                    params.setMarginStart(0);
                    btnScanAgain.setLayoutParams(params);
                }
            }
            if (btnSpeak != null) btnSpeak.setVisibility(View.VISIBLE);
        } else if ("WRITE".equals(sourcePage)) {
            if (layoutEditButtons != null) layoutEditButtons.setVisibility(View.GONE);
            if (llScanControls != null) llScanControls.setVisibility(View.GONE);
            if (llWriteControls != null) llWriteControls.setVisibility(View.VISIBLE);
            if (btnWriteDelete != null && btnWriteAgain != null) {
                if (isNewWord) {
                    btnWriteDelete.setVisibility(View.VISIBLE);
                    btnWriteDelete.setClickable(true);
                } else {
                    btnWriteDelete.setVisibility(View.GONE);
                    btnWriteDelete.setClickable(false);

                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) btnWriteAgain.getLayoutParams();
                    params.weight = 2.0f;
                    params.setMarginStart(0);
                    btnWriteAgain.setLayoutParams(params);
                }
            }
            if (btnSpeak != null) btnSpeak.setVisibility(View.VISIBLE);
        } else {
            if (llScanControls != null) llScanControls.setVisibility(View.GONE);
            if (layoutEditButtons != null) layoutEditButtons.setVisibility(View.GONE);
            if (llWriteControls != null) llWriteControls.setVisibility(View.GONE);
        }

        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(Locale.US);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    runOnUiThread(() -> Toast.makeText(this, "Language not supported for voice.", Toast.LENGTH_SHORT).show());
                } else {
                    isTtsReady = true;
                }
            }
        });

        if (btnSpeak != null) {
            btnSpeak.setOnClickListener(v -> {
                if (!isTtsReady) {
                    Toast.makeText(this, "Voice is loading, please wait...", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (wordText != null) {
                    String optimizedString = wordText.trim().toLowerCase(Locale.US);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build();
                        textToSpeech.setAudioAttributes(audioAttributes);

                        Bundle params = new Bundle();
                        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
                        textToSpeech.speak(optimizedString, TextToSpeech.QUEUE_FLUSH, params, "TTS_ID_1");
                    } else {
                        textToSpeech.speak(optimizedString, TextToSpeech.QUEUE_FLUSH, null, "TTS_ID_1");
                    }
                }
            });
        }

        if (btnNewImage != null) {
            btnNewImage.setOnClickListener(v -> {
                SoundManager.getInstance(this).playClick();
                new AlertDialog.Builder(this)
                        .setTitle("Change Picture")
                        .setMessage("How do you want to add the new picture for '" + wordText + "'?")
                        .setPositiveButton("Camera", (dialog, which) -> {
                            SoundManager.getInstance(this).playShutter();
                            takePictureLauncher.launch(null);
                        })
                        .setNeutralButton("Gallery", (dialog, which) -> pickImageLauncher.launch("image/*"))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }

        if (btnRename != null) {
            btnRename.setOnClickListener(v -> {
                EditText input = new EditText(this);
                input.setText(wordText);

                new AlertDialog.Builder(this)
                        .setTitle("Rename Object")
                        .setView(input)
                        .setPositiveButton("Save", (dialog, which) -> {
                            String newName = input.getText().toString().toUpperCase().trim();

                            if (!newName.isEmpty() && !newName.equals(wordText)) {
                                detailExecutor.execute(() -> {
                                    String player = getSharedPreferences("LetterLandMemory", MODE_PRIVATE).getString("ACTIVE_PROFILE", "Default");
                                    AppDatabase db = AppDatabase.getInstance(this.getApplicationContext());
                                    WordEntry checkExisting = db.wordDao().findWordForProfile(newName, player);

                                    if (checkExisting != null) {
                                        runOnUiThread(() -> {
                                            Toast.makeText(this, "The word '" + newName + "' already exists!\nPlease choose a different name.", Toast.LENGTH_LONG).show();
                                        });
                                    } else {
                                        WordEntry oldEntry = db.wordDao().findWordForProfile(wordText, player);
                                        if (oldEntry != null) {
                                            WordEntry newEntry = new WordEntry(newName, oldEntry.profileName, oldEntry.imagePath);
                                            newEntry.isStarred = oldEntry.isStarred;
                                            db.wordDao().insert(newEntry);
                                            db.wordDao().delete(oldEntry);

                                            runOnUiThread(() -> {
                                                wordText = newName;
                                                tvWord.setText(wordText);
                                                Toast.makeText(this, "Renamed to " + newName, Toast.LENGTH_SHORT).show();
                                            });
                                        }
                                    }
                                });
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }

        if (btnDelete != null) btnDelete.setOnClickListener(v -> showCustomDeleteDialog());
        if (btnWriteDelete != null) btnWriteDelete.setOnClickListener(v -> showCustomDeleteDialog());
        if (btnScanDelete != null) btnScanDelete.setOnClickListener(v -> showCustomDeleteDialog());
        if (btnScanAgain != null) btnScanAgain.setOnClickListener(v -> finish());
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (btnWriteAgain != null) {
            btnWriteAgain.setOnClickListener(v -> {
                SoundManager.getInstance(this).playClick();

                if (isNewWord) {
                    Intent intent = new Intent(WordDetailActivity.this, AlmanacActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                }
                finish();
            });
        }
    }

    private void showCustomDeleteDialog() {
        SoundManager.getInstance(this).playClick();
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_delete, null);
        AlertDialog deleteDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();
        if (deleteDialog.getWindow() != null) {
            deleteDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        dialogView.findViewById(R.id.btnCancelDelete).setOnClickListener(v1 -> {
            SoundManager.getInstance(this).playClick();
            deleteDialog.dismiss();
        });
        dialogView.findViewById(R.id.btnConfirmDelete).setOnClickListener(v1 -> {
            SoundManager.getInstance(this).playClick();
            deleteDialog.dismiss();
            detailExecutor.execute(this::deleteWordFromDatabase);
        });
        deleteDialog.show();
    }

    private void deleteWordFromDatabase() {
        String player = getSharedPreferences("LetterLandMemory", MODE_PRIVATE).getString("ACTIVE_PROFILE", "Default");
        AppDatabase db = AppDatabase.getInstance(this.getApplicationContext());

        WordEntry entry = db.wordDao().findWordForProfile(wordText, player);
        if (entry != null) {
            db.wordDao().delete(entry);

            String logDetails = entry.word + "|" + entry.imagePath + "|" + entry.profileName;
            db.logDao().insertLog(new LogEntry("DELETED WORD", logDetails, System.currentTimeMillis()));
        }

        runOnUiThread(() -> {
            Toast.makeText(this, wordText + " deleted!", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void updateImageInDatabase(Bitmap bitmap) {
        String fileName = "word_" + wordText + "_" + System.currentTimeMillis() + ".jpg";
        java.io.File file = new java.io.File(getExternalFilesDir(null), fileName);

        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            String newImagePath = file.getAbsolutePath();

            String player = getSharedPreferences("LetterLandMemory", MODE_PRIVATE).getString("ACTIVE_PROFILE", "Default");
            AppDatabase db = AppDatabase.getInstance(this.getApplicationContext());

            WordEntry oldEntry = db.wordDao().findWordForProfile(wordText, player);
            boolean wasStarred = oldEntry != null && oldEntry.isStarred;

            WordEntry updatedEntry = new WordEntry(wordText, player, newImagePath);
            updatedEntry.isStarred = wasStarred;

            db.wordDao().insert(updatedEntry);

            runOnUiThread(() -> {
                imagePath = newImagePath;
                Glide.with(this)
                        .load(new File(imagePath))
                        .placeholder(R.drawable.admin_pic)
                        .error(R.drawable.admin_pic)
                        .into(ivPicture);
                Toast.makeText(this, "Picture Updated!", Toast.LENGTH_SHORT).show();
            });
        } catch (java.io.IOException e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(this, "Error saving picture!", Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        if (detailExecutor != null) {
            detailExecutor.shutdown();
        }
        super.onDestroy();
    }
}