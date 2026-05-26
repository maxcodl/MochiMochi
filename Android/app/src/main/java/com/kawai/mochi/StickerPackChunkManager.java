package com.kawai.mochi;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages splitting a large sticker pack (>30 stickers) into ≤30-sticker
 * chunks for sending to WhatsApp.
 *
 * <p>WhatsApp enforces a hard limit of 30 stickers per pack. Packs are stored
 * locally without this limit; chunks are only materialized on disk when the
 * user taps "Add to WhatsApp" and removed after all chunks have been added.
 *
 * <p>Chunk identifiers follow the pattern {@code <originalId>_chunk_<index>}
 * (0-based) so they are easy to enumerate and clean up.
 */
public class StickerPackChunkManager {

    private static final String TAG = "StickerPackChunkMgr";
    static final int CHUNK_SIZE = 30;
    /** Prefix appended to the original identifier for chunk packs. */
    static final String CHUNK_SUFFIX = "_chunk_";

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Returns true when {@code pack} has more stickers than WhatsApp allows in
     * a single pack and therefore needs to be split before adding.
     */
    public static boolean needsChunking(StickerPack pack) {
        List<Sticker> stickers = pack.getStickers();
        return stickers != null && stickers.size() > CHUNK_SIZE;
    }

    /**
     * Splits the sticker list of {@code original} into sub-lists of at most
     * {@link #CHUNK_SIZE} stickers.  Does not write anything to disk.
     *
     * @return list of {@code StickerPack} objects; each shares the same
     *         metadata as {@code original} but has a derived identifier and a
     *         sub-list of stickers.
     */
    public static List<StickerPack> splitIntoChunks(StickerPack original) {
        List<Sticker> all = original.getStickers();
        if (all == null || all.isEmpty()) return Collections.emptyList();

        List<StickerPack> chunks = new ArrayList<>();
        int total = all.size();
        int chunkIndex = 0;

        for (int start = 0; start < total; start += CHUNK_SIZE) {
            int end = Math.min(start + CHUNK_SIZE, total);
            List<Sticker> slice = new ArrayList<>(all.subList(start, end));

            String chunkId = original.identifier + CHUNK_SUFFIX + chunkIndex;
            String chunkName = original.name + " (" + (chunkIndex + 1) + "/" + numChunks(total) + ")";

            StickerPack chunk = new StickerPack(
                    chunkId,
                    chunkName,
                    original.publisher,
                    original.trayImageFile,
                    original.publisherEmail,
                    original.publisherWebsite,
                    original.privacyPolicyWebsite,
                    original.licenseAgreementWebsite,
                    original.imageDataVersion,
                    original.avoidCache,
                    original.animatedStickerPack
            );
            chunk.setStickers(slice);
            chunks.add(chunk);
            chunkIndex++;
        }
        return chunks;
    }

    /**
     * Materializes a chunk pack on disk so that the
     * {@link StickerContentProvider} can serve it to WhatsApp.
     *
     * <p>Sticker files are hard-copied from the original pack's directory into
     * a new directory named after the chunk identifier. The tray image is
     * shared (copied). A minimal {@code contents.json} entry is appended to
     * the master contents file.
     *
     * @param context  app context
     * @param chunk    chunk pack returned by {@link #splitIntoChunks}
     * @param original the source pack (provides sticker files)
     * @throws IOException   if any file operation fails
     * @throws JSONException if the master contents file cannot be updated
     */
    public static void registerChunk(Context context, StickerPack chunk, StickerPack original)
            throws IOException, JSONException {

        final boolean isSAF = WastickerParser.isCustomPathUri(context);
        final String rootPath = WastickerParser.getStickerFolderPath(context);

        // ── 1. Create chunk directory ─────────────────────────────────────
        DocumentFile safRoot = isSAF
                ? DocumentFile.fromTreeUri(context, Uri.parse(rootPath))
                : null;
        DocumentFile origPackDirDoc = null;

        if (isSAF) {
            if (safRoot == null) throw new IOException("SAF root inaccessible");
            // Create chunk directory
            if (safRoot.findFile(chunk.identifier) == null) {
                safRoot.createDirectory(chunk.identifier);
            }
            origPackDirDoc = safRoot.findFile(original.identifier);
        } else {
            File chunkDir = new File(rootPath, chunk.identifier);
            if (!chunkDir.exists() && !chunkDir.mkdirs()) {
                throw new IOException("Cannot create chunk directory: " + chunkDir);
            }
        }

        DocumentFile chunkPackDirDoc = null;
        if (isSAF) {
            if (safRoot == null) throw new IOException("SAF root inaccessible");
            chunkPackDirDoc = safRoot.findFile(chunk.identifier);
            if (chunkPackDirDoc == null) {
                chunkPackDirDoc = safRoot.createDirectory(chunk.identifier);
            }
            if (chunkPackDirDoc == null) {
                throw new IOException("Cannot create SAF chunk directory: " + chunk.identifier);
            }
            origPackDirDoc = safRoot.findFile(original.identifier);
            if (origPackDirDoc == null) {
                throw new IOException("SAF Original pack directory not found under root: " + original.identifier);
            }
        } else {
            File chunkDir = new File(rootPath, chunk.identifier);
            if (!chunkDir.exists() && !chunkDir.mkdirs()) {
                throw new IOException("Cannot create chunk directory: " + chunkDir);
            }
        }

        // ── 2. Copy tray icon (fallback: generate from first sticker) ─────
        ensureChunkTrayIcon(context, isSAF, rootPath, chunk, original,
            origPackDirDoc, chunkPackDirDoc);

        // ── 3. Copy sticker files ─────────────────────────────────────────
        List<Sticker> stickers = chunk.getStickers();
        if (stickers != null) {
            for (Sticker s : stickers) {
                copyFileWithinStorage(context, isSAF, rootPath,
                        original.identifier, s.imageFileName,
                        chunk.identifier, s.imageFileName,
                        origPackDirDoc, chunkPackDirDoc);
            }
        }

        // ── 4. Register in master contents.json ───────────────────────────
        addChunkToMasterContents(context, chunk, original);

        // ── 5. Invalidate content provider cache ──────────────────────────
        StickerContentProvider provider = StickerContentProvider.getInstance();
        if (provider != null) provider.invalidateStickerPackList();
    }

    /**
     * Removes all chunk directories and their entries from the master
     * {@code contents.json} for the given original pack.
     *
     * @param context            app context
     * @param originalIdentifier identifier of the source pack
     */
    public static void cleanupChunks(Context context, String originalIdentifier)
            throws IOException, JSONException {

        final boolean isSAF = WastickerParser.isCustomPathUri(context);
        final String rootPath = WastickerParser.getStickerFolderPath(context);
        final String prefix = originalIdentifier + CHUNK_SUFFIX;

        // ── 1. Remove chunk directories ───────────────────────────────────
        if (isSAF) {
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(rootPath));
            if (root != null) {
                for (DocumentFile f : root.listFiles()) {
                    String name = f.getName();
                    if (name != null && name.startsWith(prefix) && f.isDirectory()) {
                        f.delete();
                        Log.d(TAG, "Deleted SAF chunk dir: " + name);
                    }
                }
            }
        } else {
            File rootDir = new File(rootPath);
            File[] children = rootDir.listFiles();
            if (children != null) {
                for (File f : children) {
                    if (f.isDirectory() && f.getName().startsWith(prefix)) {
                        deleteRecursive(f);
                        Log.d(TAG, "Deleted chunk dir: " + f.getName());
                    }
                }
            }
        }

        // ── 2. Remove from master contents.json ───────────────────────────
        removeChunksFromMasterContents(context, prefix);

        // ── 3. Invalidate cache ───────────────────────────────────────────
        StickerContentProvider provider = StickerContentProvider.getInstance();
        if (provider != null) provider.invalidateStickerPackList();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Returns the number of chunks needed for {@code totalStickers}. */
    public static int numChunks(int totalStickers) {
        return (totalStickers + CHUNK_SIZE - 1) / CHUNK_SIZE;
    }

    // ── File I/O ──────────────────────────────────────────────────────────────

    private static void copyFileWithinStorage(
            Context context,
            boolean isSAF, String rootPath,
            String srcPackId, String srcFileName,
            String dstPackId, String dstFileName,
            DocumentFile srcPackDirDoc,
            DocumentFile dstPackDirDoc) throws IOException {

        if (srcFileName == null || srcFileName.isEmpty()) return;

        if (isSAF) {
            if (srcPackDirDoc == null) {
                throw new IOException("SAF Source pack directory document is null for pack: " + srcPackId);
            }
            if (dstPackDirDoc == null) {
                throw new IOException("SAF Destination pack directory document is null for pack: " + dstPackId);
            }
            DocumentFile srcFile = srcPackDirDoc.findFile(srcFileName);
            if (srcFile == null) {
                throw new IOException("SAF Source file not found: " + srcFileName + " in pack: " + srcPackId);
            }
            DocumentFile dstFile = dstPackDirDoc.findFile(dstFileName);
            if (dstFile == null) {
                dstFile = dstPackDirDoc.createFile("image/*", dstFileName);
            }
            if (dstFile == null) {
                throw new IOException("Cannot create SAF file: " + dstFileName + " in chunk: " + dstPackId);
            }

            try (InputStream is = context.getContentResolver().openInputStream(srcFile.getUri());
                 OutputStream os = context.getContentResolver().openOutputStream(dstFile.getUri())) {
                if (is == null) throw new IOException("Cannot open SAF source input stream: " + srcFileName);
                if (os == null) throw new IOException("Cannot open SAF dest output stream: " + dstFileName);
                pipe(is, os);
            }
        } else {
            File srcDir = new File(rootPath, srcPackId);
            File dstDir = new File(rootPath, dstPackId);
            if (!srcDir.exists()) {
                throw new IOException("Source pack directory does not exist: " + srcDir.getAbsolutePath());
            }
            if (!dstDir.exists()) {
                throw new IOException("Destination pack directory does not exist: " + dstDir.getAbsolutePath());
            }
            File src = new File(srcDir, srcFileName);
            File dst = new File(dstDir, dstFileName);
            if (!src.exists()) {
                throw new IOException("Source file does not exist: " + src.getAbsolutePath());
            }
            try (InputStream is = new FileInputStream(src);
                 OutputStream os = new FileOutputStream(dst)) {
                pipe(is, os);
            }
        }
    }

    private static void ensureChunkTrayIcon(Context context,
                                            boolean isSAF,
                                            String rootPath,
                                            StickerPack chunk,
                                            StickerPack original,
                                            DocumentFile origPackDirDoc,
                                            DocumentFile chunkPackDirDoc) throws IOException {
        String trayFileName = chunk.trayImageFile;
        if (trayFileName == null || trayFileName.isEmpty()) {
            trayFileName = "tray.png";
        }

        try {
            copyFileWithinStorage(context, isSAF, rootPath,
                    original.identifier, original.trayImageFile,
                    chunk.identifier, trayFileName,
                    origPackDirDoc, chunkPackDirDoc);
            return;
        } catch (IOException e) {
            Log.w(TAG, "Tray copy failed; generating from first sticker", e);
        }

        List<Sticker> stickers = chunk.getStickers();
        if (stickers == null || stickers.isEmpty()) {
            throw new IOException("Chunk has no stickers to generate tray icon: " + chunk.identifier);
        }

        String srcStickerFile = stickers.get(0).imageFileName;
        if (srcStickerFile == null || srcStickerFile.isEmpty()) {
            throw new IOException("Chunk sticker file is empty for tray generation: " + chunk.identifier);
        }

        if (isSAF) {
            if (origPackDirDoc == null || chunkPackDirDoc == null) {
                throw new IOException("SAF pack directories missing for tray generation: " + chunk.identifier);
            }
            DocumentFile srcFile = origPackDirDoc.findFile(srcStickerFile);
            if (srcFile == null) {
                throw new IOException("SAF source sticker not found for tray generation: " + srcStickerFile);
            }
            DocumentFile dstFile = chunkPackDirDoc.findFile(trayFileName);
            if (dstFile == null) {
                dstFile = chunkPackDirDoc.createFile("image/png", trayFileName);
            }
            if (dstFile == null) {
                throw new IOException("Cannot create SAF tray file: " + trayFileName);
            }
            StickerProcessor.processTrayIcon(context, srcFile.getUri(), dstFile.getUri());
        } else {
            File srcDir = new File(rootPath, original.identifier);
            File dstDir = new File(rootPath, chunk.identifier);
            File srcFile = new File(srcDir, srcStickerFile);
            File dstFile = new File(dstDir, trayFileName);
            if (!srcFile.exists()) {
                throw new IOException("Source sticker does not exist for tray generation: " + srcFile.getAbsolutePath());
            }
            StickerProcessor.processTrayIcon(srcFile, dstFile);
        }
    }

    private static void pipe(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    // ── contents.json ─────────────────────────────────────────────────────────

    private static void addChunkToMasterContents(Context context, StickerPack chunk, StickerPack original)
            throws IOException, JSONException {

        JSONObject masterRoot = WastickerParser.getOrSeedMasterRootPublic(context);
        JSONArray masterPacks = masterRoot.optJSONArray("sticker_packs");
        if (masterPacks == null) {
            masterPacks = new JSONArray();
            masterRoot.put("sticker_packs", masterPacks);
        }

        // Remove stale chunk entry if it exists (idempotent)
        JSONArray updated = new JSONArray();
        for (int i = 0; i < masterPacks.length(); i++) {
            JSONObject p = masterPacks.getJSONObject(i);
            if (!p.optString("identifier").equals(chunk.identifier)) {
                updated.put(p);
            }
        }

        // Build new chunk entry from original's JSON, override identifier/name/stickers
        JSONObject chunkJson = new JSONObject();
        chunkJson.put("identifier", chunk.identifier);
        chunkJson.put("name", chunk.name);
        chunkJson.put("publisher", chunk.publisher);
        String trayFileName = chunk.trayImageFile;
        if (trayFileName == null || trayFileName.isEmpty()) trayFileName = "tray.png";
        chunkJson.put("tray_image_file", trayFileName);
        chunkJson.put("image_data_version", chunk.imageDataVersion != null ? chunk.imageDataVersion : "1");
        chunkJson.put("animated_sticker_pack", chunk.animatedStickerPack ? 1 : 0);

        JSONArray stickersArray = new JSONArray();
        List<Sticker> stickers = chunk.getStickers();
        if (stickers != null) {
            for (Sticker s : stickers) {
                JSONObject sj = new JSONObject();
                sj.put("image_file", s.imageFileName);
                JSONArray emojis = new JSONArray();
                if (s.emojis != null) {
                    for (String e : s.emojis) emojis.put(e);
                } else {
                    emojis.put("\uD83D\uDE00");
                }
                sj.put("emojis", emojis);
                stickersArray.put(sj);
            }
        }
        chunkJson.put("stickers", stickersArray);
        updated.put(chunkJson);

        masterRoot.put("sticker_packs", updated);
        WastickerParser.saveMasterContentsPublic(context, masterRoot);
    }

    private static void removeChunksFromMasterContents(Context context, String chunkPrefix)
            throws IOException, JSONException {

        JSONObject masterRoot = WastickerParser.getOrSeedMasterRootPublic(context);
        JSONArray masterPacks = masterRoot.optJSONArray("sticker_packs");
        if (masterPacks == null) return;

        JSONArray updated = new JSONArray();
        for (int i = 0; i < masterPacks.length(); i++) {
            JSONObject p = masterPacks.getJSONObject(i);
            String id = p.optString("identifier", "");
            if (!id.startsWith(chunkPrefix)) {
                updated.put(p);
            }
        }
        masterRoot.put("sticker_packs", updated);
        WastickerParser.saveMasterContentsPublic(context, masterRoot);
    }
}
