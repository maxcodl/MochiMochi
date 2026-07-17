package com.kawai.mochi;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.facebook.common.internal.Supplier;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.imagepipeline.cache.MemoryCacheParams;
import com.facebook.imagepipeline.core.DefaultExecutorSupplier;
import com.facebook.imagepipeline.core.ImagePipelineConfig;

public class StickerApplication extends Application {

    private static final String PREFS_NAME = "mochi_prefs";

    private static StickerApplication instance;

    /**
     * Static accessor for the application Context. Used by background code
     * (e.g. StickerProcessor's thumbnail generation) that doesn't have a
     * Context passed in directly.
     */
    public static StickerApplication getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        final ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        // FORCE DOWNSAMPLING: This is required for animated WebP resizing to work
        ImagePipelineConfig config = ImagePipelineConfig.newBuilder(this)
                .setDownsampleEnabled(true)
                .setBitmapsConfig(android.graphics.Bitmap.Config.ARGB_8888)
                .setBitmapMemoryCacheParamsSupplier(() -> {
                    final int maxCacheSize = getMaxCacheSize(activityManager);
                    return new MemoryCacheParams(
                            maxCacheSize,
                            1024,
                            maxCacheSize / 4,
                            Integer.MAX_VALUE,
                            Integer.MAX_VALUE,
                            java.util.concurrent.TimeUnit.MINUTES.toMillis(5)
                    );
                })
                .setDiskCacheEnabled(true)
                .setExecutorSupplier(new DefaultExecutorSupplier(Runtime.getRuntime().availableProcessors()))
                .build();
        Fresco.initialize(this, config);

        new Thread(() -> {
            try {
                Context appCtx = getApplicationContext();
                WastickerParser.seedBundledPacksIfNeeded(appCtx);
                WastickerParser.fixAnimatedPackFlagsIfNeeded(appCtx);
                // ⭐ Generate thumbnails for all existing packs (one-time)
                WastickerParser.generateMissingThumbnails(appCtx);
            } catch (Exception e) {
                android.util.Log.e("StickerApplication", "Initialization failed", e);
            }
        }, "AppInit").start();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int themeMode = prefs.getInt("theme_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(themeMode);
    }

    private int getMaxCacheSize(ActivityManager activityManager) {
        final int maxMemory = Math.min(activityManager.getMemoryClass() * 1024 * 1024, Integer.MAX_VALUE);
        if (maxMemory < 32 * 1024 * 1024) {
            return 4 * 1024 * 1024;
        } else if (maxMemory < 64 * 1024 * 1024) {
            return 8 * 1024 * 1024;
        } else {
            return maxMemory / 4;
        }
    }
}