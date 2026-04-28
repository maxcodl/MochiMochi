package com.kawai.mochi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;

/**
 * Pure-Java animated WebP RIFF container assembler.
 *
 * Encodes a list of {@link Bitmap} frames into an animated WebP byte array without
 * any native library beyond {@link Bitmap#compress}.
 *
 * Container structure:
 *  RIFF … WEBP
 *    VP8X  (flags = animation)
 *    ANIM  (background colour, loop count)
 *    ANMF* (one per frame, each containing a VP8/VP8L chunk)
 *
 * Quality / size management mirrors the Python bot:
 *   Pass 1 – quality ladder [72, 58, 46, 34, 24]
 *   Pass 2 – fallback ladder [50, 38, 28, 20]
 *   Pass 3 – frame decimation 2×/3×/4× if still over limit
 */
public class AnimatedWebPWriter {

    private static final String TAG = "AnimatedWebPWriter";

    private static final int MAX_SIZE_BYTES = 490 * 1024; // 490 KB target (500 KB hard)

    // ── Public entry point ───────────────────────────────────────────────────

    /**
     * Encodes {@code frames} into an animated WebP byte array under 500 KB.
     *
     * @param frames        List of 512×512 ARGB_8888 bitmaps (not recycled by this method).
     * @param frameDurationMs Duration per frame in milliseconds.
     * @return Animated WebP bytes.
     * @throws IOException if no quality/decimation combination fits under the limit.
     */
    public static byte[] encode(List<Bitmap> frames, int frameDurationMs) throws IOException {
        if (frames == null || frames.isEmpty()) {
            throw new IOException("No frames provided");
        }
        if (frames.size() == 1) {
            // Duplicate the single frame so WhatsApp accepts it as animated (≥ 2 frames required)
            frames = java.util.Arrays.asList(frames.get(0), frames.get(0));
        }

        int[] qualityLadder = {76, 68, 60, 52, 44, 36, 28, 22, 16};
        float[] scalePasses = {1.00f, 0.92f, 0.86f, 0.80f, 0.74f, 0.68f};
        int[] decimations   = {1, 2, 3, 4};

        // Multi-level strategy:
        // 1) keep full frame cadence and progressively lower quality
        // 2) if still oversized, reduce spatial resolution while preserving timing
        // 3) only then use frame decimation as a last resort
        for (int decimation : decimations) {
            int curDuration = Math.min(frameDurationMs * decimation, 1000);
            List<Bitmap> decimatedFrames = decimate(frames, decimation);
            if (decimatedFrames.size() < 2) continue;

            for (float scale : scalePasses) {
                List<Bitmap> curFrames = scaleFramesIfNeeded(decimatedFrames, scale);
                try {
                    for (int quality : qualityLadder) {
                        byte[] result = tryEncode(curFrames, curDuration, quality);
                        if (result != null && result.length <= MAX_SIZE_BYTES) {
                            Log.d(TAG, "encode: success frames=" + curFrames.size()
                                    + " quality=" + quality + " decimation=" + decimation
                                    + " scale=" + scale
                                    + " out=" + result.length);
                            return result;
                        }
                    }
                } finally {
                    recycleScaledFrames(curFrames, decimatedFrames);
                }
            }
        }
        throw new IOException("Cannot encode animated WebP under 500 KB limit");
    }

    /**
     * Returns true if {@code data} is an animated WebP (has VP8X + animation flag or ANIM chunk).
     */
    public static boolean isAnimated(byte[] data) {
        if (data == null || data.length < 30) return false;
        if (data[0] != 'R' || data[8] != 'W') return false;
        if (data[12] == 'V' && data[13] == 'P' && data[14] == '8' && data[15] == 'X') {
            return (data[20] & 0x02) != 0;
        }
        for (int i = 12; i < Math.min(data.length - 4, 512); i++) {
            if (data[i] == 'A' && data[i+1] == 'N' && data[i+2] == 'I' && data[i+3] == 'M') return true;
        }
        return false;
    }

    /**
     * Counts ANMF chunks in the animated WebP payload.
     * Returns 1 for non-animated (or malformed) inputs.
     */
    public static int countFrames(byte[] data) {
        if (data == null || data.length < 30) return 1;
        if (data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F') return 1;
        if (data[8] != 'W' || data[9] != 'E' || data[10] != 'B' || data[11] != 'P') return 1;

        int count = 0;
        int pos = 12;
        while (pos + 8 <= data.length) {
            if (data[pos] == 'A' && data[pos + 1] == 'N' && data[pos + 2] == 'M' && data[pos + 3] == 'F') {
                count++;
            }

            int size = (data[pos + 4] & 0xFF)
                    | ((data[pos + 5] & 0xFF) << 8)
                    | ((data[pos + 6] & 0xFF) << 16)
                    | ((data[pos + 7] & 0xFF) << 24);
            if (size < 0) break;
            int advance = 8 + size + (size & 1);
            if (advance <= 0) break;
            pos += advance;
        }
        return count > 0 ? count : 1;
    }

    // ── Frame encoding ────────────────────────────────────────────────────────

    private static byte[] tryEncode(List<Bitmap> frames, int durationMs, int quality) {
        try {
            // 1. Encode every frame to static WebP bytes.
            byte[][] frameData = new byte[frames.size()][];
            // Use lossless frame compression when available to preserve alpha fidelity.
            // Lossy WebP frame encoding on some Android devices can introduce opaque black
            // backgrounds when those frames are later repackaged into ANMF chunks.
            Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? Bitmap.CompressFormat.WEBP_LOSSLESS
                    : Bitmap.CompressFormat.WEBP;
            int effectiveQuality = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? 100 : Math.max(quality, 100);
            for (int i = 0; i < frames.size(); i++) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
                frames.get(i).compress(format, effectiveQuality, bos);
                frameData[i] = bos.toByteArray();
            }

            // 2. Assemble RIFF container
            byte[] out = buildRiff(frameData, durationMs, frames.get(0).getWidth(), frames.get(0).getHeight());
            return out;
        } catch (Exception e) {
            Log.d(TAG, "tryEncode: exception", e);
            return null;
        }
    }

    // ── RIFF container assembly ───────────────────────────────────────────────

    /**
     * Builds a complete animated WebP RIFF file from pre-encoded static WebP frame bytes.
     */
    private static byte[] buildRiff(byte[][] frameData, int durationMs, int width, int height)
            throws IOException {
        // Collect ANMF chunks first so we know the total size
        byte[][] anmfChunks = new byte[frameData.length][];
        for (int i = 0; i < frameData.length; i++) {
            anmfChunks[i] = buildAnmf(frameData[i], durationMs, width, height);
        }

        byte[] vp8x = buildVp8x(width, height);
        byte[] anim = buildAnim();

        // Total WEBP payload = VP8X chunk + ANIM chunk + all ANMF chunks
        int payloadSize = vp8x.length + anim.length;
        for (byte[] anmf : anmfChunks) payloadSize += anmf.length;

        // RIFF header: "RIFF" + 4-byte size (payloadSize + 4 for "WEBP") + "WEBP"
        ByteArrayOutputStream out = new ByteArrayOutputStream(payloadSize + 12);
        putFourCC(out, "RIFF");
        putLE32(out, payloadSize + 4); // +4 for the "WEBP" fourcc itself
        putFourCC(out, "WEBP");
        out.write(vp8x);
        out.write(anim);
        for (byte[] anmf : anmfChunks) out.write(anmf);

        // Patch the RIFF size field with the real total
        byte[] result = out.toByteArray();
        int riffSize = result.length - 8; // exclude "RIFF" + size fields
        result[4] = (byte) (riffSize);
        result[5] = (byte) (riffSize >> 8);
        result[6] = (byte) (riffSize >> 16);
        result[7] = (byte) (riffSize >> 24);
        return result;
    }

    /**
     * VP8X chunk: signals extended format with animation flag set.
     * https://developers.google.com/speed/webp/docs/riff_container#extended_file_format
     */
    private static byte[] buildVp8x(int width, int height) throws IOException {
        // chunk data = flags(1) + reserved(3) + canvas_width_minus_one(3) + canvas_height_minus_one(3)
        //            = 10 bytes
        ByteArrayOutputStream out = new ByteArrayOutputStream(18);
        putFourCC(out, "VP8X");
        putLE32(out, 10);              // chunk size
        out.write(0x12);              // flags: alpha + animation
        out.write(0); out.write(0); out.write(0); // reserved
        putLE24(out, width - 1);       // canvas width − 1
        putLE24(out, height - 1);      // canvas height − 1
        return out.toByteArray();
    }

    /**
     * ANIM chunk: global animation parameters.
     */
    private static byte[] buildAnim() throws IOException {
        // chunk data = background_colour(4) + loop_count(2) = 6 bytes
        ByteArrayOutputStream out = new ByteArrayOutputStream(14);
        putFourCC(out, "ANIM");
        putLE32(out, 6);          // chunk size
        putLE32(out, 0x00000000); // background colour = transparent black
        putLE16(out, 0);          // loop_count = 0 (infinite)
        return out.toByteArray();
    }

    /**
     * ANMF chunk: wraps a single static WebP frame.
     */
    private static byte[] buildAnmf(byte[] frameWebpBytes, int durationMs,
                                    int canvasWidth, int canvasHeight) throws IOException {
        // Strip the outer RIFF/WEBP header from the static WebP bytes to get the inner VP8/VP8L chunk.
        byte[] innerChunk = extractInnerChunk(frameWebpBytes);

        // ANMF data = frame_x(3)+frame_y(3)+frame_width_minus_one(3)+frame_height_minus_one(3)
        //           + frame_duration(3)+flags(1)+frame_data
        int anmfDataSize = 3 + 3 + 3 + 3 + 3 + 1 + innerChunk.length;
        // pad to even size
        int paddedDataSize = (anmfDataSize + 1) & ~1;

        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + paddedDataSize);
        putFourCC(out, "ANMF");
        putLE32(out, anmfDataSize);    // chunk size (before padding)
        putLE24(out, 0);               // frame_x / 2 = 0
        putLE24(out, 0);               // frame_y / 2 = 0
        putLE24(out, canvasWidth - 1); // frame_width − 1
        putLE24(out, canvasHeight - 1);// frame_height − 1
        putLE24(out, durationMs);      // frame_duration in ms
        out.write(0x00);               // flags: matches WhatsApp sample (default blend/dispose)
        out.write(innerChunk);
        if ((anmfDataSize & 1) != 0) out.write(0); // padding byte
        return out.toByteArray();
    }

    /**
     * Extracts only valid frame bitstream chunks for ANMF payload:
     *  - "VP8 "
     *  - "VP8L"
     *  - optional "ALPH" immediately followed by "VP8 "
     *
     * Copying every chunk after the RIFF header can include unsupported chunks (for ANMF)
     * on some encoders/devices, which can lead to blank frames in WhatsApp.
     */
    private static byte[] extractInnerChunk(byte[] webpBytes) throws IOException {
        if (webpBytes.length < 20) throw new IOException("WebP bytes too short");
        if (webpBytes[0] != 'R' || webpBytes[1] != 'I' || webpBytes[2] != 'F' || webpBytes[3] != 'F') {
            throw new IOException("Invalid WebP RIFF header");
        }
        if (webpBytes[8] != 'W' || webpBytes[9] != 'E' || webpBytes[10] != 'B' || webpBytes[11] != 'P') {
            throw new IOException("Invalid WEBP signature");
        }

        int pos = 12;
        byte[] pendingAlph = null;

        while (pos + 8 <= webpBytes.length) {
            String fourcc = new String(webpBytes, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = readLE32(webpBytes, pos + 4);

            if (size < 0 || pos + 8 + size > webpBytes.length) {
                break;
            }

            int paddedSize = size + (size & 1);
            int chunkTotal = 8 + paddedSize;

            if ("VP8X".equals(fourcc)) {
                // Extended header chunk; keep scanning for the actual frame chunk.
            } else if ("ALPH".equals(fourcc)) {
                pendingAlph = new byte[chunkTotal];
                System.arraycopy(webpBytes, pos, pendingAlph, 0, chunkTotal);
            } else if ("VP8 ".equals(fourcc) || "VP8L".equals(fourcc)) {
                if (pendingAlph != null && "VP8 ".equals(fourcc)) {
                    byte[] out = new byte[pendingAlph.length + chunkTotal];
                    System.arraycopy(pendingAlph, 0, out, 0, pendingAlph.length);
                    System.arraycopy(webpBytes, pos, out, pendingAlph.length, chunkTotal);
                    return out;
                }
                byte[] out = new byte[chunkTotal];
                System.arraycopy(webpBytes, pos, out, 0, chunkTotal);
                return out;
            }

            pos += chunkTotal;
        }

        throw new IOException("No VP8/VP8L frame chunk found");
    }

    private static int readLE32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    // ── Utility writers ───────────────────────────────────────────────────────

    private static void putFourCC(ByteArrayOutputStream out, String fourcc) throws IOException {
        out.write(fourcc.charAt(0));
        out.write(fourcc.charAt(1));
        out.write(fourcc.charAt(2));
        out.write(fourcc.charAt(3));
    }

    private static void putLE32(ByteArrayOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void putLE24(ByteArrayOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
    }

    private static void putLE16(ByteArrayOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    // ── Frame decimation ──────────────────────────────────────────────────────

    private static List<Bitmap> decimate(List<Bitmap> frames, int step) {
        if (step <= 1) return frames;
        java.util.List<Bitmap> result = new java.util.ArrayList<>();
        for (int i = 0; i < frames.size(); i += step) result.add(frames.get(i));
        return result;
    }

    private static List<Bitmap> scaleFramesIfNeeded(List<Bitmap> frames, float scale) {
        if (scale >= 0.999f) {
            return frames;
        }
        java.util.List<Bitmap> scaled = new java.util.ArrayList<>(frames.size());
        for (Bitmap f : frames) {
            int w = Math.max(64, Math.round(f.getWidth() * scale));
            int h = Math.max(64, Math.round(f.getHeight() * scale));
            if (w == f.getWidth() && h == f.getHeight()) {
                scaled.add(f);
            } else {
                scaled.add(Bitmap.createScaledBitmap(f, w, h, true));
            }
        }
        return scaled;
    }

    private static void recycleScaledFrames(List<Bitmap> maybeScaled, List<Bitmap> original) {
        if (maybeScaled == original) return;
        for (Bitmap bmp : maybeScaled) {
            if (bmp == null || bmp.isRecycled()) continue;
            if (!original.contains(bmp)) {
                bmp.recycle();
            }
        }
    }
}
