package com.example.letterland;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.google.mlkit.common.MlKitException;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.Ink;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WriteActivity extends AppCompatActivity {

    private DrawingView drawingView;
    private TextView tvLiveText;

    private DigitalInkRecognizer recognizer;
    private String pendingWord = "";
    private String currentlyDetectedWord = "";
    private final Handler scanHandler = new Handler(Looper.getMainLooper());
    private Runnable scanRunnable;

    private final Set<String> DICTIONARY = new HashSet<>();
    private String lastSpokenLetter = "";
    private int lastSpokenWordLength = 0;

    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private AlertDialog voiceDialog;
    private TextView tvVoiceStatus;
    private boolean isVoiceInputActive = false;
    private String rawVoiceOutputBuffer = "";

    // SMART CLEAR FIX: Persistent memory holding onto the target tracing word across stroke rewrites
    private String activeTracingWord = "";

    private TextToSpeech textToSpeech;
    private boolean isTtsReady = false;

    private boolean isButtonSpamLocked = false;
    private boolean isProceedingLocked = false;
    private final Handler spamHandler = new Handler(Looper.getMainLooper());

    // OPTIMIZATION FIX: Track asynchronous execution runnables to clean context references on window pause
    private Runnable autoProceedRunnable;

    // Managed thread model prevents leak profiles during component mutations
    private ExecutorService databaseExecutor;

    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    showCustomVoiceDialog();
                } else {
                    Toast.makeText(this, "Microphone permission denied!", Toast.LENGTH_LONG).show();
                }
            }
    );

    private final ActivityResultLauncher<Void> takePictureLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicturePreview(),
            bitmap -> {
                if (bitmap != null && !pendingWord.isEmpty()) {
                    try {
                        String tempName = "temp_word_" + System.currentTimeMillis() + ".jpg";
                        File tempFile = new File(getExternalFilesDir(null), tempName);
                        try (FileOutputStream out = new FileOutputStream(tempFile)) {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
                        }

                        Intent intent = new Intent(WriteActivity.this, WordDetailActivity.class);
                        intent.putExtra("WORD_TEXT", pendingWord);
                        intent.putExtra("IMAGE_PATH", tempFile.getAbsolutePath());
                        intent.putExtra("SOURCE_PAGE", "WRITE");
                        intent.putExtra("IS_NEW_WORD", true);
                        startActivity(intent);

                        resetCanvasAndText();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    resetCanvasAndText();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_write);

        databaseExecutor = Executors.newSingleThreadExecutor();

        drawingView = findViewById(R.id.drawingView);
        tvLiveText = findViewById(R.id.tvLiveText);
        MaterialButton btnClear = findViewById(R.id.btnClear);
        MaterialButton btnProceed = findViewById(R.id.btnProceed);
        ImageButton btnBack = findViewById(R.id.btnBackWrite);
        MaterialButton btnVoiceAssist = findViewById(R.id.btnVoiceAssist);
        MaterialButton btnSpeakLiveText = findViewById(R.id.btnSpeakLiveText);

        btnBack.setOnClickListener(v -> finish());

        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(Locale.US);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    runOnUiThread(() -> Toast.makeText(this, "Voice language not supported.", Toast.LENGTH_SHORT).show());
                } else {
                    isTtsReady = true;
                }
            }
        });

        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                SoundManager.getInstance(getApplicationContext()).duckBackgroundMusic();
            }

            @Override
            public void onDone(String utteranceId) {
                SoundManager.getInstance(getApplicationContext()).restoreBackgroundMusic();
            }

            @Override
            public void onError(String utteranceId) {
                SoundManager.getInstance(getApplicationContext()).restoreBackgroundMusic();
            }
        });

        if (btnSpeakLiveText != null) {
            btnSpeakLiveText.setOnClickListener(v -> {
                if (!isTtsReady) {
                    Toast.makeText(this, "Voice is loading...", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (isButtonSpamLocked) {
                    return;
                }

                if (currentlyDetectedWord != null && !currentlyDetectedWord.isEmpty() && !currentlyDetectedWord.equals("...")) {
                    isButtonSpamLocked = true;
                    speakTextDirectly(currentlyDetectedWord);
                    spamHandler.postDelayed(() -> isButtonSpamLocked = false, 1000);
                } else {
                    Toast.makeText(this, "Write something first!", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            initializeSpeechIntent();
            setupSpeechListener();
        }

        databaseExecutor.execute(this::loadDictionaryData);
        tvLiveText.setText("Loading Model...");

        try {
            DigitalInkRecognitionModelIdentifier modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US");
            DigitalInkRecognitionModel model = DigitalInkRecognitionModel.builder(modelIdentifier).build();

            RemoteModelManager.getInstance().download(model, new DownloadConditions.Builder().build())
                    .addOnSuccessListener(aVoid -> {
                        recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build());
                        tvLiveText.setText("...");
                    })
                    .addOnFailureListener(e -> tvLiveText.setText("Error"));
        } catch (MlKitException e) {
            e.printStackTrace();
        }

        drawingView.setOnDrawListener(new DrawingView.OnDrawListener() {
            @Override
            public void onDrawStarted() {
                SoundManager.getInstance(WriteActivity.this).startScratchSound();
                if (scanRunnable != null) scanHandler.removeCallbacks(scanRunnable);
            }

            @Override
            public void onDrawFinished() {
                SoundManager.getInstance(WriteActivity.this).stopScratchSound();
                scanRunnable = () -> performScan();
                scanHandler.postDelayed(scanRunnable, 600);
            }
        });

        btnVoiceAssist.setOnClickListener(v -> {
            SoundManager.getInstance(this).playClick();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                showCustomVoiceDialog();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            }
        });

        btnClear.setOnClickListener(v -> {
            SoundManager.getInstance(this).playClick();

            if (!activeTracingWord.isEmpty()) {
                if (drawingView.getInk().getStrokes().isEmpty()) {
                    resetCanvasAndText();
                } else {
                    drawingView.resetFullCanvas();
                    drawingView.setTracingWord(activeTracingWord);
                    tvLiveText.setText(activeTracingWord);
                    currentlyDetectedWord = activeTracingWord;
                    lastSpokenLetter = "";
                    lastSpokenWordLength = 0;
                    isProceedingLocked = false;
                }
            } else {
                resetCanvasAndText();
            }
        });

        btnProceed.setOnClickListener(v -> {
            SoundManager.getInstance(this).playClick();
            if (isProceedingLocked) {
                return;
            }

            if (drawingView.getInk().getStrokes().isEmpty()) {
                Toast.makeText(this, "Write clearly first!", Toast.LENGTH_SHORT).show();
                return;
            }

            if (currentlyDetectedWord.isEmpty() || currentlyDetectedWord.equals("...")) {
                Toast.makeText(this, "Write clearly first!", Toast.LENGTH_SHORT).show();
            } else {
                isProceedingLocked = true;
                checkWordDatabase(currentlyDetectedWord);
            }
        });
    }

    private void initializeSpeechIntent() {
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-PH");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
    }

    private void loadDictionaryData() {
        try {
            java.io.InputStream is = getAssets().open("dictionary.txt");
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.toUpperCase().trim();
                if (!word.isEmpty()) DICTIONARY.add(word);
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            String player = getSharedPreferences("LetterLandMemory", MODE_PRIVATE).getString("ACTIVE_PROFILE", "Default");
            List<WordEntry> savedWords = AppDatabase.getInstance(this.getApplicationContext()).wordDao().getAllWordsForProfile(player);
            for (WordEntry entry : savedWords) {
                DICTIONARY.add(entry.word.toUpperCase().trim());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupSpeechListener() {
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                runOnUiThread(() -> {
                    if (tvVoiceStatus != null && rawVoiceOutputBuffer.isEmpty()) {
                        tvVoiceStatus.setTextColor(Color.parseColor("#000000"));
                        tvVoiceStatus.setText("Speak now!");
                    }
                });
            }

            @Override
            public void onBeginningOfSpeech() {}
            @Override
            public void onRmsChanged(float rmsdB) {}
            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                runOnUiThread(() -> {
                    if (tvVoiceStatus != null && !rawVoiceOutputBuffer.isEmpty()) {
                        tvVoiceStatus.setTextColor(Color.parseColor("#000000"));
                        tvVoiceStatus.setText("Heard: " + rawVoiceOutputBuffer + "\nAuto-proceeding...");
                    }
                });
            }

            @Override
            public void onError(int error) {
                if (tvVoiceStatus == null) return;
                runOnUiThread(() -> {
                    switch (error) {
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                            tvVoiceStatus.setTextColor(Color.parseColor("#4CAF50"));
                            tvVoiceStatus.setText("Please say your word!");
                            break;
                        case SpeechRecognizer.ERROR_NO_MATCH:
                            tvVoiceStatus.setTextColor(Color.parseColor("#F44336"));
                            tvVoiceStatus.setText("Didn't catch that. Try again!");
                            break;
                        default:
                            tvVoiceStatus.setTextColor(Color.parseColor("#FF9800"));
                            tvVoiceStatus.setText("Tap Retry to speak.");
                            break;
                    }
                });
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                processVoiceResults(partialResults);
            }

            @Override
            public void onResults(Bundle results) {
                processVoiceResults(results);

                // OPTIMIZATION FIX: Stores reference explicitly to flush task callbacks upon rotation or exit passes
                autoProceedRunnable = () -> executeVoiceProceed(rawVoiceOutputBuffer);
                scanHandler.postDelayed(autoProceedRunnable, 1000);
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void processVoiceResults(Bundle bundle) {
        ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty() && tvVoiceStatus != null) {
            String rawInput = matches.get(0);
            rawVoiceOutputBuffer = cleanAndVerifySpeechInput(rawInput, matches);

            runOnUiThread(() -> {
                tvVoiceStatus.setTextColor(Color.parseColor("#000000"));
                tvVoiceStatus.setText("Heard: " + rawVoiceOutputBuffer);
            });
        }
    }

    private String cleanAndVerifySpeechInput(String primeText, ArrayList<String> alternatives) {
        String input = replaceDigitsWithWords(primeText.toUpperCase().trim());

        for (String candidate : alternatives) {
            String cleanCand = replaceDigitsWithWords(candidate.toUpperCase().trim());
            if (DICTIONARY.contains(cleanCand)) {
                return cleanCand;
            }
        }

        if (input.contains(" ") && !DICTIONARY.contains(input)) {
            boolean isNumberPhrase = false;
            String[] numberPrefixes = {
                    "TWENTY", "THIRTY", "FORTY", "FIFTY", "SIXTY", "SEVENTY", "EIGHTY", "NINETY", "ONE"
            };

            for (String prefix : numberPrefixes) {
                if (input.startsWith(prefix)) {
                    isNumberPhrase = true;
                    break;
                }
            }

            if (!isNumberPhrase) {
                input = input.substring(0, input.indexOf(" "));
            }
        }

        return input;
    }

    private String replaceDigitsWithWords(String text) {
        if (text == null) return "";
        String result = text;

        result = result.replace("100", "ONE HUNDRED");

        result = result.replace("99", "NINETY NINE");
        result = result.replace("98", "NINETY EIGHT");
        result = result.replace("97", "NINETY SEVEN");
        result = result.replace("96", "NINETY SIX");
        result = result.replace("95", "NINETY FIVE");
        result = result.replace("94", "NINETY FOUR");
        result = result.replace("93", "NINETY THREE");
        result = result.replace("92", "NINETY TWO");
        result = result.replace("91", "NINETY ONE");
        result = result.replace("90", "NINETY");

        result = result.replace("89", "EIGHTY NINE");
        result = result.replace("88", "EIGHTY EIGHT");
        result = result.replace("87", "EIGHTY SEVEN");
        result = result.replace("86", "EIGHTY SIX");
        result = result.replace("85", "EIGHTY FIVE");
        result = result.replace("84", "EIGHTY FOUR");
        result = result.replace("83", "EIGHTY THREE");
        result = result.replace("82", "EIGHTY TWO");
        result = result.replace("81", "EIGHTY ONE");
        result = result.replace("80", "EIGHTY");

        result = result.replace("79", "SEVENTY NINE");
        result = result.replace("78", "SEVENTY EIGHT");
        result = result.replace("77", "SEVENTY SEVEN");
        result = result.replace("76", "SEVENTY SIX");
        result = result.replace("75", "SEVENTY FIVE");
        result = result.replace("74", "SEVENTY FOUR");
        result = result.replace("73", "SEVENTY THREE");
        result = result.replace("72", "SEVENTY TWO");
        result = result.replace("71", "SEVENTY ONE");
        result = result.replace("70", "SEVENTY");

        result = result.replace("69", "SIXTY NINE");
        result = result.replace("68", "SIXTY EIGHT");
        result = result.replace("67", "SIXTY SEVEN");
        result = result.replace("66", "SIXTY SIX");
        result = result.replace("65", "SIXTY FIVE");
        result = result.replace("64", "SIXTY FOUR");
        result = result.replace("63", "SIXTY THREE");
        result = result.replace("62", "SIXTY TWO");
        result = result.replace("61", "SIXTY ONE");
        result = result.replace("60", "SIXTY");

        result = result.replace("59", "FIFTY NINE");
        result = result.replace("58", "FIFTY EIGHT");
        result = result.replace("57", "FIFTY SEVEN");
        result = result.replace("56", "FIFTY SIX");
        result = result.replace("55", "FIFTY FIVE");
        result = result.replace("54", "FIFTY FOUR");
        result = result.replace("53", "FIFTY THREE");
        result = result.replace("52", "FIFTY TWO");
        result = result.replace("51", "FIFTY ONE");
        result = result.replace("50", "FIFTY");

        result = result.replace("49", "FORTY NINE");
        result = result.replace("48", "FORTY EIGHT");
        result = result.replace("47", "FORTY SEVEN");
        result = result.replace("46", "FORTY SIX");
        result = result.replace("45", "FORTY FIVE");
        result = result.replace("44", "FORTY FOUR");
        result = result.replace("43", "FORTY THREE");
        result = result.replace("42", "FORTY TWO");
        result = result.replace("41", "FORTY ONE");
        result = result.replace("40", "FORTY");

        result = result.replace("39", "THIRTY NINE");
        result = result.replace("38", "THIRTY EIGHT");
        result = result.replace("37", "THIRTY SEVEN");
        result = result.replace("36", "THIRTY SIX");
        result = result.replace("35", "THIRTY FIVE");
        result = result.replace("34", "THIRTY FOUR");
        result = result.replace("33", "THIRTY THREE");
        result = result.replace("32", "THIRTY TWO");
        result = result.replace("31", "THIRTY ONE");
        result = result.replace("30", "THIRTY");

        result = result.replace("29", "TWENTY NINE");
        result = result.replace("28", "TWENTY EIGHT");
        result = result.replace("27", "TWENTY SEVEN");
        result = result.replace("26", "TWENTY SIX");
        result = result.replace("25", "TWENTY FIVE");
        result = result.replace("24", "TWENTY FOUR");
        result = result.replace("23", "TWENTY THREE");
        result = result.replace("22", "TWENTY TWO");
        result = result.replace("21", "TWENTY ONE");
        result = result.replace("20", "TWENTY");

        result = result.replace("19", "NINETEEN");
        result = result.replace("18", "EIGHTEEN");
        result = result.replace("17", "SEVENTEEN");
        result = result.replace("16", "SIXTEEN");
        result = result.replace("15", "FIFTEEN");
        result = result.replace("14", "FOURTEEN");
        result = result.replace("13", "THIRTEEN");
        result = result.replace("12", "TWELVE");
        result = result.replace("11", "ELEVEN");
        result = result.replace("10", "TEN");

        result = result.replace("9", "NINE");
        result = result.replace("8", "EIGHT");
        result = result.replace("7", "SEVEN");
        result = result.replace("6", "SIX");
        result = result.replace("5", "FIVE");
        result = result.replace("4", "FOUR");
        result = result.replace("3", "THREE");
        result = result.replace("2", "TWO");
        result = result.replace("1", "ONE");
        result = result.replace("0", "ZERO");

        return result;
    }

    private void performScan() {
        if (isFinishing() || isDestroyed() || recognizer == null) return;
        Ink ink = drawingView.getInk();
        if (ink.getStrokes().isEmpty()) return;

        recognizer.recognize(ink)
                .addOnSuccessListener(result -> {
                    if (isFinishing() || isDestroyed() || result.getCandidates().isEmpty()) return;

                    String rawText = result.getCandidates().get(0).getText().toUpperCase().trim();
                    rawText = rawText.replace("0", "O");
                    rawText = rawText.replace("1", "I");
                    rawText = rawText.replace("5", "S");
                    String cleanWord = rawText.replaceAll("[^A-Z]", "");

                    if (cleanWord.isEmpty()) {
                        tvLiveText.setText("...");
                        currentlyDetectedWord = "";
                        return;
                    }

                    if (cleanWord.length() > 20) {
                        cleanWord = cleanWord.substring(0, 20);
                    }

                    isVoiceInputActive = false;
                    currentlyDetectedWord = cleanWord;
                    tvLiveText.setText(cleanWord);

                    if (!cleanWord.isEmpty()) {
                        String newlyWrittenLetter = cleanWord.substring(cleanWord.length() - 1);
                        int currentLength = cleanWord.length();

                        if (!newlyWrittenLetter.equals(lastSpokenLetter) || currentLength != lastSpokenWordLength) {
                            lastSpokenLetter = newlyWrittenLetter;
                            lastSpokenWordLength = currentLength;
                            playLocalLetterSound(newlyWrittenLetter);
                        }
                    }
                });
    }

    private void playLocalLetterSound(String letter) {
        String resName = letter.toLowerCase().trim();
        int resId = getResources().getIdentifier(resName, "raw", getPackageName());
        if (resId != 0) {
            SoundManager.getInstance(this).playPhonicAsset(this, resId);
        }
    }

    private void executeVoiceProceed(String word) {
        runOnUiThread(() -> {
            if (voiceDialog == null || !voiceDialog.isShowing()) return;

            speechRecognizer.cancel();
            voiceDialog.dismiss();

            String verifiedWord = (word == null || word.isEmpty()) ? "MIC" : word;
            verifiedWord = verifiedWord.replaceAll("[^A-Z]", "");

            if (verifiedWord.length() > 13) {
                verifiedWord = verifiedWord.substring(0, 13);
            }

            if (verifiedWord.isEmpty()) verifiedWord = "MIC";

            isVoiceInputActive = true;
            activeTracingWord = verifiedWord;
            currentlyDetectedWord = verifiedWord;
            tvLiveText.setText(verifiedWord);
            drawingView.setTracingWord(verifiedWord);

            if (isTtsReady) {
                CharSequence spokenTarget = getNormalizedTtsText(verifiedWord);
                CharSequence utterance = android.text.TextUtils.concat("Let's trace ", spokenTarget);
                textToSpeech.speak(utterance, TextToSpeech.QUEUE_FLUSH, null, "VOICE_TRACE_ID");
            }
        });
    }

    private void showCustomVoiceDialog() {
        if (speechRecognizer == null) return;
        if (voiceDialog != null && voiceDialog.isShowing()) return;
        rawVoiceOutputBuffer = "";
        isVoiceInputActive = false;

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_voice_assist, null);
        tvVoiceStatus = dialogView.findViewById(R.id.tvVoiceStatus);
        MaterialButton btnCancelVoice = dialogView.findViewById(R.id.btnCancelVoice);
        MaterialButton btnProceedVoice = dialogView.findViewById(R.id.btnProceedVoice);
        MaterialButton btnRetryVoice = dialogView.findViewById(R.id.btnRetryVoice);

        voiceDialog = new AlertDialog.Builder(this)
                .setCancelable(false)
                .setView(dialogView)
                .create();
        if (voiceDialog.getWindow() != null) {
            voiceDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        btnCancelVoice.setOnClickListener(v -> {
            SoundManager.getInstance(this).playClick();
            // Clear pending proceed requests if canceled
            if (autoProceedRunnable != null) {
                scanHandler.removeCallbacks(autoProceedRunnable);
            }
            speechRecognizer.cancel();
            voiceDialog.dismiss();
        });

        btnProceedVoice.setOnClickListener(v -> {
            SoundManager.getInstance(this).playClick();
            executeVoiceProceed(rawVoiceOutputBuffer);
        });

        btnRetryVoice.setOnClickListener(v -> {
            SoundManager.getInstance(this).playClick();

            // ==========================================
            // FIX: Kill the pending auto-proceed timer
            // from the previous speech attempt!
            // ==========================================
            if (autoProceedRunnable != null) {
                scanHandler.removeCallbacks(autoProceedRunnable);
            }

            speechRecognizer.cancel();
            rawVoiceOutputBuffer = "";

            tvVoiceStatus.setTextColor(Color.parseColor("#000000"));
            tvVoiceStatus.setText("Listening again...");

            speechRecognizer.startListening(speechIntent);
        });

        voiceDialog.show();
        tvVoiceStatus.setText("Listening...");

        speechRecognizer.startListening(speechIntent);
    }

    private void speakTextDirectly(String textToSpeak) {
        CharSequence optimizedString = getNormalizedTtsText(textToSpeak);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            textToSpeech.setAudioAttributes(audioAttributes);
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);

            textToSpeech.speak(optimizedString, TextToSpeech.QUEUE_FLUSH, params, "TTS_DIRECT_ID");
        } else {
            textToSpeech.speak(optimizedString, TextToSpeech.QUEUE_FLUSH, null, "TTS_DIRECT_ID");
        }
    }

    private void checkWordDatabase(String word) {
        databaseExecutor.execute(() -> {
            String targetWord = word.toUpperCase().trim();

            if (isVoiceInputActive && !DICTIONARY.contains(targetWord)) {
                targetWord = findPhoneticMatch(targetWord);
            }

            final String processedWord = targetWord;
            String player = getSharedPreferences("LetterLandMemory", MODE_PRIVATE).getString("ACTIVE_PROFILE", "Default");
            WordEntry savedWord = AppDatabase.getInstance(this.getApplicationContext()).wordDao().findWordForProfile(processedWord, player);

            runOnUiThread(() -> {
                isProceedingLocked = false;
                if (isFinishing() || isDestroyed()) return;
                if (savedWord != null) {
                    Intent intent = new Intent(WriteActivity.this, WordDetailActivity.class);
                    intent.putExtra("WORD_TEXT", savedWord.word);
                    intent.putExtra("IMAGE_PATH", savedWord.imagePath);
                    intent.putExtra("SOURCE_PAGE", "WRITE");
                    intent.putExtra("IS_NEW_WORD", false);
                    startActivity(intent);
                    resetCanvasAndText();
                } else {
                    showNewWordDialog(processedWord);
                }
            });
        });
    }

    private String findPhoneticMatch(String scannedWord) {
        if (scannedWord == null || scannedWord.isEmpty() || DICTIONARY.contains(scannedWord)) return scannedWord;
        String scannedSoundex = getSoundexCode(scannedWord);
        for (String dictionaryWord : DICTIONARY) {
            if (getSoundexCode(dictionaryWord).equals(scannedSoundex)) {
                return dictionaryWord;
            }
        }
        return scannedWord;
    }

    private String getSoundexCode(String s) {
        if (s == null || s.isEmpty()) return "0000";
        char[] x = s.toUpperCase().toCharArray();
        StringBuilder buffer = new StringBuilder();
        buffer.append(x[0]);
        for (int i = 1; i < x.length; i++) {
            switch (x[i]) {
                case 'B': case 'F': case 'P': case 'V': buffer.append('1');
                    break;
                case 'C': case 'G': case 'J': case 'K': case 'Q': case 'S': case 'X': case 'Z': buffer.append('2'); break;
                case 'D': case 'T': buffer.append('3'); break;
                case 'L': buffer.append('4'); break;
                case 'M': case 'N': buffer.append('5'); break;
                case 'R': buffer.append('6'); break;
            }
        }

        StringBuilder cleanBuffer = new StringBuilder();
        cleanBuffer.append(buffer.charAt(0));
        for (int i = 1; i < buffer.length(); i++) {
            if (buffer.charAt(i) != buffer.charAt(i - 1)) cleanBuffer.append(buffer.charAt(i));
        }

        while (cleanBuffer.length() < 4) cleanBuffer.append('0');
        return cleanBuffer.substring(0, 4);
    }

    private void showNewWordDialog(String wordToSave) {
        if (isFinishing() || isDestroyed()) return;
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_new_word, null);
        AlertDialog newWordDialog = new AlertDialog.Builder(this).setView(dialogView).create();
        if (newWordDialog.getWindow() != null) {
            newWordDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        TextView tvDetected = dialogView.findViewById(R.id.tvDetectedWord);
        tvDetected.setText(wordToSave);
        dialogView.findViewById(R.id.btnDialogCamera).setOnClickListener(v1 -> {
            SoundManager.getInstance(this).playShutter();
            pendingWord = wordToSave;
            takePictureLauncher.launch(null);
            newWordDialog.dismiss();
        });
        dialogView.findViewById(R.id.btnDialogLater).setOnClickListener(v1 -> {
            SoundManager.getInstance(this).playClick();
            newWordDialog.dismiss();
            resetCanvasAndText();
        });
        newWordDialog.setCancelable(false);
        newWordDialog.show();
    }

    private void resetCanvasAndText() {
        drawingView.resetFullCanvas();
        tvLiveText.setText("...");
        currentlyDetectedWord = "";
        lastSpokenLetter = "";
        lastSpokenWordLength = 0;
        isVoiceInputActive = false;
        isProceedingLocked = false;
        activeTracingWord = "";
    }

    @Override
    protected void onResume() {
        super.onResume();
        SoundManager.getInstance(this).startBackgroundMusic();
        isProceedingLocked = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        SoundManager.getInstance(this).pauseBackgroundMusic();
        SoundManager.getInstance(this).stopScratchSound();

        spamHandler.removeCallbacksAndMessages(null);

        // OPTIMIZATION FIX: Flush pending delayed handlers to remove explicit temporary window leaks
        if (autoProceedRunnable != null) {
            scanHandler.removeCallbacks(autoProceedRunnable);
        }

        if (speechRecognizer != null) {
            speechRecognizer.cancel();
        }
    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.shutdown();
        }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }

        if (recognizer != null) {
            recognizer.close();
        }
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
        }
        super.onDestroy();
    }

    private CharSequence getNormalizedTtsText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        String clean = text.trim();
        if (clean.length() == 1) {
            return mapIsolatedLetter(clean);
        }
        return clean.toLowerCase(Locale.US);
    }

    private CharSequence mapIsolatedLetter(String text) {
        if (text == null || text.trim().length() != 1) {
            return text;
        }
        String letter = text.trim().toUpperCase();

        android.text.SpannableString spannable = new android.text.SpannableString(letter);
        android.text.style.TtsSpan alphabetSpan = new android.text.style.TtsSpan.VerbatimBuilder(letter)
                .build();

        spannable.setSpan(alphabetSpan, 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }
}