package com.example.letterland;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;

public class SoundManager {
    private static SoundManager instance;
    private SoundPool soundPool;
    private SharedPreferences audioPrefs;

    private int clickSoundId;
    private int shutterSoundId;

    private int scratchSoundId;
    private int scratchStreamId = 0;

    private MediaPlayer backgroundMusicPlayer;
    private boolean isSoundOn = true;
    private boolean isMusicOn = true;

    private final float NORMAL_MUSIC_VOLUME = 0.2f;
    private final float DUCKED_MUSIC_VOLUME = 0.05f;

    private final Handler audioCleanupHandler = new Handler(Looper.getMainLooper());

    private SoundManager(Context context) {
        // Core persistent storage initialization
        audioPrefs = context.getSharedPreferences("LetterLandMemory", Context.MODE_PRIVATE);
        this.isMusicOn = audioPrefs.getBoolean("PREF_MUSIC_ON", true);
        this.isSoundOn = audioPrefs.getBoolean("PREF_SOUND_ON", true);

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        soundPool = new SoundPool.Builder()
                .setMaxStreams(5)
                .setAudioAttributes(audioAttributes)
                .build();

        clickSoundId = soundPool.load(context, R.raw.button_pop, 1);
        shutterSoundId = soundPool.load(context, R.raw.shutter, 1);

        try {
            scratchSoundId = soundPool.load(context, R.raw.scratch, 1);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            backgroundMusicPlayer = MediaPlayer.create(context.getApplicationContext(), R.raw.game_music);
            if (backgroundMusicPlayer != null) {
                backgroundMusicPlayer.setLooping(true);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static synchronized SoundManager getInstance(Context context) {
        if (instance == null) {
            instance = new SoundManager(context.getApplicationContext());
        }
        return instance;
    }

    public boolean isMusicOn() {
        return isMusicOn;
    }

    public void setMusicOn(boolean musicOn) {
        this.isMusicOn = musicOn;
        // Save state explicitly to disk
        audioPrefs.edit().putBoolean("PREF_MUSIC_ON", musicOn).apply();
        if (!musicOn) {
            pauseBackgroundMusic();
        }
    }

    public void playClick() {
        if (isSoundOn) {
            soundPool.play(clickSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
        }
    }

    public void playShutter() {
        if (isSoundOn) {
            soundPool.play(shutterSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
        }
    }

    public void playPhonicAsset(Context context, int resId) {
        if (!isSoundOn || resId == 0) return;

        duckBackgroundMusic();

        int loadedSoundId = soundPool.load(context, resId, 1);
        soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
            if (status == 0) {
                pool.play(sampleId, 1.0f, 1.0f, 2, 0, 1.0f);

                audioCleanupHandler.postDelayed(() -> {
                    restoreBackgroundMusic();
                    soundPool.unload(sampleId);
                }, 1200);
            } else {
                restoreBackgroundMusic();
            }
        });
    }

    public void startScratchSound() {
        if (isSoundOn && scratchStreamId == 0) {
            scratchStreamId = soundPool.play(scratchSoundId, 0.6f, 0.6f, 1, -1, 1.0f);
        }
    }

    public void stopScratchSound() {
        if (scratchStreamId != 0) {
            soundPool.stop(scratchStreamId);
            scratchStreamId = 0;
        }
    }

    public void startBackgroundMusic() {
        if (isMusicOn && backgroundMusicPlayer != null && !backgroundMusicPlayer.isPlaying()) {
            backgroundMusicPlayer.setVolume(NORMAL_MUSIC_VOLUME, NORMAL_MUSIC_VOLUME);
            backgroundMusicPlayer.start();
        }
    }

    public void pauseBackgroundMusic() {
        if (backgroundMusicPlayer != null && backgroundMusicPlayer.isPlaying()) {
            backgroundMusicPlayer.pause();
        }
    }

    public void duckBackgroundMusic() {
        if (isMusicOn && backgroundMusicPlayer != null && !isFinishingMusicPlayer()) {
            backgroundMusicPlayer.setVolume(DUCKED_MUSIC_VOLUME, DUCKED_MUSIC_VOLUME);
        }
    }

    public void restoreBackgroundMusic() {
        if (isMusicOn && backgroundMusicPlayer != null && !isFinishingMusicPlayer()) {
            backgroundMusicPlayer.setVolume(NORMAL_MUSIC_VOLUME, NORMAL_MUSIC_VOLUME);
        }
    }

    private boolean isFinishingMusicPlayer() {
        try {
            return !backgroundMusicPlayer.isPlaying() && backgroundMusicPlayer.getCurrentPosition() == 0;
        } catch (Exception e) {
            return true;
        }
    }

    public void toggleSound(boolean soundOn) {
        isSoundOn = soundOn;
        audioPrefs.edit().putBoolean("PREF_SOUND_ON", soundOn).apply();
        if (!isSoundOn) {
            pauseBackgroundMusic();
            stopScratchSound();
        } else {
            startBackgroundMusic();
        }
    }
}