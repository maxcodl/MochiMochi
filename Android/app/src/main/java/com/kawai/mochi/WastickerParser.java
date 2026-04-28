package com.kawai.mochi;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.content.res.AssetManager;

import androidx.documentfile.provider.DocumentFile;

import com.facebook.animated.webp.WebPImage;
import com.facebook.imagepipeline.common.ImageDecodeOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
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
import java.util.zip.ZipOutputStream;

public class WastickerParser {
    private static final String TAG = "WastickerParser";
    private static final String PREFS_NAME = "mochi_prefs";
    private static final String KEY_STICKER_FOLDER = "sticker_folder_path";
    private static final String KEY_BUNDLED_SEEDED_PATH = "bundled_seeded_path";
    private static final String CONTENTS_FILE_NAME = "contents.json";

    /**
     * First-run seed: copy bundled sticker packs from assets into the selected storage folder.
     * This keeps built-in packs available from storage (not direct asset reads).
     */
    public static synchronized void seedBundledPacksIfNeeded(Context context) {
        try {
            String rootPath = getStickerFolderPath(context);
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String seededPath = prefs.getString(KEY_BUNDLED_SEEDED_PATH, null);

            // Fast no-op: already seeded for this exact storage path.
            if (rootPath.equals(seededPath)) {
                return;
            }

            if (isCustomPathUri(context)) {
                seedBundledToSafIfNeeded(context, rootPath);
            } else {
                seedBundledToInternalIfNeeded(context, rootPath);
            }
            prefs.edit().putString(KEY_BUNDLED_SEEDED_PATH, rootPath).apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to seed bundled packs", e);
        }
    }

    private static void seedBundledToInternalIfNeeded(Context context, String rootPath) throws IOException, IllegalStateException {
        File rootDir = new File(rootPath);
        if (!rootDir.exists()) {
            rootDir.mkdirs();
        }

        File contentsFile = new File(rootDir, CONTENTS_FILE_NAME);
        if (contentsFile.exists()) {
            return;
        }

        List<StickerPack> bundledPacks;
        try (InputStream is = context.getAssets().open(CONTENTS_FILE_NAME)) {
            bundledPacks = ContentFileParser.parseStickerPacks(is);
        }

        // Copy contents.json first.
        copyAssetFileToInternal(context.getAssets(), CONTENTS_FILE_NAME, contentsFile);

        // Copy tray + sticker assets for each bundled pack.
        for (StickerPack pack : bundledPacks) {
            File packDir = new File(rootDir, pack.identifier);
            if (!packDir.exists()) {
                packDir.mkdirs();
            }

            if (pack.trayImageFile != null && !pack.trayImageFile.isEmpty()) {
                copyAssetFileToInternal(context.getAssets(), pack.identifier + "/" + pack.trayImageFile,
                        new File(packDir, pack.trayImageFile));
            }

            if (pack.getStickers() != null) {
                for (Sticker sticker : pack.getStickers()) {
                    if (sticker.imageFileName == null || sticker.imageFileName.isEmpty()) continue;
                    copyAssetFileToInternal(context.getAssets(), pack.identifier + "/" + sticker.imageFileName,
                            new File(packDir, sticker.imageFileName));
                }
            }
        }
    }

    private static void seedBundledToSafIfNeeded(Context context, String rootPath) throws IOException, IllegalStateException {
        DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(rootPath));
        if (root == null) {
            return;
        }

        DocumentFile contentsFile = root.findFile(CONTENTS_FILE_NAME);
        if (contentsFile != null) {
            return;
        }

        List<StickerPack> bundledPacks;
        try (InputStream is = context.getAssets().open(CONTENTS_FILE_NAME)) {
            bundledPacks = ContentFileParser.parseStickerPacks(is);
        }

        DocumentFile createdContents = root.createFile("application/json", CONTENTS_FILE_NAME);
        if (createdContents != null) {
            copyAssetFileToUri(context, CONTENTS_FILE_NAME, createdContents.getUri());
        }

        for (StickerPack pack : bundledPacks) {
            DocumentFile packDir = root.findFile(pack.identifier);
            if (packDir == null) {
                packDir = root.createDirectory(pack.identifier);
            }
            if (packDir == null) {
                continue;
            }

            if (pack.trayImageFile != null && !pack.trayImageFile.isEmpty()) {
                DocumentFile trayFile = packDir.findFile(pack.trayImageFile);
                if (trayFile == null) {
                    trayFile = packDir.createFile("image/*", pack.trayImageFile);
                }
                if (trayFile != null) {
                    copyAssetFileToUri(context, pack.identifier + "/" + pack.trayImageFile, trayFile.getUri());
                }
            }

            if (pack.getStickers() != null) {
                for (Sticker sticker : pack.getStickers()) {
                    if (sticker.imageFileName == null || sticker.imageFileName.isEmpty()) continue;
                    DocumentFile stickerFile = packDir.findFile(sticker.imageFileName);
                    if (stickerFile == null) {
                        stickerFile = packDir.createFile("image/*", sticker.imageFileName);
                    }
                    if (stickerFile != null) {
                        copyAssetFileToUri(context, pack.identifier + "/" + sticker.imageFileName, stickerFile.getUri());
                    }
                }
            }
        }
    }

    private static void copyAssetFileToInternal(AssetManager assetManager, String assetPath, File destFile) throws IOException {
        if (destFile.exists()) {
            return;
        }
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (InputStream is = assetManager.open(assetPath);
             OutputStream os = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
        }
    }

    private static void copyAssetFileToUri(Context context, String assetPath, Uri destUri) throws IOException {
        try (InputStream is = context.getAssets().open(assetPath);
             OutputStream os = context.getContentResolver().openOutputStream(destUri)) {
            if (os == null) {
                throw new IOException("Cannot open output URI: " + destUri);
            }
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
        }
    }

    public static String importStickerPack(Context context, Uri uri) throws IOException, JSONException {
        File tempDir = new File(context.getCacheDir(), "wasticker_import_" + System.currentTimeMillis());
        tempDir.mkdirs();

        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream == null) throw new IOException("Cannot open URI: " + uri);
            unzip(inputStream, tempDir);
            inputStream.close();

            File contentsFile = new File(tempDir, "contents.json");
            JSONArray packsArray = new JSONArray();
            
            if (contentsFile.exists()) {
                String contentsJson = readStringFromFile(contentsFile);
                JSONObject root = new JSONObject(contentsJson);
                packsArray = root.optJSONArray("sticker_packs");
            } else {
                File titleFile = new File(tempDir, "title.txt");
                if (!titleFile.exists()) throw new IOException("Invalid sticker pack: missing title.txt and contents.json");
                
                String title = readStringFromFile(titleFile).trim();
                String author = "Bot";
                File authorFile = new File(tempDir, "author.txt");
                if (authorFile.exists()) author = readStringFromFile(authorFile).trim();
                
                JSONObject botPack = new JSONObject();
                String newId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                botPack.put("identifier", newId);
                botPack.put("name", title.isEmpty() ? "Imported Pack" : title);
                botPack.put("publisher", author);
                botPack.put("tray_image_file", "tray.png");
                botPack.put("image_data_version", "1");
                botPack.put("stickers", new JSONArray());
                packsArray.put(botPack);
            }

            String firstPackIdentifier = null;
            JSONObject masterRoot = getOrSeedMasterRoot(context);
            JSONArray masterPacks = masterRoot.optJSONArray("sticker_packs");

            // Pre-fetch the SAF root once for the entire import — avoids re-opening it per sticker.
            final boolean isSAF = isCustomPathUri(context);
            final DocumentFile safRoot = isSAF
                    ? DocumentFile.fromTreeUri(context, Uri.parse(getStickerFolderPath(context)))
                    : null;

            for (int p = 0; p < packsArray.length(); p++) {
                JSONObject packJson = packsArray.getJSONObject(p);
                String identifier = packJson.optString("identifier", UUID.randomUUID().toString());
                if (firstPackIdentifier == null) firstPackIdentifier = identifier;

                // Create pack directory (internal path or SAF)
                ensureDirectory(context, identifier, safRoot);

                // Pre-fetch the pack DocumentFile once per pack — avoids one findFile() IPC per sticker.
                DocumentFile packDirDoc = (safRoot != null) ? safRoot.findFile(identifier) : null;

                // Copy tray and stickers
                String trayImageFile = packJson.optString("tray_image_file", "tray.png");
                File traySource = new File(tempDir, trayImageFile);
                if (traySource.exists()) {
                    copyToPackFolder(context, traySource, identifier, trayImageFile, packDirDoc);
                }

                JSONArray stickers = packJson.optJSONArray("stickers");
                if (stickers != null) {
                    for (int s = 0; s < stickers.length(); s++) {
                        String imageFile = stickers.getJSONObject(s).optString("image_file", "");
                        File src = new File(tempDir, imageFile);
                        if (src.exists()) {
                            copyToPackFolder(context, src, identifier, imageFile, packDirDoc);
                        }
                    }
                }
                
                // Ensure version exists before merging
                if (!packJson.has("image_data_version")) {
                    packJson.put("image_data_version", "1");
                }
                masterPacks.put(packJson);
            }

            saveMasterContents(context, masterRoot);
            return firstPackIdentifier;

        } finally {
            deleteRecursive(tempDir);
        }
    }

    private static void ensureDirectory(Context context, String packId) throws IOException {
        ensureDirectory(context, packId, null);
    }

    private static void ensureDirectory(Context context, String packId, DocumentFile preloadedRoot) throws IOException {
        String rootPath = getStickerFolderPath(context);
        if (isCustomPathUri(context)) {
            DocumentFile root = preloadedRoot != null ? preloadedRoot
                    : DocumentFile.fromTreeUri(context, Uri.parse(rootPath));
            if (root == null) throw new IOException("Cannot access custom folder");
            DocumentFile packDir = root.findFile(packId);
            if (packDir == null) root.createDirectory(packId);
        } else {
            File packDir = new File(new File(rootPath), packId);
            if (!packDir.exists()) packDir.mkdirs();
        }
    }

    private static void copyToPackFolder(Context context, File src, String packId, String fileName) throws IOException {
        copyToPackFolder(context, src, packId, fileName, null);
    }

    /**
     * Copies {@code src} into the sticker pack folder, optionally accepting a pre-fetched
     * {@code packDirDoc} to avoid redundant SAF IPC calls when copying many files into the same pack.
     */
    private static void copyToPackFolder(Context context, File src, String packId, String fileName,
                                         DocumentFile packDirDoc) throws IOException {
        String rootPath = getStickerFolderPath(context);
        if (isCustomPathUri(context)) {
            // Use the pre-fetched pack dir when available; fall back to a fresh lookup otherwise.
            DocumentFile packDir = packDirDoc;
            if (packDir == null) {
                DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(rootPath));
                packDir = root != null ? root.findFile(packId) : null;
            }
            if (packDir == null) throw new IOException("Pack directory not found: " + packId);
            
            DocumentFile destFile = packDir.findFile(fileName);
            if (destFile == null) destFile = packDir.createFile("image/*", fileName);
            if (destFile == null) throw new IOException("Could not create file: " + fileName);
            
            try (InputStream is = new FileInputStream(src);
                 OutputStream os = context.getContentResolver().openOutputStream(destFile.getUri())) {
                byte[] buffer = new byte[8192]; int len;
                while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
            }
        } else {
            File dest = new File(new File(new File(rootPath), packId), fileName);
            copyFile(src, dest);
        }
    }

    private static JSONObject getOrSeedMasterRoot(Context context) throws IOException, JSONException {
        String rootPath = getStickerFolderPath(context);
        String json = null;
        if (isCustomPathUri(context)) {
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(rootPath));
            DocumentFile contents = root != null ? root.findFile("contents.json") : null;
            if (contents != null) {
                try (InputStream is = context.getContentResolver().openInputStream(contents.getUri())) {
                    json = readStringFromStream(is);
                }
            }
        } else {
            File masterContentsFile = new File(rootPath, "contents.json");
            if (masterContentsFile.exists()) json = readStringFromFile(masterContentsFile);
        }

        if (json != null) return new JSONObject(json);
        
        JSONObject masterRoot = new JSONObject();
        masterRoot.put("sticker_packs", new JSONArray());
        return masterRoot;
    }

    private static void saveMasterContents(Context context, JSONObject root) throws IOException {
        String rootPath = getStickerFolderPath(context);
        String content;
        try {
            content = root.toString(2);
        } catch (JSONException e) {
            content = root.toString();
        }
        if (isCustomPathUri(context)) {
            DocumentFile rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(rootPath));
            if (rootDoc == null) throw new IOException("Root folder inaccessible");
            DocumentFile contents = rootDoc.findFile("contents.json");
            if (contents == null) contents = rootDoc.createFile("application/json", "contents.json");
            if (contents == null) throw new IOException("Could not create contents.json");
            try (OutputStream os = context.getContentResolver().openOutputStream(contents.getUri())) {
                os.write(content.getBytes("UTF-8"));
            }
        } else {
            File masterContentsFile = new File(rootPath, "contents.json");
            writeStringToFile(masterContentsFile, content);
        }
    }

    public static String getStickerFolderPath(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String storedValue = prefs.getString(KEY_STICKER_FOLDER, null);
        if (storedValue == null || storedValue.isEmpty()) {
            File defaultDir = new File(context.getFilesDir(), "stickers");
            if (!defaultDir.exists()) defaultDir.mkdirs();
            return defaultDir.getAbsolutePath();
        }
        return storedValue;
    }

    public static String getDisplayablePath(Context context, String path) {
        if (path == null) return "Internal Storage";
        if (path.startsWith("content://")) {
            try {
                String decoded = Uri.decode(path);
                int lastColon = decoded.lastIndexOf(':');
                if (lastColon != -1) return decoded.substring(lastColon + 1);
                return Uri.parse(path).getLastPathSegment();
            } catch (Exception e) { return "External Folder"; }
        }
        String internalPrefix = context.getFilesDir().getAbsolutePath();
        if (path.startsWith(internalPrefix)) return "Internal Storage";
        return new File(path).getName();
    }

    public static boolean isCustomPathUri(Context context) {
        String path = getStickerFolderPath(context);
        return path != null && path.startsWith("content://");
    }

    public static void setStickerFolderPath(Context context, String path) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putString(KEY_STICKER_FOLDER, path).apply();
        // Force bundled seeding for the newly selected folder on next read.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_BUNDLED_SEEDED_PATH).apply();
        StickerContentProvider provider = StickerContentProvider.getInstance();
        if (provider != null) provider.invalidateStickerPackList();
    }

    public static String readStringFromFile(File file) throws IOException {
        try (InputStream is = new FileInputStream(file)) {
            return readStringFromStream(is);
        }
    }

    private static String readStringFromStream(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static void writeStringToFile(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes("UTF-8"));
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192]; int len;
            while ((len = fis.read(buffer)) > 0) fos.write(buffer, 0, len);
        }
    }

    private static byte[] readPackFileBytes(Context context, String identifier, String fileName, DocumentFile packDirDoc) throws IOException {
        if (fileName == null || fileName.isEmpty()) return null;

        if (isCustomPathUri(context)) {
            DocumentFile packDir = packDirDoc;
            if (packDir == null) {
                DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(getStickerFolderPath(context)));
                packDir = root != null ? root.findFile(identifier) : null;
            }
            if (packDir == null) return null;

            DocumentFile file = packDir.findFile(fileName);
            if (file == null) return null;
            try (InputStream is = context.getContentResolver().openInputStream(file.getUri())) {
                if (is == null) return null;
                return readAllBytes(is);
            }
        }

        File rootFile = new File(new File(getStickerFolderPath(context), identifier), fileName);
        if (!rootFile.exists()) return null;
        try (InputStream is = new FileInputStream(rootFile)) {
            return readAllBytes(is);
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) > 0) {
            bos.write(buffer, 0, len);
        }
        return bos.toByteArray();
    }

    private static void putZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static void unzip(InputStream is, File destDir) throws IOException {
        ZipInputStream zis = new ZipInputStream(is);
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            File file = new File(destDir, entry.getName());
            if (entry.isDirectory()) file.mkdirs();
            else {
                file.getParentFile().mkdirs();
                try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file))) {
                    byte[] buffer = new byte[8192]; int len;
                    while ((len = zis.read(buffer)) > 0) bos.write(buffer, 0, len);
                }
            }
            zis.closeEntry();
        }
        zis.close();
    }

    private static void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        fileOrDir.delete();
    }

    public static void deleteStickerPack(Context context, String identifier) throws IOException, JSONException {
        JSONObject masterRoot = getOrSeedMasterRoot(context);
        JSONArray masterPacks = masterRoot.optJSONArray("sticker_packs");
        if (masterPacks == null) return;

        JSONArray updatedPacks = new JSONArray();
        for (int i = 0; i < masterPacks.length(); i++) {
            JSONObject pack = masterPacks.getJSONObject(i);
            if (!pack.optString("identifier").equals(identifier)) updatedPacks.put(pack);
        }

        masterRoot.put("sticker_packs", updatedPacks);
        saveMasterContents(context, masterRoot);
        
        String rootPath = getStickerFolderPath(context);
        if (isCustomPathUri(context)) {
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(rootPath));
            DocumentFile packDir = root != null ? root.findFile(identifier) : null;
            if (packDir != null) packDir.delete();
        } else {
            deleteRecursive(new File(new File(rootPath), identifier));
        }
    }

    public static boolean isAnimatedWebPPublic(Context context, String identifier, String fileName) {
        try {
            byte[] bytes = StickerPackLoader.fetchStickerAsset(identifier, fileName, context.getContentResolver());
            if (bytes == null || bytes.length == 0) {
                android.util.Log.w(TAG, "isAnimatedWebPPublic: empty bytes for " + identifier + "/" + fileName);
                return false;
            }
            WebPImage webPImage = WebPImage.createFromByteArray(bytes, ImageDecodeOptions.defaults());
            int frameCount = webPImage.getFrameCount();
            android.util.Log.d(TAG, "isAnimatedWebPPublic: " + identifier + "/" + fileName + " frameCount=" + frameCount);
            return frameCount > 1;
        } catch (Exception e) {
            android.util.Log.e(TAG, "isAnimatedWebPPublic: error for " + identifier + "/" + fileName, e);
            return false;
        }
    }

    public static File exportStickerPackZip(Context context, String identifier) throws IOException, JSONException {
        JSONObject masterRoot = getOrSeedMasterRoot(context);
        JSONArray masterPacks = masterRoot.optJSONArray("sticker_packs");
        if (masterPacks == null) {
            throw new IOException("No sticker packs found");
        }

        JSONObject packJson = null;
        for (int i = 0; i < masterPacks.length(); i++) {
            JSONObject candidate = masterPacks.getJSONObject(i);
            if (identifier.equals(candidate.optString("identifier"))) {
                packJson = candidate;
                break;
            }
        }
        if (packJson == null) {
            throw new IOException("Sticker pack not found: " + identifier);
        }

        File exportDir = new File(context.getCacheDir(), "exports");
        if (!exportDir.exists()) exportDir.mkdirs();
        String safeName = identifier.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safeName.isEmpty()) safeName = "sticker_pack";
        File exportFile = File.createTempFile(safeName + "_", ".wasticker", exportDir);

        JSONObject exportRoot = new JSONObject();
        JSONArray exportPacks = new JSONArray();
        exportPacks.put(packJson);
        exportRoot.put("sticker_packs", exportPacks);

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(exportFile)))) {
            putZipEntry(zos, "contents.json", exportRoot.toString(2).getBytes("UTF-8"));

            String rootPath = getStickerFolderPath(context);
            DocumentFile rootDoc = isCustomPathUri(context) ? DocumentFile.fromTreeUri(context, Uri.parse(rootPath)) : null;
            DocumentFile packDirDoc = rootDoc != null ? rootDoc.findFile(identifier) : null;

            String trayFile = packJson.optString("tray_image_file", "tray.png");
            byte[] trayBytes = readPackFileBytes(context, identifier, trayFile, packDirDoc);
            if (trayBytes != null) {
                putZipEntry(zos, trayFile, trayBytes);
            }

            JSONArray stickers = packJson.optJSONArray("stickers");
            if (stickers != null) {
                for (int i = 0; i < stickers.length(); i++) {
                    JSONObject sticker = stickers.getJSONObject(i);
                    String fileName = sticker.optString("image_file", "");
                    if (fileName.isEmpty()) continue;
                    byte[] stickerBytes = readPackFileBytes(context, identifier, fileName, packDirDoc);
                    if (stickerBytes != null) {
                        putZipEntry(zos, fileName, stickerBytes);
                    }
                }
            }
        }

        return exportFile;
    }

    public static void savePack(Context context, String name, String author, String identifier,
                               List<EditStickerAdapter.StickerItem> items, Uri trayUri) throws IOException, JSONException {
        JSONObject masterRoot = getOrSeedMasterRoot(context);
        JSONArray masterPacks = masterRoot.optJSONArray("sticker_packs");
        if (masterPacks == null) {
            masterPacks = new JSONArray();
            masterRoot.put("sticker_packs", masterPacks);
        }

        JSONObject packJson = null;
        for (int i = 0; i < masterPacks.length(); i++) {
            if (masterPacks.getJSONObject(i).optString("identifier").equals(identifier)) {
                packJson = masterPacks.getJSONObject(i);
                break;
            }
        }

        if (packJson == null) {
            packJson = new JSONObject();
            packJson.put("identifier", identifier);
            packJson.put("image_data_version", "1");
            masterPacks.put(packJson);
        } else if (!packJson.has("image_data_version")) {
            packJson.put("image_data_version", "1");
        }

        packJson.put("name", name);
        packJson.put("publisher", author);
        if (!packJson.has("tray_image_file")) packJson.put("tray_image_file", "tray.png");

        ensureDirectory(context, identifier);

        // Pre-fetch the SAF pack directory once — avoids re-opening root + calling findFile()
        // inside every processAndSaveImage() call (one IPC per sticker on SAF).
        DocumentFile preloadedPackDir = null;
        if (isCustomPathUri(context)) {
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(getStickerFolderPath(context)));
            if (root != null) preloadedPackDir = root.findFile(identifier);
        }

        if (trayUri != null) {
            processAndSaveImage(context, trayUri, identifier, packJson.getString("tray_image_file"), true, preloadedPackDir);
        }

        JSONArray stickersArray = new JSONArray();
        for (int i = 0; i < items.size(); i++) {
            EditStickerAdapter.StickerItem item = items.get(i);
            String fileName = (i + 1) + ".webp";

            JSONObject stickerJson = new JSONObject();
            stickerJson.put("image_file", fileName);

            JSONArray emojis = new JSONArray();
            if (item.emojis != null && !item.emojis.isEmpty()) {
                for (String e : item.emojis) emojis.put(e);
            } else {
                emojis.put("\uD83D\uDE00");
            }
            stickerJson.put("emojis", emojis);
            stickersArray.put(stickerJson);

            if (item.newUri != null) {
                processAndSaveImage(context, item.newUri, identifier, fileName, false, preloadedPackDir);
            } else if (item.packIdentifier != null && item.fileName != null) {
                if (!item.packIdentifier.equals(identifier) || !item.fileName.equals(fileName)) {
                    copyWithinStorage(context, item.packIdentifier, item.fileName, identifier, fileName);
                }
            }
        }
        packJson.put("stickers", stickersArray);
        saveMasterContents(context, masterRoot);

        StickerContentProvider provider = StickerContentProvider.getInstance();
        if (provider != null) provider.invalidateStickerPackList();
    }

    private static void processAndSaveImage(Context context, Uri sourceUri, String packId, String fileName, boolean isTray) throws IOException {
        processAndSaveImage(context, sourceUri, packId, fileName, isTray, null);
    }

    /**
     * Processes and saves an image into the sticker pack folder, optionally accepting a
     * pre-fetched {@code packDirDoc} to avoid redundant SAF IPC calls during batch saves.
     */
    private static void processAndSaveImage(Context context, Uri sourceUri, String packId, String fileName,
                                            boolean isTray, DocumentFile packDirDoc) throws IOException {
        String rootPath = getStickerFolderPath(context);
        if (isCustomPathUri(context)) {
            // Use pre-fetched pack dir when available; fall back to a fresh lookup otherwise.
            DocumentFile packDir = packDirDoc;
            if (packDir == null) {
                DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(rootPath));
                packDir = root != null ? root.findFile(packId) : null;
            }
            if (packDir == null) throw new IOException("Pack directory not found");
            DocumentFile destFile = packDir.findFile(fileName);
            if (destFile == null) destFile = packDir.createFile(isTray ? "image/png" : "image/webp", fileName);
            if (destFile == null) throw new IOException("Could not create file: " + fileName);

            if (isTray) StickerProcessor.processTrayIcon(context, sourceUri, destFile.getUri());
            else StickerProcessor.processStaticSticker(context, sourceUri, destFile.getUri());
        } else {
            File packDir = new File(new File(rootPath), packId);
            File destFile = new File(packDir, fileName);
            if (isTray) StickerProcessor.processTrayIcon(context, sourceUri, destFile);
            else StickerProcessor.processStaticSticker(context, sourceUri, destFile);
        }
    }

    private static void copyWithinStorage(Context context, String srcPackId, String srcFileName, String dstPackId, String dstFileName) throws IOException {
        String rootPath = getStickerFolderPath(context);
        if (isCustomPathUri(context)) {
            // Open the SAF root once; avoid two separate fromTreeUri() calls.
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(rootPath));
            DocumentFile srcDir = root != null ? root.findFile(srcPackId) : null;
            DocumentFile dstDir = root != null ? root.findFile(dstPackId) : null;
            DocumentFile srcFile = srcDir != null ? srcDir.findFile(srcFileName) : null;
            if (srcFile == null || dstDir == null) return;

            DocumentFile dstFile = dstDir.findFile(dstFileName);
            if (dstFile == null) dstFile = dstDir.createFile("image/*", dstFileName);
            if (dstFile == null) return;

            try (InputStream is = context.getContentResolver().openInputStream(srcFile.getUri());
                 OutputStream os = context.getContentResolver().openOutputStream(dstFile.getUri())) {
                byte[] buffer = new byte[8192]; int len;
                while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
            }
        } else {
            File srcFile = new File(new File(new File(rootPath), srcPackId), srcFileName);
            File dstFile = new File(new File(new File(rootPath), dstPackId), dstFileName);
            if (srcFile.exists()) copyFile(srcFile, dstFile);
        }
    }

    public static String createPackWithSticker(Context context, String name, String author, File webpFile) throws IOException, JSONException {
        String identifier = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ensureDirectory(context, identifier);

        // Copy first sticker as tray icon and first sticker
        String rootPath = getStickerFolderPath(context);
        if (isCustomPathUri(context)) {
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(rootPath));
            DocumentFile packDir = root.findFile(identifier);
            DocumentFile trayFile = packDir.createFile("image/png", "tray.png");
            DocumentFile stickerFile = packDir.createFile("image/webp", "1.webp");
            StickerProcessor.processTrayIcon(context, Uri.fromFile(webpFile), trayFile.getUri());
            copyFileToUri(context, webpFile, stickerFile.getUri());
        } else {
            File packDir = new File(new File(rootPath), identifier);
            StickerProcessor.processTrayIcon(webpFile, new File(packDir, "tray.png"));
            copyFile(webpFile, new File(packDir, "1.webp"));
        }

        JSONObject masterRoot = getOrSeedMasterRoot(context);
        JSONArray masterPacks = masterRoot.optJSONArray("sticker_packs");
        JSONObject packJson = new JSONObject();
        packJson.put("identifier", identifier);
        packJson.put("name", name);
        packJson.put("publisher", author);
        packJson.put("tray_image_file", "tray.png");
        packJson.put("image_data_version", "1");
        JSONArray stickers = new JSONArray();
        JSONObject s1 = new JSONObject();
        s1.put("image_file", "1.webp");
        s1.put("emojis", new JSONArray().put("\uD83D\uDE00"));
        stickers.put(s1);
        packJson.put("stickers", stickers);
        masterPacks.put(packJson);
        saveMasterContents(context, masterRoot);
        return identifier;
    }

    public static void addWebpStickerToPack(Context context, String identifier, File webpFile) throws IOException, JSONException {
        JSONObject masterRoot = getOrSeedMasterRoot(context);
        JSONArray masterPacks = masterRoot.optJSONArray("sticker_packs");
        JSONObject packJson = null;
        for (int i = 0; i < masterPacks.length(); i++) {
            if (masterPacks.getJSONObject(i).optString("identifier").equals(identifier)) {
                packJson = masterPacks.getJSONObject(i);
                break;
            }
        }
        if (packJson == null) return;

        JSONArray stickers = packJson.getJSONArray("stickers");
        String fileName = (stickers.length() + 1) + ".webp";
        
        String rootPath = getStickerFolderPath(context);
        if (isCustomPathUri(context)) {
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(rootPath));
            DocumentFile packDir = root.findFile(identifier);
            DocumentFile stickerFile = packDir.createFile("image/webp", fileName);
            copyFileToUri(context, webpFile, stickerFile.getUri());
        } else {
            File packDir = new File(new File(rootPath), identifier);
            copyFile(webpFile, new File(packDir, fileName));
        }

        JSONObject s = new JSONObject();
        s.put("image_file", fileName);
        s.put("emojis", new JSONArray().put("\uD83D\uDE00"));
        stickers.put(s);
        
        // Ensure version exists during update
        if (!packJson.has("image_data_version")) {
            packJson.put("image_data_version", "1");
        }

        saveMasterContents(context, masterRoot);
    }

    private static void copyFileToUri(Context context, File src, Uri destUri) throws IOException {
        try (InputStream is = new FileInputStream(src);
             OutputStream os = context.getContentResolver().openOutputStream(destUri)) {
            byte[] buffer = new byte[8192]; int len;
            while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
        }
    }

    // -------------------------------------------------------------------------
    // Pack Merge
    // -------------------------------------------------------------------------

    /**
     * Creates a new sticker pack whose stickers are the union of {@code sourcePacks},
     * capped at {@code maxStickers} (WhatsApp allows at most 30).
     *
     * <p>The new pack takes the name and publisher of the first selected pack.
     * Stickers are added in source-pack order; once the cap is reached the remainder
     * are silently skipped (the caller already warned the user in the confirmation dialog).
     *
     * @param context     Application context.
     * @param sourcePacks Packs to merge (must be ≥ 2).
     * @param maxStickers Hard cap on total stickers in the merged pack (≤ 30).
     */
    public static void mergeStickerPacks(Context context,
                                         List<StickerPack> sourcePacks,
                                         int maxStickers) throws IOException, JSONException {
        if (sourcePacks == null || sourcePacks.size() < 2) {
            throw new IllegalArgumentException("Need at least 2 packs to merge");
        }
        // WhatsApp packs must be uniformly animated or uniformly static.
        boolean firstAnimated = sourcePacks.get(0).animatedStickerPack;
        for (StickerPack p : sourcePacks) {
            if (p.animatedStickerPack != firstAnimated) {
                throw new IllegalArgumentException(
                        "Cannot merge animated and static packs together");
            }
        }

        // New pack metadata derived from the first selected pack.
        String newId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        StickerPack firstPack = sourcePacks.get(0);
        String mergedName   = firstPack.name   != null ? firstPack.name : "Merged Pack";
        String mergedAuthor = firstPack.publisher != null ? firstPack.publisher : "Unknown";

        // Create the directory for the new pack.
        ensureDirectory(context, newId);

        final boolean isSAF = isCustomPathUri(context);
        final String rootPath = getStickerFolderPath(context);

        // Pre-fetch SAF root + new pack dir once — avoids per-sticker IPC.
        // NOTE: ensureDirectory already created the dir; use findFile first, then
        // createDirectory as fallback to handle any SAF DocumentFile cache lag.
        DocumentFile safRoot    = isSAF ? DocumentFile.fromTreeUri(context, Uri.parse(rootPath)) : null;
        DocumentFile newPackDir = null;
        if (safRoot != null) {
            newPackDir = safRoot.findFile(newId);
            if (newPackDir == null) newPackDir = safRoot.createDirectory(newId);
        }

        // Build the sticker JSON array while copying files.
        JSONArray stickersArray = new JSONArray();
        int stickerIndex = 1;

        outer:
        for (StickerPack sourcePack : sourcePacks) {
            List<Sticker> stickers = sourcePack.getStickers();
            if (stickers == null) continue;

            // Pre-fetch the source pack dir on SAF (one findFile per source pack, not per sticker).
            DocumentFile srcPackDir = (safRoot != null) ? safRoot.findFile(sourcePack.identifier) : null;

            for (Sticker sticker : stickers) {
                if (stickerIndex > maxStickers) break outer;

                String destFileName = stickerIndex + ".webp";

                // Raw copy — stickers already live on device and are valid as-is.
                if (isSAF) {
                    if (srcPackDir == null || newPackDir == null) continue;
                    DocumentFile srcFile  = srcPackDir.findFile(sticker.imageFileName);
                    if (srcFile == null) continue;
                    DocumentFile destFile = newPackDir.findFile(destFileName);
                    if (destFile == null) destFile = newPackDir.createFile("image/webp", destFileName);
                    if (destFile == null) continue;
                    try (InputStream is = context.getContentResolver().openInputStream(srcFile.getUri());
                         OutputStream os = context.getContentResolver().openOutputStream(destFile.getUri())) {
                        byte[] buf = new byte[8192]; int len;
                        while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                    }
                } else {
                    File srcFile  = new File(new File(rootPath, sourcePack.identifier), sticker.imageFileName);
                    File destFile = new File(new File(rootPath, newId), destFileName);
                    if (!srcFile.exists()) continue;
                    copyFile(srcFile, destFile);
                }


                // Build sticker JSON entry, preserving all emojis.
                JSONObject stickerJson = new JSONObject();
                stickerJson.put("image_file", destFileName);
                JSONArray emojis = new JSONArray();
                if (sticker.emojis != null && !sticker.emojis.isEmpty()) {
                    for (String e : sticker.emojis) emojis.put(e);
                } else {
                    emojis.put("\uD83D\uDE00");
                }
                stickerJson.put("emojis", emojis);
                stickersArray.put(stickerJson);
                stickerIndex++;
            }
        }

        if (stickersArray.length() < 3) {
            // WhatsApp requires at least 3 stickers — clean up and abort.
            if (isSAF) {
                if (newPackDir != null) newPackDir.delete();
            } else {
                deleteRecursive(new File(rootPath, newId));
            }
            throw new IOException("Merged pack has fewer than 3 stickers. Import the source packs first.");
        }

        // Copy the tray icon from the first source pack that has one.
        String trayFile = "tray.png";
        boolean trayCopied = false;
        for (StickerPack sourcePack : sourcePacks) {
            if (sourcePack.trayImageFile == null) continue;
            if (isSAF) {
                DocumentFile srcPackDir = safRoot != null ? safRoot.findFile(sourcePack.identifier) : null;
                DocumentFile srcTray    = srcPackDir != null ? srcPackDir.findFile(sourcePack.trayImageFile) : null;
                if (srcTray == null) continue;
                DocumentFile destTray = newPackDir != null ? newPackDir.findFile(trayFile) : null;
                if (destTray == null && newPackDir != null) destTray = newPackDir.createFile("image/png", trayFile);
                if (destTray == null) continue;
                try (InputStream is = context.getContentResolver().openInputStream(srcTray.getUri());
                     OutputStream os = context.getContentResolver().openOutputStream(destTray.getUri())) {
                    byte[] buf = new byte[8192]; int len;
                    while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                }
                trayCopied = true;
            } else {
                File srcTray  = new File(new File(rootPath, sourcePack.identifier), sourcePack.trayImageFile);
                File destTray = new File(new File(rootPath, newId), trayFile);
                if (!srcTray.exists()) continue;
                copyFile(srcTray, destTray);
                trayCopied = true;
            }
            if (trayCopied) break;
        }

        // Write the master contents.json entry.
        JSONObject masterRoot  = getOrSeedMasterRoot(context);
        JSONArray  masterPacks = masterRoot.optJSONArray("sticker_packs");
        if (masterPacks == null) {
            masterPacks = new JSONArray();
            masterRoot.put("sticker_packs", masterPacks);
        }

        JSONObject packJson = new JSONObject();
        packJson.put("identifier",          newId);
        packJson.put("name",                mergedName);
        packJson.put("publisher",           mergedAuthor);
        packJson.put("tray_image_file",     trayFile);
        packJson.put("image_data_version",  "1");
        packJson.put("animated_sticker_pack", firstAnimated);
        packJson.put("stickers",            stickersArray);
        masterPacks.put(packJson);

        saveMasterContents(context, masterRoot);
    }
}

