/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.kawai.mochi;

import android.app.Application;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.imagepipeline.core.DefaultExecutorSupplier;
import com.facebook.imagepipeline.core.ImagePipelineConfig;

public class StickerApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        
        // Configure Fresco for better performance with animated stickers
        ImagePipelineConfig config = ImagePipelineConfig.newBuilder(this)
                .setDownsampleEnabled(true) // Crucial for performance: scales images at decode time
                .setDiskCacheEnabled(true)
                .setExecutorSupplier(new DefaultExecutorSupplier(1)) // Limit concurrent decode threads
                .build();
        Fresco.initialize(this, config);

        // Apply the saved night mode globally (light / dark / system).
        // DynamicColors (Monet) is applied per-activity in BaseActivity.onCreate()
        // AFTER the per-activity theme is set, so AMOLED gets the right surfaces.
        android.content.SharedPreferences prefs = getSharedPreferences("mochi_prefs", MODE_PRIVATE);
        int themeMode = prefs.getInt("theme_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(themeMode);
    }
}
