package com.example.letterland;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;

public class SoundManager {
    private static SoundManager instance;
    private SoundPool soundPool;

    private int clickSoundId;
    private int shutterSoundId;

    private int scratchSoundId;
    private int scratchStreamId = 0;

    private MediaPlayer backgroundMusicPlayer;
    private boolean isSoundOn = true;
    // SEPARATION FIX: Dedicated flag for background music streams
    private boolean isMusicOn = true;

    private final float NORMAL_MUSIC_VOLUME = 0.2f;
    private final float DUCKED_MUSIC_VOLUME = 0.05f;

    // FIX: Single persistent UI main thread handler reference to prevent memory collection leaks
    private final Handler audioCleanupHandler = new Handler(Looper.getMainLooper());

    private SoundManager(Context context) {
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

    // GETTER: Expose music state to tracking activities
    public boolean isMusicOn() {
        return isMusicOn;
    }

    // SETTER: Turns music on/off globally without touching sound effects flags
    public void setMusicOn(boolean musicOn) {
        this.isMusicOn = musicOn;
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

    // FIX: Patched native OpenSL ES asset cache buildup leak via explicit post-playback sample unloads
    public void playPhonicAsset(Context context, int resId) {
        if (!isSoundOn || resId == 0) return;

        duckBackgroundMusic();

        int loadedSoundId = soundPool.load(context, resId, 1);
        soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
            if (status == 0) {
                pool.play(sampleId, 1.0f, 1.0f, 2, 0, 1.0f);

                audioCleanupHandler.postDelayed(() -> {
                    restoreBackgroundMusic();
                    // CRITICAL FIX: Drops sound asset mapping indexes out of the hardware pool to stop audio muting crashes
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
        // SEPARATION FIX: Check isMusicOn instead of isSoundOn
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
        // SEPARATION FIX: Check isMusicOn instead of isSoundOn
        if (isMusicOn && backgroundMusicPlayer != null && !isFinishingMusicPlayer()) {
            backgroundMusicPlayer.setVolume(DUCKED_MUSIC_VOLUME, DUCKED_MUSIC_VOLUME);
        }
    }

    public void restoreBackgroundMusic() {
        // SEPARATION FIX: Check isMusicOn instead of isSoundOn
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
        if (!isSoundOn) {
            pauseBackgroundMusic();
            stopScratchSound();
        } else {
            startBackgroundMusic();
        }
    }
}