package com.kawai.mochi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import android.os.Build;
import android.graphics.Color;

import com.airbnb.lottie.LottieComposition;
import com.airbnb.lottie.LottieCompositionFactory;
import com.airbnb.lottie.LottieDrawable;
import com.airbnb.lottie.LottieTask;
import com.facebook.animated.webp.WebPFrame;
import com.facebook.animated.webp.WebPImage;
import com.facebook.imagepipeline.animated.base.AnimatedDrawableFrameInfo;
import com.facebook.imagepipeline.common.ImageDecodeOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * On-device Telegram → WhatsApp sticker converter.
 *
 * Conversion parameters are identical to the Python bot (tg-wa.py):
 *   • Static  → 512×512 WebP, ≤100 KB, transparent canvas
 *   • Animated → 512×512 animated WebP, ≤500 KB, transparent canvas
 *   • Tray    → 96×96 PNG, ≤50 KB
 *
 * Pack splitting rules (same as bot):
 *   1. Separate static and animated stickers into distinct groups.
 *   2. Split each group into chunks of ≤30 (WhatsApp limit).
 *   3. Discard chunks with < 3 stickers (WhatsApp minimum).
 */
public class TelegramConverter {

    private static final String TAG = "TelegramConverter";

    // WhatsApp limits (mirror the Python bot)
    private static final int WA_STATIC_MAX_BYTES    = 100 * 1024;  // 100 KB
    private static final int WA_ANIMATED_MAX_BYTES  = 500 * 1024;  // 500 KB
    private static final int WA_TRAY_MAX_BYTES      = 50  * 1024;  //  50 KB
    private static final int STICKER_SIZE           = 512;
    private static final int TRAY_SIZE              = 96;
    private static final int MAX_STICKERS_PER_PACK  = 30;
    private static final int MIN_STICKERS_PER_PACK  = 3;
    private static final int MAX_FRAMES             = 120;
    private static final int MAX_VIDEO_FRAMES       = 240;
    private static final float TARGET_FPS           = 20f;
    private static final int MAX_CONSECUTIVE_NETWORK_DOWNLOAD_FAILURES = 8;

    // ── Result model ─────────────────────────────────────────────────────────

    public static class ImportedPackResult {
        public final String identifier;
        public final String name;
        public final int stickerCount;
        public final boolean isAnimated;

        ImportedPackResult(String identifier, String name, int stickerCount, boolean isAnimated) {
            this.identifier   = identifier;
            this.name         = name;
            this.stickerCount = stickerCount;
            this.isAnimated   = isAnimated;
        }
    }

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
     * Downloads, converts, splits, and imports a Telegram sticker set.
     *
     * @param context     Application context.
     * @param urlOrName   Full {@code t.me/addstickers/…} URL or bare set name.
     * @param callback    Progress/log callbacks (called on background thread).
     * @return List of {@link ImportedPackResult} — one per imported pack (may be multiple due to splitting).
     */
    public static List<ImportedPackResult> importFromUrl(
            Context context,
            String urlOrName,
            ConversionCallback callback) throws Exception {
        return importFromUrl(context, urlOrName, "Telegram", "", callback);
    }

    public static List<ImportedPackResult> importFromUrl(
            Context context,
            String urlOrName,
            String authorName,
            ConversionCallback callback) throws Exception {
        return importFromUrl(context, urlOrName, authorName, "", callback);
    }

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
        log(callback, "📦 Found " + total + " stickers in '" + packTitle + "'");

        // 3. Download All First
        class DownloadedSticker {
            byte[] rawBytes;
            String fileId;
            boolean isAnimated;
            List<String> emojis;
            boolean isTgsAnim;
            boolean isVideoAnim;
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
            boolean isAnimated = isTgsAnim || isVideoAnim;
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
                // Add a new progress line so the next loop iteration replaces it
                log(callback, getProgressBar("⬇ Downloading:", i + 1, total));
                if (callback != null) callback.onProgress(i + 1, total);

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
            ds.rawBytes = raw;
            ds.fileId = fileId;
            ds.isAnimated = isAnimated;
            ds.emojis = emojis;
            ds.isTgsAnim = isTgsAnim;
            ds.isVideoAnim = isVideoAnim;
            downloadedStickers.add(ds);
            
            logReplace(callback, getProgressBar("⬇ Downloading:", i + 1, total));
            if (callback != null) callback.onProgress(i + 1, total);
        }

        int totalDownloaded = downloadedStickers.size();
        if (totalDownloaded == 0) {
            throw new IOException("Failed to download any stickers. Network or source issue.");
        }

        log(callback, "✅ Downloaded " + totalDownloaded + " stickers. Starting parallel conversion...");
        log(callback, getProgressBar("🔄 Converting:", 0, totalDownloaded));
        if (callback != null) callback.onProgress(0, totalDownloaded);

        // 4. Convert All Parallel
        List<StickerEntry> staticEntries = Collections.synchronizedList(new ArrayList<>());
        List<StickerEntry> animatedEntries = Collections.synchronizedList(new ArrayList<>());

        java.util.concurrent.atomic.AtomicInteger conversionSkipped = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger conversionDone = new java.util.concurrent.atomic.AtomicInteger(0);

        int processors = Math.max(2, Runtime.getRuntime().availableProcessors());
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(processors);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(totalDownloaded);

        for (int i = 0; i < totalDownloaded; i++) {
            final int index = i;
            final DownloadedSticker ds = downloadedStickers.get(i);

            executor.submit(() -> {
                try {
                    boolean animatedWebP = isAnimatedWebPBytes(ds.rawBytes);
                    boolean treatAsAnimated = ds.isTgsAnim || ds.isVideoAnim || animatedWebP;

                    byte[] converted;
                    if (ds.isTgsAnim) {
                        converted = convertTgsSticker(context, ds.rawBytes);
                    } else if (ds.isVideoAnim) {
                        converted = convertVideoSticker(context, ds.rawBytes);
                    } else if (animatedWebP) {
                        converted = convertAnimatedWebPSticker(ds.rawBytes);
                    } else {
                        converted = convertStaticSticker(ds.rawBytes);
                    }

                    StickerEntry entry = new StickerEntry(ds.fileId, converted, ds.emojis, treatAsAnimated);
                    if (treatAsAnimated) animatedEntries.add(entry);
                    else staticEntries.add(entry);

                } catch (Exception e) {
                    conversionSkipped.incrementAndGet();
                    log(callback, "⚠ Skipped sticker conversion: " + e.getMessage());
                    log(callback, getProgressBar("🔄 Converting:", conversionDone.get(), totalDownloaded));
                } finally {
                    int done = conversionDone.incrementAndGet();
                    logReplace(callback, getProgressBar("🔄 Converting:", done, totalDownloaded));
                    if (callback != null) callback.onProgress(done, totalDownloaded);
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

        int totalSkipped = downloadSkipped + conversionSkipped.get();
        int convertedCount = staticEntries.size() + animatedEntries.size();
        log(callback, "✅ Converted: " + staticEntries.size() + " static, " + animatedEntries.size() + " animated"
            + " (skipped " + totalSkipped + ")");

        if (convertedCount < MIN_STICKERS_PER_PACK) {
            throw new IOException("Only " + convertedCount + " sticker(s) converted successfully."
                + " This usually indicates a network or source format issue.");
        }

        // 4. Build pack names and split into ≤30-sticker chunks
        String publisher = authorName != null ? authorName.trim() : "";
        if (publisher.isEmpty()) publisher = "Telegram";

        boolean hasBoth = !staticEntries.isEmpty() && !animatedEntries.isEmpty();

        List<List<StickerEntry>> staticChunks   = chunk(staticEntries);
        List<List<StickerEntry>> animatedChunks = chunk(animatedEntries);

        List<ImportedPackResult> results = new ArrayList<>();

        // Static packs
        for (int ci = 0; ci < staticChunks.size(); ci++) {
            List<StickerEntry> ch = staticChunks.get(ci);
            if (ch.size() < MIN_STICKERS_PER_PACK) {
                log(callback, "⚠ Static chunk " + (ci + 1) + " has only " + ch.size() + " stickers (< 3) — skipped");
                continue;
            }
            String name = buildPackName(packTitle, hasBoth, false, ci + 1, staticChunks.size());
            log(callback, "💾 Saving '" + name + "' (" + ch.size() + " static stickers)…");
            String id = savePackToStorage(context, name, publisher, ch, false);
            results.add(new ImportedPackResult(id, name, ch.size(), false));
            log(callback, "✅ Saved pack: " + name);
        }

        // Animated packs
        for (int ci = 0; ci < animatedChunks.size(); ci++) {
            List<StickerEntry> ch = animatedChunks.get(ci);
            if (ch.size() < MIN_STICKERS_PER_PACK) {
                log(callback, "⚠ Animated chunk " + (ci + 1) + " has only " + ch.size() + " stickers (< 3) — skipped");
                continue;
            }
            String name = buildPackName(packTitle, hasBoth, true, ci + 1, animatedChunks.size());
            log(callback, "💾 Saving '" + name + "' (" + ch.size() + " animated stickers)…");
            String id = savePackToStorage(context, name, publisher, ch, true);
            results.add(new ImportedPackResult(id, name, ch.size(), true));
            log(callback, "✅ Saved pack: " + name);
        }

        if (results.isEmpty()) {
            throw new IOException("No valid packs could be created from this sticker set");
        }
        return results;
    }

    // ── Conversion: Static ────────────────────────────────────────────────────

    /**
     * Converts static WebP/PNG bytes to a 512×512 WebP ≤100 KB.
     * Mirror of Python bot's {@code convert_to_whatsapp_static()}.
     *
     * Mirrors Python bot quality stepping for static stickers.
     */
    static byte[] convertStaticSticker(byte[] data) throws IOException {
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (src == null) throw new IOException("Failed to decode static sticker");
        Bitmap canvas = makeCanvas(src, STICKER_SIZE);
        src.recycle();

        int quality = 95;
        Bitmap.CompressFormat format;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
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

    // ── Conversion: TGS (Lottie / animated sticker) ───────────────────────────

    /**
     * Converts a TGS (gzip-compressed Lottie JSON) to an animated WebP.
     * Mirror of Python bot's {@code convert_tgs_to_animated_webp()}.
     */
    static byte[] convertTgsSticker(Context context, byte[] tgsData) throws IOException {
        // 1. Decompress gzip → JSON string
        byte[] jsonBytes = decompressGzip(tgsData);

        // 2. Parse composition to get fps + frame range
        float fps;
        int inPoint, outPoint;
        try {
            JSONObject meta = new JSONObject(new String(jsonBytes, "UTF-8"));
            fps      = (float) meta.optDouble("fr", 30.0);
            inPoint  = meta.optInt("ip", 0);
            outPoint = meta.optInt("op", 90);
        } catch (JSONException e) {
            fps = 30f; inPoint = 0; outPoint = 90;
        }
        int renderFrames = Math.min(
            Math.max(1, outPoint - inPoint),
            Math.min((int) (10.0f * fps), MAX_FRAMES)
        );
        int frameDurationMs = Math.max(8, (int)(1000f / fps));

        // 3. Parse Lottie composition synchronously (Lottie task runs on main thread — use latch)
        LottieComposition composition = parseLottieSync(jsonBytes);
        if (composition == null) throw new IOException("Failed to parse Lottie composition");

        // 4. Render frames
        List<Bitmap> frames = renderLottieFrames(composition, inPoint, renderFrames);
        if (frames.size() < 2) throw new IOException("TGS produced too few frames");

        // 5. Encode animated WebP with strict validation gates.
        byte[] encoded = null;
        try {
            encoded = AnimatedWebPWriter.encode(frames, frameDurationMs);
            // Dump a debug copy to cache for inspection and logging
            try {
                File dbg = File.createTempFile("dbg_tgs_", ".webp", context.getCacheDir());
                try (FileOutputStream fos = new FileOutputStream(dbg)) { fos.write(encoded); }
                Log.d("TelegramConverter", "TGS debug output: " + dbg.getAbsolutePath()
                        + " bytes=" + (encoded != null ? encoded.length : 0)
                        + " isAnimated=" + AnimatedWebPWriter.isAnimated(encoded)
                        + " frames=" + AnimatedWebPWriter.countFrames(encoded));
            } catch (Exception ignored) {}

            validateAnimatedOutput(encoded);
            return encoded;
        } catch (IOException e) {
            // If validation fails, still dump bytes for offline inspection
            if (encoded != null) {
                try {
                    File dbg = File.createTempFile("dbg_tgs_fail_", ".webp", context.getCacheDir());
                    try (FileOutputStream fos = new FileOutputStream(dbg)) { fos.write(encoded); }
                    Log.d("TelegramConverter", "TGS validation failed — dumped: " + dbg.getAbsolutePath()
                            + " bytes=" + encoded.length
                            + " isAnimated=" + AnimatedWebPWriter.isAnimated(encoded)
                            + " frames=" + AnimatedWebPWriter.countFrames(encoded));
                } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    /** Synchronously parses a Lottie composition from JSON bytes. */
    private static LottieComposition parseLottieSync(byte[] jsonBytes) {
        try {
            String jsonString = new String(jsonBytes, "UTF-8");
            com.airbnb.lottie.LottieResult<LottieComposition> result = 
                LottieCompositionFactory.fromJsonStringSync(jsonString, null);
            return result.getValue();
        } catch (Exception e) {
            return null;
        }
    }

    /** Renders {@code count} frames starting at {@code startFrame} from a Lottie composition. */
    private static List<Bitmap> renderLottieFrames(LottieComposition composition, int startFrame, int count) {
        List<Bitmap> frames = new ArrayList<>();
        LottieDrawable drawable = new LottieDrawable();
        drawable.setComposition(composition);
        drawable.setRepeatCount(0);
        drawable.setBounds(0, 0, STICKER_SIZE, STICKER_SIZE);

        final int firstFrame = (int) composition.getStartFrame();
        final int lastFrame = (int) composition.getEndFrame();

        for (int i = 0; i < count; i++) {
            int frameToRender = Math.max(firstFrame, Math.min(lastFrame, startFrame + i));
            float progress;
            if (lastFrame > firstFrame) {
                progress = (frameToRender - firstFrame) / (float) (lastFrame - firstFrame);
            } else {
                progress = 0f;
            }
            drawable.setProgress(Math.max(0f, Math.min(progress, 1f)));

            Bitmap bmp = Bitmap.createBitmap(STICKER_SIZE, STICKER_SIZE, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.TRANSPARENT);
            drawable.draw(new Canvas(bmp));
            if (!isBitmapFullyTransparent(bmp)) {
                frames.add(bmp);
            } else {
                bmp.recycle();
            }
        }
        return frames;
    }

    // ── Conversion: Video (WebM) ──────────────────────────────────────────────

    /**
     * Converts a WebM video sticker to animated WebP using {@link MediaMetadataRetriever}.
     * Mirror of Python bot's {@code convert_video_to_animated_webp()}.
     */
    static byte[] convertVideoSticker(Context context, byte[] webmData) throws IOException {
        // Write to temp file — MediaMetadataRetriever needs a file path or FileDescriptor
        File tmpFile = File.createTempFile("tg_sticker_", ".webm", context.getCacheDir());
        try {
            try (FileOutputStream fos = new FileOutputStream(tmpFile)) { fos.write(webmData); }

            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(tmpFile.getAbsolutePath());

            // Get duration (ms)
            String durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = 0;
            if (durStr != null) { try { durationMs = Long.parseLong(durStr); } catch (NumberFormatException ignored) {} }
            if (durationMs <= 0) durationMs = 3000; // fallback 3 s

            // Cap: WhatsApp max 10 s, we cap at min(duration, 10000)
            durationMs = Math.min(durationMs, 10_000);

            // Match Python strategy: keep fps in [8, 20], cap by 10s and 240 frames.
            float actualFps = Math.max(8f, Math.min(TARGET_FPS, 20f));
            int frameCount = Math.min((int) (durationMs * actualFps / 1000f), MAX_VIDEO_FRAMES);
            if (frameCount < 2) frameCount = 2;
            int frameDurationMs = Math.max(8, (int)(1000f / actualFps));

            // Extract frames via getFrameAtTime (every (durationMs/frameCount) µs)
            List<Bitmap> frames = new ArrayList<>();
            for (int i = 0; i < frameCount; i++) {
                long timeUs = (long)((double) i / frameCount * durationMs * 1000L);
                Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) continue;
                Bitmap canvas = makeCanvas(frame, STICKER_SIZE);
                frame.recycle();
                frames.add(canvas);
            }
            retriever.release();

            if (frames.size() < 2) {
                if (frames.size() == 1) {
                    Bitmap one = frames.get(0);
                    Bitmap duplicate = one.copy(Bitmap.Config.ARGB_8888, true);
                    // Toggle one alpha pixel so encoder keeps it as true animation.
                    int px = duplicate.getPixel(0, 0);
                    int a = (px >>> 24) & 0xFF;
                    int rgb = px & 0x00FFFFFF;
                    int toggledA = (a == 0) ? 1 : 0;
                    duplicate.setPixel(0, 0, (toggledA << 24) | rgb);
                    frames.add(duplicate);
                } else {
                    throw new IOException("No frames extracted from WebM");
                }
            }

            byte[] encoded = AnimatedWebPWriter.encode(frames, frameDurationMs);
            // Dump debug copy
            try {
                File dbg = File.createTempFile("dbg_video_", ".webp", context.getCacheDir());
                try (FileOutputStream fos = new FileOutputStream(dbg)) { fos.write(encoded); }
                Log.d("TelegramConverter", "Video debug output: " + dbg.getAbsolutePath()
                        + " bytes=" + (encoded != null ? encoded.length : 0)
                        + " isAnimated=" + AnimatedWebPWriter.isAnimated(encoded)
                        + " frames=" + AnimatedWebPWriter.countFrames(encoded));
            } catch (Exception ignored) {}

            validateAnimatedOutput(encoded);
            return encoded;
        } finally {
            tmpFile.delete();
        }
    }

    private static void validateAnimatedOutput(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length < 128) {
            throw new IOException("Converted animated output is empty or too small");
        }
        if (encoded.length > WA_ANIMATED_MAX_BYTES) {
            throw new IOException("Converted animated output exceeds 500 KB limit");
        }
        if (!AnimatedWebPWriter.isAnimated(encoded)) {
            throw new IOException("Converted animated output is not marked as animated WebP");
        }
        if (AnimatedWebPWriter.countFrames(encoded) < 2) {
            throw new IOException("Converted animated output has fewer than 2 frames");
        }
    }

    private static boolean isAnimatedWebPBytes(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        try {
            WebPImage webPImage = WebPImage.createFromByteArray(data, ImageDecodeOptions.defaults());
            return webPImage != null && webPImage.getFrameCount() > 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static byte[] convertAnimatedWebPSticker(byte[] webpData) throws IOException {
        WebPImage image = WebPImage.createFromByteArray(webpData, ImageDecodeOptions.defaults());
        if (image == null) {
            throw new IOException("Failed to decode animated WebP sticker");
        }

        int frameCount = image.getFrameCount();
        if (frameCount < 2) {
            throw new IOException("Animated WebP sticker contains fewer than 2 frames");
        }

        List<Bitmap> frames = new ArrayList<>(Math.min(frameCount, MAX_FRAMES));
        int[] durations = image.getFrameDurations();
        int totalDuration = 0;
        int imageWidth = Math.max(1, image.getWidth());
        int imageHeight = Math.max(1, image.getHeight());

        Bitmap composed = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
        Canvas composedCanvas = new Canvas(composed);
        Paint clearPaint = new Paint();
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        int step = Math.max(1, (int) Math.ceil((double) frameCount / MAX_FRAMES));

        for (int i = 0; i < frameCount; i += step) {
            WebPFrame frame = image.getFrame(i);
            try {
                AnimatedDrawableFrameInfo info = image.getFrameInfo(i);

                int frameX = Math.max(0, info.xOffset);
                int frameY = Math.max(0, info.yOffset);
                int frameW = Math.min(Math.max(1, info.width), imageWidth - frameX);
                int frameH = Math.min(Math.max(1, info.height), imageHeight - frameY);

                if (info.blendOperation == AnimatedDrawableFrameInfo.BlendOperation.NO_BLEND
                        && frameW > 0 && frameH > 0) {
                    composedCanvas.drawRect(frameX, frameY, frameX + frameW, frameY + frameH, clearPaint);
                }

                Bitmap rendered = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888);
                frame.renderFrame(frameW, frameH, rendered);
                composedCanvas.drawBitmap(rendered, frameX, frameY, null);
                rendered.recycle();

                frames.add(makeCanvas(composed, STICKER_SIZE));

                if (info.disposalMethod == AnimatedDrawableFrameInfo.DisposalMethod.DISPOSE_TO_BACKGROUND
                        && frameW > 0 && frameH > 0) {
                    composedCanvas.drawRect(frameX, frameY, frameX + frameW, frameY + frameH, clearPaint);
                }

                int durationMs = (durations != null && i < durations.length) ? durations[i] : frame.getDurationMs();
                if (durationMs > 0) {
                    totalDuration += durationMs * step;
                }
            } finally {
                frame.dispose();
            }
        }

        composed.recycle();

        if (frames.size() < 2) {
            throw new IOException("Animated WebP sticker produced too few rendered frames");
        }

        int frameDurationMs = totalDuration > 0
                ? Math.max(8, totalDuration / frames.size())
                : 100;
        return AnimatedWebPWriter.encode(frames, frameDurationMs);
    }

    private static boolean isBitmapFullyTransparent(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int px : pixels) {
            if (((px >>> 24) & 0xFF) > 0) return false;
        }
        return true;
    }

    // ── Tray icon ─────────────────────────────────────────────────────────────

    /**
     * Creates a 96×96 PNG tray icon from the first sticker's converted bytes.
     * Mirror of Python bot's {@code optimize_tray_icon()}.
     *
     * Optimized to consistently produce small tray icons (typically <30 KB).
     */
    static byte[] convertTrayIcon(byte[] webpData, boolean isAnimated) throws IOException {
        Bitmap src = BitmapFactory.decodeByteArray(webpData, 0, webpData.length);
        if (src == null) throw new IOException("Cannot decode sticker for tray icon");
        Bitmap canvas = makeCanvas(src, TRAY_SIZE);
        src.recycle();

        // Try PNG first (lossless, typically smallest for small images)
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        canvas.compress(Bitmap.CompressFormat.PNG, 100, bos); // PNG quality param is ignored
        if (bos.size() <= WA_TRAY_MAX_BYTES) {
            canvas.recycle();
            return bos.toByteArray();
        }

        // Fallback: WebP with quality ladder if PNG somehow exceeds 50 KB (extremely rare for 96×96)
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

    // ── Pack splitting ────────────────────────────────────────────────────────

    private static List<List<StickerEntry>> chunk(List<StickerEntry> entries) {
        List<List<StickerEntry>> chunks = new ArrayList<>();
        for (int i = 0; i < entries.size(); i += MAX_STICKERS_PER_PACK) {
            chunks.add(entries.subList(i, Math.min(i + MAX_STICKERS_PER_PACK, entries.size())));
        }
        return chunks;
    }

    private static String buildPackName(String base, boolean hasBothTypes, boolean animated,
                                        int chunk, int total) {
        String typeSuffix = hasBothTypes ? (animated ? " Animated" : " Static") : "";
        String chunkSuffix = (total > 1) ? " [" + chunk + "/" + total + "]" : "";
        // Truncate base to avoid excessively long names
        if (base.length() > 30) base = base.substring(0, 30).trim();
        return base + typeSuffix + chunkSuffix;
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    /**
     * Writes a pack to the app's sticker storage and updates contents.json.
     * Returns the new pack identifier.
     */
    private static String savePackToStorage(Context context, String name, String author,
                                            List<StickerEntry> stickers, boolean isAnimated)
            throws IOException, JSONException {

        String identifier = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String rootPath   = WastickerParser.getStickerFolderPath(context);
        boolean isSAF     = WastickerParser.isCustomPathUri(context);

        // Create pack directory
        File packDir = null;
        if (!isSAF) {
            packDir = new File(rootPath, identifier);
            if (!packDir.exists()) packDir.mkdirs();
        }
        // SAF path handled via WastickerParser's ensureDirectory via importStickerPack below

        // Build a temp .wasticker zip in cache and use WastickerParser.importStickerPack()
        File tmpZip = File.createTempFile("tg_import_", ".wasticker", context.getCacheDir());
        try {
            buildWastickerZip(tmpZip, identifier, name, author, stickers, isAnimated);
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
                                          String author, List<StickerEntry> stickers,
                                          boolean isAnimated) throws IOException, JSONException {
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
        pack.put("animated_sticker_pack", isAnimated);
        pack.put("stickers", stickersArray);

        JSONArray packsArray = new JSONArray();
        packsArray.put(pack);
        JSONObject root = new JSONObject();
        root.put("sticker_packs", packsArray);

        String contentsJson = root.toString(2);

        // Build tray icon from first sticker
        byte[] trayBytes;
        try {
            trayBytes = convertTrayIcon(stickers.get(0).converted, isAnimated);
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

            // contents.json
            putZipEntry(zos, "contents.json", contentsJson.getBytes("UTF-8"));
            // tray.png
            putZipEntry(zos, "tray.png", trayBytes);
            // stickers
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
     * Creates a 512×512 (or {@code size}×{@code size}) ARGB_8888 transparent canvas
     * with the source bitmap thumbnail-fit and centred. Same logic as the Python bot.
     */
    private static Bitmap makeCanvas(Bitmap src, int size) {
        Bitmap canvas = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(canvas);
        float scale = Math.min((float) size / src.getWidth(), (float) size / src.getHeight());
        float dx    = (size - src.getWidth()  * scale) * 0.5f;
        float dy    = (size - src.getHeight() * scale) * 0.5f;
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        m.postTranslate(dx, dy);
        c.drawBitmap(src, m, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return canvas;
    }

    private static byte[] decompressGzip(byte[] compressed) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPInputStream gis = new GZIPInputStream(
                new java.io.ByteArrayInputStream(compressed))) {
            byte[] buf = new byte[8192]; int n;
            while ((n = gis.read(buf)) != -1) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /**
     * Extracts the set short-name from a full t.me URL or returns the input as-is.
     */
    static String extractSetName(String input) {
        if (input == null) return "";
        input = input.trim();
        // https://t.me/addstickers/PackName
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
        final boolean animated;
        String fileName; // set during ZIP assembly

        StickerEntry(String fileId, byte[] converted, List<String> emojis, boolean animated) {
            this.fileId    = fileId;
            this.converted = converted;
            this.emojis    = emojis;
            this.animated  = animated;
        }
    }
}
