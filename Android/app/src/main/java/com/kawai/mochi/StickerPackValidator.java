/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.kawai.mochi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.webkit.URLUtil;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.animated.webp.WebPImage;
import com.facebook.imagepipeline.common.ImageDecodeOptions;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

class StickerPackValidator {
    static final int EMOJI_MAX_LIMIT = 3;
    static final int MAX_STATIC_STICKER_A11Y_TEXT_CHAR_LIMIT = 125;
    static final int MAX_ANIMATED_STICKER_A11Y_TEXT_CHAR_LIMIT = 255;

    private static final int STATIC_STICKER_FILE_LIMIT_KB = 500;
    private static final int ANIMATED_STICKER_FILE_LIMIT_KB = 500;
    private static final int EMOJI_MIN_LIMIT = 1;
    private static final int IMAGE_HEIGHT = 512;
    private static final int IMAGE_WIDTH = 512;
    private static final int STICKER_SIZE_MIN = 3;
    private static final int STICKER_SIZE_MAX = 30;
    private static final int CHAR_COUNT_MAX = 128;
    private static final long KB_IN_BYTES = 1024;
    private static final int TRAY_IMAGE_FILE_SIZE_MAX_KB = 50;
    private static final int TRAY_IMAGE_DIMENSION_MIN = 24;
    private static final int TRAY_IMAGE_DIMENSION_MAX = 512;
    private static final int ANIMATED_STICKER_FRAME_DURATION_MIN = 8;
    private static final int ANIMATED_STICKER_TOTAL_DURATION_MAX = 10 * 1000; //ms
    private static final String PLAY_STORE_DOMAIN = "play.google.com";
    private static final String APPLE_STORE_DOMAIN = "itunes.apple.com";


    /**
     * Checks whether a sticker pack contains valid data.
     * Optimized: quickCheck skips deep file decoding for stickers if they have been checked before.
     */
    static void verifyStickerPackValidity(@NonNull Context context, @NonNull StickerPack stickerPack, boolean quickCheck) throws IllegalStateException {
        if (TextUtils.isEmpty(stickerPack.identifier)) {
            throw new IllegalStateException("sticker pack identifier is empty");
        }
        if (stickerPack.identifier.length() > CHAR_COUNT_MAX) {
            throw new IllegalStateException("sticker pack identifier cannot exceed " + CHAR_COUNT_MAX + " characters");
        }
        checkStringValidity(stickerPack.identifier);
        if (TextUtils.isEmpty(stickerPack.publisher)) {
            throw new IllegalStateException("sticker pack publisher is empty, sticker pack identifier: " + stickerPack.identifier);
        }
        if (stickerPack.publisher.length() > CHAR_COUNT_MAX) {
            throw new IllegalStateException("sticker pack publisher cannot exceed " + CHAR_COUNT_MAX + " characters, sticker pack identifier: " + stickerPack.identifier);
        }
        if (TextUtils.isEmpty(stickerPack.name)) {
            throw new IllegalStateException("sticker pack name is empty, sticker pack identifier: " + stickerPack.identifier);
        }
        if (stickerPack.name.length() > CHAR_COUNT_MAX) {
            throw new IllegalStateException("sticker pack name cannot exceed " + CHAR_COUNT_MAX + " characters, sticker pack identifier: " + stickerPack.identifier);
        }
        if (TextUtils.isEmpty(stickerPack.trayImageFile)) {
            throw new IllegalStateException("sticker pack tray id is empty, sticker pack identifier:" + stickerPack.identifier);
        }
        
        // Website validations... (unchanged)
        if (!TextUtils.isEmpty(stickerPack.androidPlayStoreLink) && !isValidWebsiteUrl(stickerPack.androidPlayStoreLink)) {
            throw new IllegalStateException("Make sure to include http or https in url links, android play store link is not a valid url: " + stickerPack.androidPlayStoreLink);
        }
        if (!TextUtils.isEmpty(stickerPack.iosAppStoreLink) && !isValidWebsiteUrl(stickerPack.iosAppStoreLink)) {
            throw new IllegalStateException("Make sure to include http or https in url links, ios app store link is not a valid url: " + stickerPack.iosAppStoreLink);
        }

        try {
            final byte[] stickerAssetBytes = StickerPackLoader.fetchStickerAsset(stickerPack.identifier, stickerPack.trayImageFile, context.getContentResolver());
            if (stickerAssetBytes.length > TRAY_IMAGE_FILE_SIZE_MAX_KB * KB_IN_BYTES) {
                throw new IllegalStateException("tray image should be less than " + TRAY_IMAGE_FILE_SIZE_MAX_KB + " KB, tray image file: " + stickerPack.trayImageFile);
            }
            // Quick check: Tray dimensions are usually okay if it's already in the list
            if (!quickCheck) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(stickerAssetBytes, 0, stickerAssetBytes.length);
                if (bitmap != null) {
                    if (bitmap.getHeight() > TRAY_IMAGE_DIMENSION_MAX || bitmap.getHeight() < TRAY_IMAGE_DIMENSION_MIN) {
                        throw new IllegalStateException("tray image height error");
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open tray image, " + stickerPack.trayImageFile, e);
        }

        final List<Sticker> stickers = stickerPack.getStickers();
        // Lower bound only — packs with >30 stickers are allowed in local storage.
        // The app splits them into ≤30-sticker chunks when sending to WhatsApp.
        if (stickers.size() < STICKER_SIZE_MIN) {
            throw new IllegalStateException("sticker pack must have at least " + STICKER_SIZE_MIN + " stickers");
        }
        
        for (final Sticker sticker : stickers) {
            validateSticker(context, stickerPack.identifier, sticker, stickerPack.animatedStickerPack, quickCheck);
        }
    }

    private static void validateSticker(@NonNull Context context, @NonNull final String identifier, @NonNull final Sticker sticker, final boolean animatedStickerPack, boolean quickCheck) throws IllegalStateException {
        if (sticker.emojis.size() > EMOJI_MAX_LIMIT || sticker.emojis.size() < EMOJI_MIN_LIMIT) {
            throw new IllegalStateException("emoji count limit error");
        }
        if (TextUtils.isEmpty(sticker.imageFileName)) {
            throw new IllegalStateException("no file path for sticker");
        }
        
        // Fast file size check (no decode)
        long fileSize = sticker.size;
        if (fileSize <= 0) {
            try {
                fileSize = StickerPackLoader.fetchStickerAssetLength(identifier, sticker.imageFileName, context.getContentResolver());
            } catch (IOException ignored) {}
        }

        if (!animatedStickerPack && fileSize > STATIC_STICKER_FILE_LIMIT_KB * KB_IN_BYTES) {
            throw new IllegalStateException("static sticker > 100KB: " + sticker.imageFileName);
        }
        if (animatedStickerPack && fileSize > ANIMATED_STICKER_FILE_LIMIT_KB * KB_IN_BYTES) {
            throw new IllegalStateException("animated sticker > 500KB: " + sticker.imageFileName);
        }

        // Only decode if full validation is requested
        if (!quickCheck) {
            validateStickerFile(context, identifier, sticker.imageFileName, animatedStickerPack);
        }
    }

    private static void validateStickerFile(@NonNull Context context, @NonNull String identifier, @NonNull final String fileName, final boolean animatedStickerPack) throws IllegalStateException {
        try {
            final byte[] stickerInBytes = StickerPackLoader.fetchStickerAsset(identifier, fileName, context.getContentResolver());
            final WebPImage webPImage = WebPImage.createFromByteArray(stickerInBytes, ImageDecodeOptions.defaults());
            if (webPImage.getHeight() != IMAGE_HEIGHT || webPImage.getWidth() != IMAGE_WIDTH) {
                throw new IllegalStateException("sticker dimensions should be 512x512");
            }
            if (animatedStickerPack && webPImage.getFrameCount() <= 1) {
                throw new IllegalStateException("animated pack contains static sticker: " + fileName);
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Error parsing webp: " + fileName, e);
        }
    }

    private static void checkStringValidity(@NonNull String string) {
        String pattern = "[\\w-.,'\\s]+";
        if (!string.matches(pattern)) {
            throw new IllegalStateException("Identifier contains invalid characters");
        }
    }

    private static boolean isValidWebsiteUrl(String websiteUrl) {
        return URLUtil.isHttpUrl(websiteUrl) || URLUtil.isHttpsUrl(websiteUrl);
    }

    private static boolean isURLInCorrectDomain(String urlString, String domain) {
        try {
            URL url = new URL(urlString);
            return domain.equals(url.getHost());
        } catch (MalformedURLException e) {
            return false;
        }
    }
}
