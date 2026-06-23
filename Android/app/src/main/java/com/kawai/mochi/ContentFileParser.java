/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.kawai.mochi;

import android.text.TextUtils;
import android.util.JsonReader;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

class ContentFileParser {
    private static final String TAG = "ContentFileParser";
    private static final String FIELD_STICKER_IMAGE_FILE = "image_file";
    private static final String FIELD_STICKER_EMOJIS = "emojis";
    private static final String FIELD_STICKER_ACCESSIBILITY_TEXT = "accessibility_text";

    @NonNull
    static List<StickerPack> parseStickerPacks(@NonNull InputStream contentsInputStream) throws IOException {
        try (JsonReader reader = new JsonReader(new InputStreamReader(contentsInputStream))) {
            return readStickerPacks(reader);
        }
    }

    @NonNull
    private static List<StickerPack> readStickerPacks(@NonNull JsonReader reader) throws IOException {
        List<StickerPack> stickerPackList = new ArrayList<>();
        String androidPlayStoreLink = null;
        String iosAppStoreLink = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            if ("android_play_store_link".equals(key)) {
                androidPlayStoreLink = reader.nextString();
            } else if ("ios_app_store_link".equals(key)) {
                iosAppStoreLink = reader.nextString();
            } else if ("sticker_packs".equals(key)) {
                reader.beginArray();
                while (reader.hasNext()) {
                    StickerPack stickerPack = readStickerPack(reader);
                    if (stickerPack != null) {
                        stickerPackList.add(stickerPack);
                    }
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        
        for (StickerPack stickerPack : stickerPackList) {
            stickerPack.setAndroidPlayStoreLink(androidPlayStoreLink);
            stickerPack.setIosAppStoreLink(iosAppStoreLink);
        }
        return stickerPackList;
    }

    @Nullable
    private static StickerPack readStickerPack(@NonNull JsonReader reader) throws IOException {
        reader.beginObject();
        String identifier = null;
        String name = null;
        String publisher = null;
        String trayImageFile = null;
        String publisherEmail = null;
        String publisherWebsite = null;
        String privacyPolicyWebsite = null;
        String licenseAgreementWebsite = null;
        String imageDataVersion = "";
        boolean avoidCache = false;
        boolean animatedStickerPack = false;
        List<Sticker> stickerList = null;
        while (reader.hasNext()) {
            String key = reader.nextName();
            switch (key) {
                case "identifier": identifier = reader.nextString(); break;
                case "name": name = reader.nextString(); break;
                case "publisher": publisher = reader.nextString(); break;
                case "tray_image_file": trayImageFile = reader.nextString(); break;
                case "publisher_email": publisherEmail = reader.nextString(); break;
                case "publisher_website": publisherWebsite = reader.nextString(); break;
                case "privacy_policy_website": privacyPolicyWebsite = reader.nextString(); break;
                case "license_agreement_website": licenseAgreementWebsite = reader.nextString(); break;
                case "stickers": stickerList = readStickers(reader); break;
                case "image_data_version": imageDataVersion = reader.nextString(); break;
                case "avoid_cache": avoidCache = reader.nextBoolean(); break;
                case "animated_sticker_pack": animatedStickerPack = reader.nextBoolean(); break;
                default: reader.skipValue();
            }
        }
        reader.endObject();

        if (TextUtils.isEmpty(identifier) || TextUtils.isEmpty(name) || TextUtils.isEmpty(publisher) || TextUtils.isEmpty(trayImageFile)) {
            Log.e(TAG, "Identifier, name, publisher, or tray_image_file is empty for " + identifier);
            return null;
        }
        if (stickerList == null || stickerList.isEmpty()) {
            Log.e(TAG, "Sticker list is empty for " + identifier);
            return null;
        }
        if (identifier.contains("..") || identifier.contains("/")) {
            Log.e(TAG, "Identifier contains invalid characters: " + identifier);
            return null;
        }
        if (TextUtils.isEmpty(imageDataVersion)) {
            imageDataVersion = "1";
        }
        final StickerPack stickerPack = new StickerPack(identifier, name, publisher, trayImageFile, publisherEmail, publisherWebsite, privacyPolicyWebsite, licenseAgreementWebsite, imageDataVersion, avoidCache, animatedStickerPack);
        stickerPack.setStickers(stickerList);
        return stickerPack;
    }

    @NonNull
    private static List<Sticker> readStickers(@NonNull JsonReader reader) throws IOException {
        reader.beginArray();
        List<Sticker> stickerList = new ArrayList<>();
        while (reader.hasNext()) {
            reader.beginObject();
            String imageFile = null;
            String accessibilityText = null;
            List<String> emojis = new ArrayList<>();
            while (reader.hasNext()) {
                final String key = reader.nextName();
                if (FIELD_STICKER_IMAGE_FILE.equals(key)) {
                    imageFile = reader.nextString();
                } else if (FIELD_STICKER_EMOJIS.equals(key)) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        String emoji = reader.nextString();
                        if (!TextUtils.isEmpty(emoji)) {
                            emojis.add(emoji);
                        }
                    }
                    reader.endArray();
                } else if (FIELD_STICKER_ACCESSIBILITY_TEXT.equals(key)) {
                    accessibilityText = reader.nextString();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (imageFile != null && !TextUtils.isEmpty(imageFile) && imageFile.endsWith(".webp") && !imageFile.contains("..") && !imageFile.contains("/")) {
                stickerList.add(new Sticker(imageFile, emojis, accessibilityText));
            }
        }
        reader.endArray();
        return stickerList;
    }
}
