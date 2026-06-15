package com.kawai.mochi;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class WebPMetadataReader {
    private static final String TAG = "WebPMetadataReader";

    public static class WebPInfo {
        public boolean isAnimated  = false;
        public boolean isLossless  = false;
        public boolean hasAlpha    = false;
        public boolean hasExif     = false;
        public boolean hasXmp      = false;
        public boolean hasIcc      = false;
        public int     frameCount  = 1;
        public int     fps         = 0;
        public long    durationMs  = 0;
        public int     width       = 0;
        public int     height      = 0;
        public int     bitDepth    = 0;
        public long    fileSize    = 0;
    }

    public static WebPInfo read(Context context, Uri uri) {
        WebPInfo info = new WebPInfo();
        try {
            // Get file size
            try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd != null) info.fileSize = pfd.getStatSize();
            }

            // Dimensions via BitmapFactory (header scan)
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream dimStream = context.getContentResolver().openInputStream(uri)) {
                if (dimStream != null) BitmapFactory.decodeStream(dimStream, null, opts);
            }
            info.width = opts.outWidth;
            info.height = opts.outHeight;

            // RIFF header parsing
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is != null) parseHeader(is, info);
            }

            // Animation frame scan if header says it's animated
            if (info.isAnimated) {
                try (InputStream fs = context.getContentResolver().openInputStream(uri)) {
                    if (fs != null) parseAnimFrames(fs, info);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read WebP metadata for " + uri, e);
        }
        return info;
    }

    public static WebPInfo read(File file) {
        WebPInfo info = new WebPInfo();
        if (!file.exists()) return info;
        info.fileSize = file.length();

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        info.width = opts.outWidth;
        info.height = opts.outHeight;

        try (FileInputStream fis = new FileInputStream(file)) {
            parseHeader(fis, info);
        } catch (IOException ignored) {}

        if (info.isAnimated) {
            try (FileInputStream fis2 = new FileInputStream(file)) {
                parseAnimFrames(fis2, info);
            } catch (IOException ignored) {}
        }
        return info;
    }

    private static void parseHeader(InputStream is, WebPInfo info) throws IOException {
        byte[] header = new byte[30];
        if (readFully(is, header, 30) < 30) return;

        // RIFF header validation
        if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F' ||
            header[8] != 'W' || header[9] != 'E' || header[10] != 'B' || header[11] != 'P') {
            return;
        }

        String firstChunk = new String(header, 12, 4);
        switch (firstChunk) {
            case "VP8X":
                byte flags = header[20];
                info.hasIcc     = (flags & 0x20) != 0;
                info.hasAlpha   = (flags & 0x10) != 0;
                info.hasExif    = (flags & 0x08) != 0;
                info.hasXmp     = (flags & 0x04) != 0;
                info.isAnimated = (flags & 0x02) != 0;
                info.bitDepth   = 8;
                break;
            case "VP8L":
                info.isLossless = true;
                info.hasAlpha   = (header[21] & 0x10) != 0;
                info.bitDepth   = 8;
                break;
            case "VP8 ":
                info.bitDepth   = 8;
                break;
        }
    }

    private static void parseAnimFrames(InputStream is, WebPInfo info) throws IOException {
        if (is.skip(12) < 12) return;
        int frameCount = 0;
        long totalDurMs = 0;
        byte[] buf = new byte[8];
        while (readFully(is, buf, 8) == 8) {
            String type = new String(buf, 0, 4);
            int size = (buf[4] & 0xFF) | ((buf[5] & 0xFF) << 8) | ((buf[6] & 0xFF) << 16) | ((buf[7] & 0xFF) << 24);
            if (size < 0) break;
            if ("ANMF".equals(type)) {
                byte[] anmf = new byte[Math.min(16, size)];
                int read = readFully(is, anmf, anmf.length);
                if (read >= 16) {
                    int dur = (anmf[12] & 0xFF) | ((anmf[13] & 0xFF) << 8) | ((anmf[14] & 0xFF) << 16);
                    totalDurMs += dur;
                    frameCount++;
                    long skip = (long) size - read;
                    if (skip > 0) is.skip(skip);
                } else is.skip(Math.max(0, (long) size - read));
            } else is.skip(size);
            if ((size & 1) == 1) is.skip(1);
        }
        info.frameCount = Math.max(frameCount, 1);
        info.durationMs = totalDurMs;
        if (frameCount > 1 && totalDurMs > 0) {
            info.fps = (int) Math.round(frameCount * 1000.0 / totalDurMs);
        }
    }

    private static int readFully(InputStream is, byte[] buf, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int r = is.read(buf, total, len - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }
}
