package com.kawai.mochii;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class WastickerParser {
    private static final String TAG = "WastickerParser";
    private static final String PREFS_NAME = "mochii_prefs";
    private static final String KEY_STICKER_FOLDER = "sticker_folder_path";

    /**
     * Import a .wasticker file from the given URI.
     * Unzips the file, parses contents.json inside, copies webp files to app's files dir,
     * and merges into the master contents.json.
     */
    public static String importStickerPack(Context context, Uri uri) throws IOException, JSONException {
        File stickerDir = new File(getStickerFolderPath(context));
        if (!stickerDir.exists()) stickerDir.mkdirs();

        // Create a temp dir for extraction
        File tempDir = new File(context.getCacheDir(), "wasticker_import_" + System.currentTimeMillis());
        tempDir.mkdirs();

        try {
            // Unzip the .wasticker file
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream == null) throw new IOException("Cannot open URI: " + uri);
            unzip(inputStream, tempDir);
            inputStream.close();

            // Parse contents.json from the zip OR handle bot-generated zip
            File contentsFile = new File(tempDir, "contents.json");
            JSONArray packsArray = new JSONArray();
            
            if (contentsFile.exists()) {
                String contentsJson = readStringFromFile(contentsFile);
                JSONObject root = new JSONObject(contentsJson);
                packsArray = root.optJSONArray("sticker_packs");
                if (packsArray == null || packsArray.length() == 0) {
                    throw new IOException("No sticker packs found in contents.json");
                }
            } else {
                // Fallback for bot-generated .wasticker (has title.txt, author.txt, tray.png, emojis.json)
                File titleFile = new File(tempDir, "title.txt");
                if (!titleFile.exists()) {
                    throw new IOException("Invalid sticker pack: missing both contents.json and title.txt");
                }
                
                String title = readStringFromFile(titleFile).trim();
                String author = "Bot";
                File authorFile = new File(tempDir, "author.txt");
                if (authorFile.exists()) {
                    author = readStringFromFile(authorFile).trim();
                }
                
                JSONObject emojisMap = new JSONObject();
                File emojisFile = new File(tempDir, "emojis.json");
                if (emojisFile.exists()) {
                    try {
                        emojisMap = new JSONObject(readStringFromFile(emojisFile));
                    } catch (Exception e) {
                        AppLogger.log(TAG, "Failed to parse emojis.json: " + e.getMessage());
                    }
                }
                
                JSONObject botPack = new JSONObject();
                String newId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                botPack.put("identifier", newId);
                botPack.put("name", title.isEmpty() ? "Imported Pack" : title);
                botPack.put("publisher", author.isEmpty() ? "Me" : author);
                botPack.put("tray_image_file", "tray.png");
                botPack.put("publisher_email", "");
                botPack.put("publisher_website", "");
                botPack.put("privacy_policy_website", "");
                botPack.put("license_agreement_website", "");
                botPack.put("image_data_version", "1");
                botPack.put("avoid_cache", false);
                // Detect animation from the actual WebP files
                File[] tempFiles = tempDir.listFiles();
                boolean detectedAnimated = false;
                if (tempFiles != null) {
                    for (File f : tempFiles) {
                        String fLower = f.getName().toLowerCase();
                        if (fLower.endsWith(".webp") && !f.getName().equals("tray.webp") && isAnimatedWebPFile(f)) {
                            detectedAnimated = true;
                            break;
                        }
                    }
                }
                botPack.put("animated_sticker_pack", detectedAnimated);
                
                JSONArray stickers = new JSONArray();
                File[] files = tempDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().toLowerCase().endsWith(".webp") && !f.getName().equals("tray.webp")) {
                            JSONObject stickerJson = new JSONObject();
                            stickerJson.put("image_file", f.getName());
                            JSONArray emojiArray = emojisMap.optJSONArray(f.getName());
                            if (emojiArray == null || emojiArray.length() == 0) {
                                emojiArray = new JSONArray().put("\uD83D\uDE00");
                            }
                            stickerJson.put("emojis", emojiArray);
                            stickers.put(stickerJson);
                        }
                    }
                }
                botPack.put("stickers", stickers);
                packsArray.put(botPack);
            }

            String firstPackIdentifier = null;

            // Read existing master contents.json, seeding from bundled asset if first run
            File masterContentsFile = new File(stickerDir, "contents.json");
            JSONObject masterRoot;
            JSONArray masterPacks;
            if (masterContentsFile.exists()) {
                masterRoot = new JSONObject(readStringFromFile(masterContentsFile));
                masterPacks = masterRoot.optJSONArray("sticker_packs");
                if (masterPacks == null) masterPacks = new JSONArray();
            } else {
                // First import — seed with any bundled packs from assets so they aren't lost
                masterRoot = new JSONObject();
                masterRoot.put("android_play_store_link", "");
                masterRoot.put("ios_app_store_link", "");
                masterPacks = new JSONArray();
                try (java.io.InputStream assetStream = context.getAssets().open("contents.json")) {
                    JSONObject bundled = new JSONObject(readAllBytesAsString(assetStream));
                    JSONArray bundledPacks = bundled.optJSONArray("sticker_packs");
                    if (bundledPacks != null) {
                        for (int i = 0; i < bundledPacks.length(); i++) {
                            masterPacks.put(bundledPacks.getJSONObject(i));
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "No bundled contents.json to seed from: " + e.getMessage());
                }
            }


            for (int p = 0; p < packsArray.length(); p++) {
                JSONObject packJson = packsArray.getJSONObject(p);
                String identifier = packJson.optString("identifier", UUID.randomUUID().toString());
                
                // Try to avoid importing duplicates by name if generated by bot
                boolean isDuplicate = false;
                for (int i = 0; i < masterPacks.length(); i++) {
                    JSONObject mp = masterPacks.getJSONObject(i);
                    if (mp.optString("identifier").equals(identifier) || 
                       (mp.optString("name").equals(packJson.optString("name")) && mp.optString("publisher").equals(packJson.optString("publisher")))) {
                        isDuplicate = true;
                        break;
                    }
                }
                if (isDuplicate) {
                    AppLogger.log(TAG, "Skipping duplicate pack: " + packJson.optString("name"));
                    if (firstPackIdentifier == null) firstPackIdentifier = identifier;
                    continue;
                }

                if (firstPackIdentifier == null) firstPackIdentifier = identifier;

                // Create pack directory
                File packDir = new File(stickerDir, identifier);
                packDir.mkdirs();

                // Copy tray image
                String trayImageFile = packJson.optString("tray_image_file", "tray.png");
                File traySource = new File(tempDir, trayImageFile);
                if (traySource.exists()) {
                    copyFile(traySource, new File(packDir, trayImageFile));
                } else if (!trayImageFile.isEmpty()) {
                    // Try fallback extensions
                    File trayFallback = new File(tempDir, "tray.webp");
                    if (trayFallback.exists()) {
                        copyFile(trayFallback, new File(packDir, "tray.webp"));
                        packJson.put("tray_image_file", "tray.webp");
                    }
                }

                // Copy sticker files
                JSONArray stickers = packJson.optJSONArray("stickers");
                if (stickers != null) {
                    for (int s = 0; s < stickers.length(); s++) {
                        JSONObject stickerJson = stickers.getJSONObject(s);
                        String imageFile = stickerJson.optString("image_file", "");
                        if (!imageFile.isEmpty()) {
                            File stickerSource = new File(tempDir, imageFile);
                            if (stickerSource.exists()) {
                                copyFile(stickerSource, new File(packDir, imageFile));
                            }
                        }
                    }
                }

                // Add pack to master
                masterPacks.put(packJson);
            }

            // Write updated master contents.json
            masterRoot.put("sticker_packs", masterPacks);
            writeStringToFile(masterContentsFile, masterRoot.toString(2));

            AppLogger.log(TAG, "Successfully imported sticker pack(s)");
            return firstPackIdentifier;

        } finally {
            deleteRecursive(tempDir);
        }
    }

    /**
     * Import a single sticker from a .wst URI.
     *
     * .wst can be either:
     *   - A raw WebP file (renamed to .wst)
     *   - A ZIP containing one .webp + optional metadata.json
     *     (keys: "emojis": [...], "title": "...", "author": "...")
     *
     * The sticker is added to {@code targetPackId} if supplied and found,
     * otherwise to the first user pack with fewer than 30 stickers,
     * otherwise to a freshly created pack.
     *
     * @return null on success, error message on failure.
     */
    public static String importSingleSticker(Context context, Uri uri, String targetPackId)
            throws IOException, JSONException {

        // --- Read the .wst file ---
        byte[] webpBytes = null;
        List<String> emojis = new ArrayList<>();
        String title = null;
        String author = null;

        InputStream firstStream = context.getContentResolver().openInputStream(uri);
        if (firstStream == null) return "Cannot open URI: " + uri;

        // Peek first 4 bytes to detect ZIP (PK\x03\x04)
        byte[] peek = new byte[4];
        int peekRead = firstStream.read(peek);
        firstStream.close();

        boolean isZip = peekRead >= 2 && peek[0] == 0x50 && peek[1] == 0x4B;

        if (isZip) {
            try (InputStream is = context.getContentResolver().openInputStream(uri);
                 ZipInputStream zis = new ZipInputStream(is)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    int slash = name.lastIndexOf('/');
                    if (slash >= 0) name = name.substring(slash + 1);

                    if (name.endsWith(".webp") && webpBytes == null) {
                        webpBytes = readAllBytes(zis);
                    } else if (name.equals("metadata.json")) {
                        JSONObject meta = new JSONObject(new String(readAllBytes(zis), "UTF-8"));
                        if (meta.has("emojis")) {
                            JSONArray arr = meta.getJSONArray("emojis");
                            for (int i = 0; i < arr.length(); i++) emojis.add(arr.getString(i));
                        }
                        if (meta.has("title")) title = meta.getString("title");
                        if (meta.has("author")) author = meta.getString("author");
                    }
                    zis.closeEntry();
                }
            }
        } else {
            // Raw bytes: prepend the 4 peeked bytes
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) return "Cannot open URI: " + uri;
                byte[] rest = readAllBytes(is);
                webpBytes = new byte[peekRead + rest.length];
                System.arraycopy(peek, 0, webpBytes, 0, peekRead);
                System.arraycopy(rest, 0, webpBytes, peekRead, rest.length);
            }
        }

        if (webpBytes == null || webpBytes.length == 0) {
            return "No sticker image found in .wst file";
        }
        if (emojis.isEmpty()) emojis.add("\uD83D\uDE00");

        // --- Find or create target pack ---
        File stickerDir = new File(getStickerFolderPath(context));
        stickerDir.mkdirs();

        File masterContentsFile = new File(stickerDir, "contents.json");
        JSONObject masterRoot;
        JSONArray masterPacks;
        if (masterContentsFile.exists()) {
            masterRoot = new JSONObject(readStringFromFile(masterContentsFile));
            masterPacks = masterRoot.optJSONArray("sticker_packs");
            if (masterPacks == null) masterPacks = new JSONArray();
        } else {
            masterRoot = new JSONObject();
            masterRoot.put("android_play_store_link", "");
            masterRoot.put("ios_app_store_link", "");
            masterPacks = new JSONArray();
        }

        JSONObject targetPack = null;

        // 1. Try exact match
        if (targetPackId != null) {
            for (int i = 0; i < masterPacks.length(); i++) {
                JSONObject p = masterPacks.getJSONObject(i);
                if (targetPackId.equals(p.optString("identifier"))) {
                    targetPack = p;
                    break;
                }
            }
        }
        // 2. First user pack with < 30 stickers
        if (targetPack == null) {
            for (int i = 0; i < masterPacks.length(); i++) {
                JSONObject p = masterPacks.getJSONObject(i);
                JSONArray s = p.optJSONArray("stickers");
                if (s != null && s.length() < 30) {
                    targetPack = p;
                    break;
                }
            }
        }
        // 3. Create new pack
        if (targetPack == null) {
            String newId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            targetPack = new JSONObject();
            targetPack.put("identifier", newId);
            targetPack.put("name", title != null ? title : "Imported Stickers");
            targetPack.put("publisher", author != null ? author : "Me");
            targetPack.put("tray_image_file", "tray.webp");
            targetPack.put("publisher_email", "");
            targetPack.put("publisher_website", "");
            targetPack.put("privacy_policy_website", "");
            targetPack.put("license_agreement_website", "");
            targetPack.put("image_data_version", "1");
            targetPack.put("avoid_cache", false);
            targetPack.put("animated_sticker_pack", isAnimatedWebPBytes(webpBytes));
            targetPack.put("stickers", new JSONArray());
            masterPacks.put(targetPack);
        }

        String packId = targetPack.getString("identifier");
        File packDir = new File(stickerDir, packId);
        packDir.mkdirs();

        // Write WebP sticker file
        String fileName = "sticker_" + System.currentTimeMillis() + ".webp";
        FileOutputStream stickerFos = new FileOutputStream(new File(packDir, fileName));
        stickerFos.write(webpBytes);
        stickerFos.close();

        // Auto-generate tray icon from first sticker if absent
        File trayFile = new File(packDir, targetPack.optString("tray_image_file", "tray.webp"));
        if (!trayFile.exists()) {
            FileOutputStream trayFos = new FileOutputStream(trayFile);
            trayFos.write(webpBytes);
            trayFos.close();
        }

        // Add sticker entry to pack JSON
        JSONObject stickerEntry = new JSONObject();
        stickerEntry.put("image_file", fileName);
        JSONArray emojiArray = new JSONArray();
        for (String e : emojis) emojiArray.put(e);
        stickerEntry.put("emojis", emojiArray);
        stickerEntry.put("accessibility_text", "");

        JSONArray stickers = targetPack.optJSONArray("stickers");
        if (stickers == null) {
            stickers = new JSONArray();
            targetPack.put("stickers", stickers);
        }
        stickers.put(stickerEntry);

        // Save master contents.json
        masterRoot.put("sticker_packs", masterPacks);
        writeStringToFile(masterContentsFile, masterRoot.toString(2));

        AppLogger.log(TAG, "Imported single sticker to pack: " + packId);
        return null; // success
    }

    /** Returns true if the given WebP bytes represent an animated WebP (VP8X with animation flag). */
    private static boolean isAnimatedWebPBytes(byte[] data) {
        if (data == null || data.length < 21) return false;
        if (data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F') return false;
        if (data[8] != 'W' || data[9] != 'E' || data[10] != 'B' || data[11] != 'P') return false;
        if (data[12] == 'V' && data[13] == 'P' && data[14] == '8' && data[15] == 'X') {
            return (data[20] & 0x02) != 0;
        }
        return false;
    }

    /** Returns true if the given WebP File is animated. */
    private static boolean isAnimatedWebPFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[21];
            int read = fis.read(header);
            return read >= 21 && isAnimatedWebPBytes(header);
        } catch (Exception e) {
            return false;
        }
    }

    /** Read all bytes from an InputStream without closing it. */
    private static byte[] readAllBytes(InputStream is) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        while ((n = is.read(buffer)) != -1) bos.write(buffer, 0, n);
        return bos.toByteArray();
    }

    /**
     * Delete a sticker pack by identifier — removes pack folder and updates contents.json.
     */
    public static void deleteStickerPack(Context context, String identifier) throws IOException, JSONException {
        File stickerDir = new File(getStickerFolderPath(context));
        File masterContentsFile = new File(stickerDir, "contents.json");

        if (!masterContentsFile.exists()) return;

        JSONObject masterRoot = new JSONObject(readStringFromFile(masterContentsFile));
        JSONArray masterPacks = masterRoot.optJSONArray("sticker_packs");
        if (masterPacks == null) return;

        JSONArray updatedPacks = new JSONArray();
        for (int i = 0; i < masterPacks.length(); i++) {
            JSONObject pack = masterPacks.getJSONObject(i);
            if (!pack.optString("identifier").equals(identifier)) {
                updatedPacks.put(pack);
            }
        }

        masterRoot.put("sticker_packs", updatedPacks);
        writeStringToFile(masterContentsFile, masterRoot.toString(2));

        // Delete the pack folder
        File packDir = new File(stickerDir, identifier);
        if (packDir.exists()) {
            deleteRecursive(packDir);
        }

        AppLogger.log(TAG, "Deleted sticker pack: " + identifier);
    }

    /**
     * Add a single WebP sticker to an existing pack.
     */
    public static void addWebpStickerToPack(Context context, String identifier, File webpFile) throws IOException, JSONException {
        File stickerDir = new File(getStickerFolderPath(context));
        File masterContentsFile = new File(stickerDir, "contents.json");
        if (!masterContentsFile.exists()) throw new IOException("No contents.json found");

        JSONObject masterRoot = new JSONObject(readStringFromFile(masterContentsFile));
        JSONArray masterPacks = masterRoot.optJSONArray("sticker_packs");
        if (masterPacks == null) throw new IOException("No sticker packs found");

        for (int i = 0; i < masterPacks.length(); i++) {
            JSONObject pack = masterPacks.getJSONObject(i);
            if (pack.optString("identifier").equals(identifier)) {
                File packDir = new File(stickerDir, identifier);
                packDir.mkdirs();

                // Copy the webp file
                String fileName = webpFile.getName();
                copyFile(webpFile, new File(packDir, fileName));

                // Add to stickers array
                JSONArray stickers = pack.optJSONArray("stickers");
                if (stickers == null) stickers = new JSONArray();
                JSONObject stickerJson = new JSONObject();
                stickerJson.put("image_file", fileName);
                stickerJson.put("emojis", new JSONArray().put("\uD83D\uDE00"));
                stickers.put(stickerJson);
                pack.put("stickers", stickers);
                break;
            }
        }

        masterRoot.put("sticker_packs", masterPacks);
        writeStringToFile(masterContentsFile, masterRoot.toString(2));
    }

    /**
     * Create a new pack with a single sticker.
     */
    public static String createPackWithSticker(Context context, String title, String author, File webpFile) throws IOException, JSONException {
        File stickerDir = new File(getStickerFolderPath(context));
        if (!stickerDir.exists()) stickerDir.mkdirs();

        String identifier = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        File packDir = new File(stickerDir, identifier);
        packDir.mkdirs();

        // Copy webp as sticker
        String fileName = webpFile.getName();
        File destSticker = new File(packDir, fileName);
        copyFile(webpFile, destSticker);

        // Use the sticker as tray icon too (will be a larger image, but functional)
        String trayFileName = "tray_" + fileName;
        copyFile(webpFile, new File(packDir, trayFileName));

        // Build pack JSON
        JSONObject packJson = new JSONObject();
        packJson.put("identifier", identifier);
        packJson.put("name", title);
        packJson.put("publisher", author);
        packJson.put("tray_image_file", trayFileName);
        packJson.put("publisher_email", "");
        packJson.put("publisher_website", "");
        packJson.put("privacy_policy_website", "");
        packJson.put("license_agreement_website", "");
        packJson.put("image_data_version", "1");
        packJson.put("avoid_cache", false);
        packJson.put("animated_sticker_pack", isAnimatedWebPFile(webpFile));

        JSONArray stickers = new JSONArray();
        JSONObject stickerJson = new JSONObject();
        stickerJson.put("image_file", fileName);
        stickerJson.put("emojis", new JSONArray().put("\uD83D\uDE00"));
        stickers.put(stickerJson);
        packJson.put("stickers", stickers);

        // Merge into master contents.json
        File masterContentsFile = new File(stickerDir, "contents.json");
        JSONObject masterRoot;
        JSONArray masterPacks;
        if (masterContentsFile.exists()) {
            masterRoot = new JSONObject(readStringFromFile(masterContentsFile));
            masterPacks = masterRoot.optJSONArray("sticker_packs");
            if (masterPacks == null) masterPacks = new JSONArray();
        } else {
            masterRoot = new JSONObject();
            masterRoot.put("android_play_store_link", "");
            masterRoot.put("ios_app_store_link", "");
            masterPacks = new JSONArray();
        }

        masterPacks.put(packJson);
        masterRoot.put("sticker_packs", masterPacks);
        writeStringToFile(masterContentsFile, masterRoot.toString(2));

        AppLogger.log(TAG, "Created new pack: " + identifier + " with sticker: " + fileName);
        return identifier;
    }

    /**
     * Full save of an edited or new pack.
     */
    public static void savePack(Context context, String packName, String author, String identifier,
                                 List<EditStickerAdapter.StickerItem> stickerItems, Uri trayUri) throws IOException, JSONException {
        File stickerDir = new File(getStickerFolderPath(context));
        File packDir = new File(stickerDir, identifier);
        packDir.mkdirs();

        // Handle tray icon
        String trayFileName = "tray.webp";
        if (trayUri != null) {
            File trayFile = new File(packDir, trayFileName);
            copyUriToFile(context, trayUri, trayFile);
        }

        // Build stickers array
        JSONArray stickersArray = new JSONArray();
        for (int i = 0; i < stickerItems.size(); i++) {
            EditStickerAdapter.StickerItem item = stickerItems.get(i);
            String fileName;
            if (item.newUri != null) {
                // New sticker — copy from URI
                fileName = "sticker_" + i + ".webp";
                File destFile = new File(packDir, fileName);
                copyUriToFile(context, item.newUri, destFile);
            } else {
                fileName = item.fileName;
            }

            JSONObject stickerJson = new JSONObject();
            stickerJson.put("image_file", fileName);
            JSONArray emojis = new JSONArray();
            if (item.emojis != null) {
                for (String emoji : item.emojis) {
                    emojis.put(emoji);
                }
            }
            if (emojis.length() == 0) emojis.put("\uD83D\uDE00");
            stickerJson.put("emojis", emojis);
            stickersArray.put(stickerJson);
        }

        // Update master contents.json
        File masterContentsFile = new File(stickerDir, "contents.json");
        JSONObject masterRoot;
        JSONArray masterPacks;
        if (masterContentsFile.exists()) {
            masterRoot = new JSONObject(readStringFromFile(masterContentsFile));
            masterPacks = masterRoot.optJSONArray("sticker_packs");
            if (masterPacks == null) masterPacks = new JSONArray();
        } else {
            masterRoot = new JSONObject();
            masterRoot.put("android_play_store_link", "");
            masterRoot.put("ios_app_store_link", "");
            masterPacks = new JSONArray();
        }

        // Find existing pack or create new one
        boolean found = false;
        for (int i = 0; i < masterPacks.length(); i++) {
            JSONObject pack = masterPacks.getJSONObject(i);
            if (pack.optString("identifier").equals(identifier)) {
                pack.put("name", packName);
                pack.put("publisher", author);
                pack.put("tray_image_file", trayFileName);
                pack.put("stickers", stickersArray);
                found = true;
                break;
            }
        }
        if (!found) {
            // Detect animated flag from any sticker already written to packDir
            boolean isAnimated = false;
            File[] writtenFiles = packDir.listFiles();
            if (writtenFiles != null) {
                for (File f : writtenFiles) {
                    if (f.getName().toLowerCase().endsWith(".webp") && !f.getName().startsWith("tray") && isAnimatedWebPFile(f)) {
                        isAnimated = true;
                        break;
                    }
                }
            }
            JSONObject packJson = new JSONObject();
            packJson.put("identifier", identifier);
            packJson.put("name", packName);
            packJson.put("publisher", author);
            packJson.put("tray_image_file", trayFileName);
            packJson.put("publisher_email", "");
            packJson.put("publisher_website", "");
            packJson.put("privacy_policy_website", "");
            packJson.put("license_agreement_website", "");
            packJson.put("image_data_version", "1");
            packJson.put("avoid_cache", false);
            packJson.put("animated_sticker_pack", isAnimated);
            packJson.put("stickers", stickersArray);
            masterPacks.put(packJson);
        }

        masterRoot.put("sticker_packs", masterPacks);
        writeStringToFile(masterContentsFile, masterRoot.toString(2));
        AppLogger.log(TAG, "Saved pack: " + identifier);
    }

    /**
     * Check if a WebP file is animated by reading the RIFF header.
     */
    public static boolean isAnimatedWebPPublic(Context context, String identifier, String filename) {
        try {
            File stickerDir = new File(getStickerFolderPath(context));
            File file = new File(new File(stickerDir, identifier), filename);
            if (!file.exists()) return false;
            FileInputStream fis = new FileInputStream(file);
            byte[] header = new byte[20];
            int read = fis.read(header);
            fis.close();
            if (read < 16) return false;
            // Check for RIFF header
            if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') return false;
            // Check for WEBP
            if (header[8] != 'W' || header[9] != 'E' || header[10] != 'B' || header[11] != 'P') return false;
            // Check for VP8X (extended) with animation flag
            if (header[12] == 'V' && header[13] == 'P' && header[14] == '8' && header[15] == 'X') {
                if (read >= 21) {
                    return (header[20] & 0x02) != 0; // Animation flag
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Shrink a WebP file to fit WhatsApp's size limits.
     */
    public static void shrinkWebP(File file) {
        // WebP compression requires native libraries — for now just log a warning
        long size = file.length();
        if (size > 500 * 1024) {
            AppLogger.log(TAG, "WARNING: " + file.getName() + " exceeds 500KB limit (" + size + " bytes)");
        }
    }

    /**
     * Get the sticker folder path. Returns custom path from SharedPrefs or default filesDir.
     */
    public static String getStickerFolderPath(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String customPath = prefs.getString(KEY_STICKER_FOLDER, null);
        if (customPath != null && !customPath.isEmpty()) {
            return customPath;
        }
        return context.getFilesDir().getAbsolutePath();
    }

    public static void setStickerFolderPath(Context context, String path) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (path == null || path.isEmpty()) {
            prefs.edit().remove(KEY_STICKER_FOLDER).apply();
        } else {
            prefs.edit().putString(KEY_STICKER_FOLDER, path).apply();
        }
    }

    // ---- Utility methods ----

    public static String readStringFromFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    private static String readAllBytesAsString(InputStream is) throws IOException {
        byte[] bytes = readAllBytes(is);
        return new String(bytes, "UTF-8");
    }

    private static void writeStringToFile(File file, String content) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(content.getBytes("UTF-8"));
        fos.close();
    }

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buffer = new byte[8192];
        int len;
        while ((len = fis.read(buffer)) > 0) {
            fos.write(buffer, 0, len);
        }
        fos.close();
        fis.close();
    }

    private static void copyUriToFile(Context context, Uri uri, File dst) throws IOException {
        InputStream is = context.getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("Cannot open URI: " + uri);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) > 0) {
            fos.write(buffer, 0, len);
        }
        fos.close();
        is.close();
    }

    private static void unzip(InputStream is, File destDir) throws IOException {
        ZipInputStream zis = new ZipInputStream(is);
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            File file = new File(destDir, entry.getName());
            if (entry.isDirectory()) {
                file.mkdirs();
            } else {
                file.getParentFile().mkdirs();
                BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file));
                byte[] buffer = new byte[8192];
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    bos.write(buffer, 0, len);
                }
                bos.close();
            }
            zis.closeEntry();
        }
        zis.close();
    }

    private static void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDir.delete();
    }
}
