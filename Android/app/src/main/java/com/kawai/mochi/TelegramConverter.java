package com.kawai.mochi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * On-device Telegram → WhatsApp sticker converter.
 *
 * Only static stickers are supported on-device:
 *   • Static  → 512×512 WebP, ≤100 KB, transparent canvas
 *   • Tray    → 96×96 PNG, ≤50 KB
 *
 * Animated stickers (TGS, WebM, animated WebP) are skipped and counted in
 * {@link ImportedPackResult#skippedAnimatedCount}.
 *
 * Pack splitting (chunking into ≤30) is NOT done here — the single returned
 * pack is handled by {@link StickerPackChunkManager} at "Add to WhatsApp" time,
 * exactly like the .wasticker import flow.
 */
public class TelegramConverter {

    private static final String TAG = "TelegramConverter";

    // WhatsApp limits
    private static final int WA_STATIC_MAX_BYTES    = 100 * 1024;  // 100 KB
    private static final int WA_TRAY_MAX_BYTES      = 50  * 1024;  //  50 KB
    private static final int STICKER_SIZE           = 512;
    private static final int TRAY_SIZE              = 96;
    private static final int MIN_STICKERS_PER_PACK  = 3;
    private static final int MAX_CONSECUTIVE_NETWORK_DOWNLOAD_FAILURES = 8;

    // ── Result model ─────────────────────────────────────────────────────────

    public record ImportedPackResult(
            String identifier,
            String name,
            int stickerCount,
            boolean isAnimated,
            int skippedAnimatedCount
    ) {}

    // ── Callback interface ────────────────────────────────────────────────────

    public interface ConversionCallback {
        /** Called on a background thread for each log event. */
        void onLog(String message);
        /** Called to replace the last log line (useful for progress bars). */
        void onLogReplace(String message);
        /** Called on a background thread as stickers are processed. */
        void onProgress(int done, int total);
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Downloads, converts (static only), and imports a Telegram sticker set.
     *
     * @param context     Application context.
     * @param urlOrName   Full {@code t.me/addstickers/…} URL or bare set name.
     * @param authorName  Publisher name for the pack metadata.
     * @param customPackName Optional override for the pack title.
     * @param callback    Progress/log callbacks (called on background thread).
     * @return List of {@link ImportedPackResult} — one entry for the static pack
     *         (which carries {@code skippedAnimatedCount} for any skipped stickers).
     *         The list may be empty if all stickers were animated/skipped.
     */
    public static List<ImportedPackResult> importFromUrl(
            Context context,
            String urlOrName,
            String authorName,
            String customPackName,
            ConversionCallback callback) throws Exception {

        // Get bot token from SharedPreferences (managed via BotTokenManager)
        String botToken = BotTokenManager.getBotToken(context);
        if (botToken.isEmpty()) {
            throw new IOException("Bot token not configured. Please set it in Settings.");
        }

        TelegramApiClient api = new TelegramApiClient(botToken, context);

        // 1. Normalise input: accept full URL or bare set name
        String setName = extractSetName(urlOrName);
        log(callback, "🔍 Fetching sticker set: " + setName);

        // 2. Fetch sticker set metadata
        JSONObject setInfo = api.getStickerSet(setName);
        String fetchedTitle = setInfo.optString("title", setName);
        String packTitle = (customPackName != null && !customPackName.trim().isEmpty())
            ? customPackName.trim()
            : fetchedTitle;
        JSONArray stickers = setInfo.optJSONArray("stickers");
        if (stickers == null || stickers.length() == 0) {
            throw new IOException("No stickers found in pack");
        }
        int total = stickers.length();
        // Total progress steps: download (total) + convert (total)
        int overallTotal = total * 2;
        log(callback, "📦 Found " + total + " stickers in '" + packTitle + "'");

        // 3. Download All First
        class DownloadedSticker {
            byte[] rawBytes;
            String fileId;
            boolean isTgsAnim;
            boolean isVideoAnim;
            List<String> emojis;
        }

        List<DownloadedSticker> downloadedStickers = new ArrayList<>();
        int consecutiveNetworkDownloadFailures = 0;
        int downloadSkipped = 0;

        log(callback, getProgressBar("⬇ Downloading:", 0, total));

        for (int i = 0; i < total; i++) {
            JSONObject sticker = stickers.getJSONObject(i);
            String fileId = sticker.getString("file_id");
            boolean isTgsAnim = sticker.optBoolean("is_animated", false);
            boolean isVideoAnim = sticker.optBoolean("is_video", false);
            List<String> emojis = extractEmojis(sticker.optString("emoji", ""));

            byte[] raw;
            try {
                raw = api.downloadFile(fileId);
                consecutiveNetworkDownloadFailures = 0;
            } catch (Exception e) {
                downloadSkipped++;
                if (isLikelyNetworkError(e)) {
                    consecutiveNetworkDownloadFailures++;
                } else {
                    consecutiveNetworkDownloadFailures = 0;
                }

                log(callback, "⚠ Skipped download for sticker " + (i + 1) + ": " + e.getMessage());
                log(callback, getProgressBar("⬇ Downloading:", i + 1, total));
                if (callback != null) callback.onProgress(i + 1, overallTotal);

                if (consecutiveNetworkDownloadFailures >= MAX_CONSECUTIVE_NETWORK_DOWNLOAD_FAILURES) {
                    throw new IOException(
                            "Network to Telegram API appears unavailable ("
                                    + consecutiveNetworkDownloadFailures
                                    + " consecutive failures). Please check internet/DNS and retry import."
                    );
                }
                continue;
            }

            DownloadedSticker ds = new DownloadedSticker();
            ds.rawBytes   = raw;
            ds.fileId     = fileId;
            ds.isTgsAnim  = isTgsAnim;
            ds.isVideoAnim = isVideoAnim;
            ds.emojis     = emojis;
            downloadedStickers.add(ds);

            logReplace(callback, getProgressBar("⬇ Downloading:", i + 1, total));
            if (callback != null) callback.onProgress(i + 1, overallTotal);
        }

        int totalDownloaded = downloadedStickers.size();
        if (totalDownloaded == 0) {
            throw new IOException("Failed to download any stickers. Network or source issue.");
        }

        log(callback, "✅ Downloaded " + totalDownloaded + " stickers. Starting conversion (static only)…");
        log(callback, getProgressBar("🔄 Converting:", 0, totalDownloaded));
        if (callback != null) callback.onProgress(total, overallTotal);

        // 4. Convert static stickers; skip animated ones
        java.util.concurrent.atomic.AtomicInteger conversionSkipped =
                new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger animatedSkipped =
                new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger conversionDone =
                new java.util.concurrent.atomic.AtomicInteger(0);

        List<StickerEntry> staticEntries =
                java.util.Collections.synchronizedList(new ArrayList<>());

        int processors = Math.max(2, Runtime.getRuntime().availableProcessors());
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(processors);
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(totalDownloaded);

        for (int i = 0; i < totalDownloaded; i++) {
            final DownloadedSticker ds = downloadedStickers.get(i);

            executor.submit(() -> {
                try {
                    // Determine if this sticker is animated
                    boolean isAnimatedByMeta = ds.isTgsAnim || ds.isVideoAnim;
                    boolean isAnimatedWebP   = !isAnimatedByMeta && isAnimatedWebPBytes(ds.rawBytes);
                    boolean treatAsAnimated  = isAnimatedByMeta || isAnimatedWebP;

                    if (treatAsAnimated) {
                        // Skip — animated stickers are not supported on-device
                        animatedSkipped.incrementAndGet();
                        logVerbose(callback, "⏭ Skipped animated sticker (not supported)");
                    } else {
                        byte[] converted = convertStaticSticker(ds.rawBytes);
                        staticEntries.add(new StickerEntry(ds.fileId, converted, ds.emojis));
                    }

                } catch (Exception e) {
                    conversionSkipped.incrementAndGet();
                    log(callback, "⚠ Skipped sticker conversion: " + e.getMessage());
                    log(callback, getProgressBar("🔄 Converting:", conversionDone.get(), totalDownloaded));
                } finally {
                    int done = conversionDone.incrementAndGet();
                    logReplace(callback, getProgressBar("🔄 Converting:", done, totalDownloaded));
                    if (callback != null) callback.onProgress(total + done, overallTotal);
                    latch.countDown();
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new IOException("Conversion was interrupted.");
        }

        int skippedAnimCount  = animatedSkipped.get();
        int totalSkipped      = downloadSkipped + conversionSkipped.get();
        int convertedCount    = staticEntries.size();

        log(callback, "✅ Converted: " + convertedCount + " static"
            + " | skipped animated: " + skippedAnimCount
            + " | other skipped: " + totalSkipped);

        if (convertedCount == 0 && skippedAnimCount > 0) {
            // All stickers were animated — surface a clear message and return an empty list
            // (the caller/UI will display the animated-not-supported banner).
            log(callback, "ℹ️ All stickers in this pack are animated and were skipped.");
            ImportedPackResult placeholder = new ImportedPackResult(
                    "", packTitle, 0, false, skippedAnimCount);
            List<ImportedPackResult> results = new ArrayList<>();
            results.add(placeholder);
            return results;
        }

        if (convertedCount < MIN_STICKERS_PER_PACK) {
            throw new IOException("Only " + convertedCount + " sticker(s) converted successfully."
                + " This usually indicates a network or source format issue.");
        }

        // 5. Build a single pack (no internal chunking — StickerPackChunkManager handles ≤30 splitting)
        String publisher = authorName != null ? authorName.trim() : "";
        if (publisher.isEmpty()) publisher = "Telegram";

        log(callback, "💾 Saving '" + packTitle + "' (" + convertedCount + " static stickers)…");
        String id = savePackToStorage(context, packTitle, publisher, staticEntries);
        log(callback, "✅ Saved pack: " + packTitle);

        List<ImportedPackResult> results = new ArrayList<>();
        results.add(new ImportedPackResult(id, packTitle, convertedCount, false, skippedAnimCount));
        return results;
    }

    // ── Conversion: Static ────────────────────────────────────────────────────

    /**
     * Converts static WebP/PNG bytes to a 512×512 WebP ≤100 KB.
     * Mirrors Python bot quality stepping for static stickers.
     */
    static byte[] convertStaticSticker(byte[] data) throws IOException {
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (src == null) throw new IOException("Failed to decode static sticker");
        Bitmap canvas = makeCanvas(src, STICKER_SIZE);
        src.recycle();

        int quality = 95;
        Bitmap.CompressFormat format;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format = Bitmap.CompressFormat.WEBP_LOSSY;
        } else {
            // noinspection deprecation
            format = Bitmap.CompressFormat.WEBP;
        }

        while (quality >= 5) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            canvas.compress(format, quality, bos);
            if (bos.size() <= WA_STATIC_MAX_BYTES) {
                canvas.recycle();
                return bos.toByteArray();
            }

            if (quality > 75) quality -= 5;
            else if (quality > 50) quality -= 10;
            else quality -= 15;
        }

        // Fallback: return at lowest quality anyway
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        canvas.compress(format, 5, bos);
        canvas.recycle();
        return bos.toByteArray();
    }

    // ── Animated WebP detection ───────────────────────────────────────────────

    /**
     * Returns true if the given bytes represent an animated WebP
     * (contains an ANIM chunk in the RIFF/WEBP header).
     * We do a lightweight byte-pattern check to avoid Fresco dependency.
     */
    private static boolean isAnimatedWebPBytes(byte[] data) {
        if (data == null || data.length < 20) return false;
        // WebP: bytes 0-3 = "RIFF", bytes 8-11 = "WEBP"
        if (data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F') return false;
        if (data[8] != 'W' || data[9] != 'E' || data[10] != 'B' || data[11] != 'P') return false;
        // VP8X chunk with Animation bit set: bytes 12-15 = "VP8X", bit 1 of byte 20 = Animation
        // Search for "ANIM" chunk anywhere in first 100 bytes as a quick heuristic
        int limit = Math.min(data.length - 4, 200);
        for (int i = 12; i < limit; i++) {
            if (data[i] == 'A' && data[i+1] == 'N' && data[i+2] == 'I' && data[i+3] == 'M') {
                return true;
            }
        }
        return false;
    }

    // ── Tray icon ─────────────────────────────────────────────────────────────

    /**
     * Creates a 96×96 PNG tray icon from the first sticker's converted bytes.
     * Mirror of Python bot's {@code optimize_tray_icon()}.
     */
    static byte[] convertTrayIcon(byte[] webpData) throws IOException {
        Bitmap src = BitmapFactory.decodeByteArray(webpData, 0, webpData.length);
        if (src == null) throw new IOException("Cannot decode sticker for tray icon");
        Bitmap canvas = makeCanvas(src, TRAY_SIZE);
        src.recycle();

        // Try PNG first (lossless, typically smallest for small images)
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        canvas.compress(Bitmap.CompressFormat.PNG, 100, bos);
        if (bos.size() <= WA_TRAY_MAX_BYTES) {
            canvas.recycle();
            return bos.toByteArray();
        }

        // Fallback: WebP with quality ladder
        Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? Bitmap.CompressFormat.WEBP_LOSSY
                : Bitmap.CompressFormat.WEBP;
        int[] qualities = {90, 75, 50, 25};
        for (int q : qualities) {
            ByteArrayOutputStream wosbos = new ByteArrayOutputStream();
            canvas.compress(format, q, wosbos);
            if (wosbos.size() <= WA_TRAY_MAX_BYTES) {
                canvas.recycle();
                return wosbos.toByteArray();
            }
        }

        // Final fallback: PNG anyway
        ByteArrayOutputStream finalBos = new ByteArrayOutputStream();
        canvas.compress(Bitmap.CompressFormat.PNG, 100, finalBos);
        canvas.recycle();
        return finalBos.toByteArray();
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    /**
     * Writes a pack to the app's sticker storage and updates contents.json.
     * Returns the new pack identifier.
     *
     * <p>No internal chunking is performed here. Packs with more than 30 stickers
     * are handled by {@link StickerPackChunkManager} when the user taps "Add to WhatsApp".</p>
     */
    private static String savePackToStorage(Context context, String name, String author,
                                            List<StickerEntry> stickers)
            throws IOException, JSONException {

        String identifier = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // Build a temp .wasticker zip in cache and use WastickerParser.importStickerPack()
        File tmpZip = File.createTempFile("tg_import_", ".wasticker", context.getCacheDir());
        try {
            buildWastickerZip(tmpZip, identifier, name, author, stickers);
            android.net.Uri zipUri = android.net.Uri.fromFile(tmpZip);
            WastickerParser.importStickerPack(context, zipUri);
            StickerContentProvider provider = StickerContentProvider.getInstance();
            if (provider != null) provider.invalidateStickerPackList();
        } finally {
            tmpZip.delete();
        }
        return identifier;
    }

    /**
     * Builds a WhatsApp-compliant .wasticker ZIP in {@code outFile}.
     */
    private static void buildWastickerZip(File outFile, String identifier, String name,
                                          String author, List<StickerEntry> stickers)
            throws IOException, JSONException {

        // Build contents.json
        JSONArray stickersArray = new JSONArray();
        for (int i = 0; i < stickers.size(); i++) {
            StickerEntry e = stickers.get(i);
            JSONObject s = new JSONObject();
            String fname = identifier + "_" + String.format("%03d", i + 1) + ".webp";
            e.fileName = fname;
            s.put("image_file", fname);
            JSONArray emojis = new JSONArray();
            for (String emoji : e.emojis) {
                emojis.put(emoji);
            }
            s.put("emojis", emojis);
            stickersArray.put(s);
        }

        JSONObject pack = new JSONObject();
        pack.put("identifier", identifier);
        pack.put("name", name);
        pack.put("publisher", author);
        pack.put("tray_image_file", "tray.png");
        pack.put("publisher_email", "");
        pack.put("publisher_website", "");
        pack.put("privacy_policy_website", "");
        pack.put("license_agreement_website", "");
        pack.put("image_data_version", "1");
        pack.put("avoid_cache", false);
        pack.put("animated_sticker_pack", false);
        pack.put("stickers", stickersArray);

        JSONArray packsArray = new JSONArray();
        packsArray.put(pack);
        JSONObject root = new JSONObject();
        root.put("sticker_packs", packsArray);

        String contentsJson = root.toString(2);

        // Build tray icon from first sticker
        byte[] trayBytes;
        try {
            trayBytes = convertTrayIcon(stickers.get(0).converted);
        } catch (Exception e) {
            // Fallback: transparent 96×96 PNG
            Bitmap empty = Bitmap.createBitmap(TRAY_SIZE, TRAY_SIZE, Bitmap.Config.ARGB_8888);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            empty.compress(Bitmap.CompressFormat.PNG, 100, bos);
            empty.recycle();
            trayBytes = bos.toByteArray();
        }

        // Write ZIP
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new java.io.BufferedOutputStream(new FileOutputStream(outFile)))) {
            putZipEntry(zos, "contents.json", contentsJson.getBytes("UTF-8"));
            putZipEntry(zos, "tray.png", trayBytes);
            for (StickerEntry e : stickers) {
                putZipEntry(zos, e.fileName, e.converted);
            }
        }
    }

    private static void putZipEntry(java.util.zip.ZipOutputStream zos, String name, byte[] data)
            throws IOException {
        zos.putNextEntry(new java.util.zip.ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a {@code size}×{@code size} ARGB_8888 transparent canvas
     * with the source bitmap thumbnail-fit and centred. Same logic as the Python bot.
     */
    private static Bitmap makeCanvas(Bitmap src, int size) {
        Bitmap canvas = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        canvas.eraseColor(Color.TRANSPARENT);
        Canvas c = new Canvas(canvas);
        float scale = Math.min((float) size / src.getWidth(), (float) size / src.getHeight());
        float dx    = (size - src.getWidth()  * scale) * 0.5f;
        float dy    = (size - src.getHeight() * scale) * 0.5f;
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        m.postTranslate(dx, dy);
        c.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        return canvas;
    }

    /**
     * Extracts the set short-name from a full t.me URL or returns the input as-is.
     */
    static String extractSetName(String input) {
        if (input == null) return "";
        input = input.trim();
        int idx = input.lastIndexOf('/');
        if (idx >= 0 && idx < input.length() - 1) {
            return input.substring(idx + 1);
        }
        return input;
    }

    private static void log(ConversionCallback cb, String msg) {
        Log.d(TAG, msg);
        if (cb != null) cb.onLog(msg);
    }

    private static void logVerbose(ConversionCallback cb, String msg) {
        Log.d(TAG, msg);
        if (cb != null) cb.onLog(msg);
    }

    private static void logReplace(ConversionCallback cb, String msg) {
        if (cb != null) cb.onLogReplace(msg);
    }

    private static List<String> extractEmojis(String emojiField) {
        List<String> emojis = new ArrayList<>(3);
        if (emojiField == null) emojiField = "";

        BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(emojiField);
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            String cluster = emojiField.substring(start, end);
            if (cluster.trim().isEmpty()) continue;
            emojis.add(cluster);
            if (emojis.size() >= 3) break;
        }

        if (emojis.isEmpty()) {
            emojis.add("😊");
        }
        return emojis;
    }

    private static String getProgressBar(String prefix, int done, int total) {
        if (total <= 0) total = 1;
        int maxBars = 10;
        int filled = (int) (((float) done / total) * maxBars);
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(" [");
        for (int i = 0; i < maxBars; i++) {
            if (i < filled) sb.append("█");
            else sb.append("░");
        }
        int percent = (int) (((float) done / total) * 100);
        sb.append("] ").append(percent).append("% (").append(done).append("/").append(total).append(")");
        return sb.toString();
    }

    private static boolean isLikelyNetworkError(Exception e) {
        if (e == null) return false;
        if (e instanceof java.net.UnknownHostException
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException) {
            return true;
        }
        Throwable cause = e.getCause();
        if (cause instanceof java.net.UnknownHostException
                || cause instanceof java.net.SocketTimeoutException
                || cause instanceof java.net.ConnectException) {
            return true;
        }
        String msg = e.getMessage();
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("unable to resolve host")
                || m.contains("failed to connect")
                || m.contains("timeout")
                || m.contains("connection reset")
                || m.contains("network");
    }

    // ── Internal sticker entry ────────────────────────────────────────────────

    private static class StickerEntry {
        final String fileId;
        final byte[] converted;
        final List<String> emojis;
        String fileName; // set during ZIP assembly

        StickerEntry(String fileId, byte[] converted, List<String> emojis) {
            this.fileId    = fileId;
            this.converted = converted;
            this.emojis    = emojis;
        }
    }
}
