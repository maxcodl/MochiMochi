/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.kawai.mochi;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Abstract activity that handles the logic of adding sticker packs to WhatsApp.
 * 
 * Performance & Bug Fixes:
 * 1. Validation is now offloaded to a background thread to prevent UI hangs.
 * 2. Uses the modern ActivityResultLauncher which handles focus transitions better.
 * 3. Handles the "WhatsApp transparent overlay" bug (where WA sheet collapses but 
 *    activity remains active/blocking) by tracking focus state and restoring 
 *    interactivity.
 */
public abstract class AddStickerPackActivity extends BaseActivity {
    private static final String TAG = "AddStickerPackActivity";
    
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Intent> addStickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_CANCELED && result.getData() != null) {
                    final String validationError = result.getData().getStringExtra("validation_error");
                    if (validationError != null) {
                        Log.e(TAG, "WhatsApp validation failed: " + validationError);
                        MessageDialogFragment.newInstance(R.string.title_validation_error,
                                "WhatsApp reported an error:\n" + validationError)
                                .show(getSupportFragmentManager(), "whatsapp validation error");
                    }
                }
                restoreStatusBarAppearance();
            }
    );

    protected void addStickerPackToWhatsApp(String identifier, String stickerPackName) {
        // 1. Check if WhatsApp is installed
        if (!WhitelistCheck.isWhatsAppConsumerAppInstalled(getPackageManager())
                && !WhitelistCheck.isWhatsAppSmbAppInstalled(getPackageManager())) {
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. Perform validation on a background thread
        executor.execute(() -> {
            try {
                ArrayList<StickerPack> packs = StickerPackLoader.fetchStickerPacks(this);
                StickerPack targetPack = null;
                for (StickerPack p : packs) {
                    if (p.identifier.equals(identifier)) {
                        targetPack = p;
                        break;
                    }
                }
                if (targetPack != null) {
                    StickerPackValidator.verifyStickerPackValidity(this, targetPack);
                }
                
                // If validation passes, proceed to launch on main thread
                mainHandler.post(() -> proceedWithLaunch(identifier, stickerPackName));
                
            } catch (Exception e) {
                Log.e(TAG, "Internal validation failed for pack: " + identifier, e);
                mainHandler.post(() -> {
                    MessageDialogFragment.newInstance(R.string.title_validation_error,
                            "Internal Check Failed:\n" + e.getMessage())
                            .show(getSupportFragmentManager(), "internal validation error");
                });
            }
        });
    }

    private void proceedWithLaunch(String identifier, String stickerPackName) {
        final boolean whitelistedConsumer = WhitelistCheck.isStickerPackWhitelistedInWhatsAppConsumer(this, identifier);
        final boolean whitelistedSmb     = WhitelistCheck.isStickerPackWhitelistedInWhatsAppSmb(this, identifier);

        if (!whitelistedConsumer && !whitelistedSmb) {
            launchIntentToAddPackToChooser(identifier, stickerPackName);
        } else if (!whitelistedConsumer) {
            launchIntentToAddPackToSpecificPackage(identifier, stickerPackName, WhitelistCheck.CONSUMER_WHATSAPP_PACKAGE_NAME);
        } else if (!whitelistedSmb) {
            launchIntentToAddPackToSpecificPackage(identifier, stickerPackName, WhitelistCheck.SMB_WHATSAPP_PACKAGE_NAME);
        }
    }

    /**
     * Primary unlock point.
     *
     * WhatsApp's import screen is a full-screen translucent Activity, so ALL touches
     * (including taps in the transparent area above the bottom card) go to WhatsApp's
     * window first — not ours. On some devices/versions, tapping outside collapses
     * the sheet but does NOT finish the WA activity, leaving a "ghost" transparent
     * layer blocking our app.
     *
     * onWindowFocusChanged(true) is reliably called when WA's window FINALLY 
     * releases focus back to ours (usually after a back gesture).
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            restoreStatusBarAppearance();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreStatusBarAppearance();
    }

    private void restoreStatusBarAppearance() {
        boolean isNight = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat wic =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (wic != null) {
            wic.setAppearanceLightStatusBars(!isNight);
        }
    }

    private void launchIntentToAddPackToSpecificPackage(String identifier, String stickerPackName, String whatsappPackageName) {
        Intent intent = createIntentToAddStickerPack(identifier, stickerPackName);
        intent.setPackage(whatsappPackageName);
        try {
            addStickerLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Couldn't open WhatsApp", e);
            Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchIntentToAddPackToChooser(String identifier, String stickerPackName) {
        Intent intent = createIntentToAddStickerPack(identifier, stickerPackName);
        try {
            addStickerLauncher.launch(Intent.createChooser(intent, getString(R.string.add_to_whatsapp)));
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Couldn't open WhatsApp chooser", e);
        }
    }

    @NonNull
    private Intent createIntentToAddStickerPack(String identifier, String stickerPackName) {
        Intent intent = new Intent();
        intent.setAction("com.whatsapp.intent.action.ENABLE_STICKER_PACK");
        intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_ID, identifier);
        intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_AUTHORITY, BuildConfig.CONTENT_PROVIDER_AUTHORITY);
        intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_NAME, stickerPackName);
        return intent;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
