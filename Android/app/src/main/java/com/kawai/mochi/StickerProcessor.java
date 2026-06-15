package com.kawai.mochi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class StickerProcessor {
    private static final String TAG = "StickerProcessor";
    public static final int STICKER_SIZE = 512;
    public static final int TRAY_SIZE = 96;
    public static final int THUMB_SIZE = 32;
    private static final long MAX_IMPORT_BYTES = 40L * 1024L * 1024L;

    public static void processStaticSticker(Context context, Uri uri, File destFile) throws IOException {
        Bitmap bitmap = decodeAndResize(context, uri, STICKER_SIZE);
        saveAsWebP(bitmap, destFile, 80);
        int quality = 70;
        while (destFile.length() > 100 * 1024 && quality > 10) {
            saveAsWebP(bitmap, destFile, quality);
            quality -= 10;
        }
        bitmap.recycle();
    }

    public static void processStaticSticker(Context context, Uri sourceUri, Uri destUri) throws IOException {
        Bitmap bitmap = decodeAndResize(context, sourceUri, STICKER_SIZE);
        saveAsWebP(context, bitmap, destUri, 80);
        bitmap.recycle();
    }

    public static void processStaticSticker(File sourceFile, File destFile) throws IOException {
        Bitmap bitmap = decodeAndResize(sourceFile, STICKER_SIZE);
        saveAsWebP(bitmap, destFile, 80);
        int quality = 70;
        while (destFile.length() > 100 * 1024 && quality > 10) {
            saveAsWebP(bitmap, destFile, quality);
            quality -= 10;
        }
        bitmap.recycle();
    }

    public static void processTrayIcon(Context context, Uri uri, File destFile) throws IOException {
        Bitmap bitmap = decodeAndResize(context, uri, TRAY_SIZE);
        if (destFile.getName().toLowerCase().endsWith(".png")) {
            saveAsPng(bitmap, destFile);
        } else {
            saveAsWebP(bitmap, destFile, 80);
        }
        bitmap.recycle();
    }

    public static void processTrayIcon(Context context, Uri sourceUri, Uri destUri) throws IOException {
        Bitmap bitmap = decodeAndResize(context, sourceUri, TRAY_SIZE);
        String name = destUri.getLastPathSegment();
        if (name != null && name.toLowerCase().endsWith(".png")) {
            saveAsPng(context, bitmap, destUri);
        } else {
            saveAsWebP(context, bitmap, destUri, 80);
        }
        bitmap.recycle();
    }

    public static void processTrayIcon(File sourceFile, File destFile) throws IOException {
        Bitmap bitmap = decodeAndResize(sourceFile, TRAY_SIZE);
        if (destFile.getName().toLowerCase().endsWith(".png")) {
            saveAsPng(bitmap, destFile);
        } else {
            saveAsWebP(bitmap, destFile, 80);
        }
        bitmap.recycle();
    }

    private static Bitmap decodeAndResize(Context context, Uri uri, int targetSize) throws IOException {
        try (android.content.res.AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (afd != null) {
                long length = afd.getLength();
                if (length > 0 && length > MAX_IMPORT_BYTES) {
                    throw new IOException("Image too large: " + length + " bytes");
                }
            }
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;

        // Pass 1: decode bounds only.
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IOException("Failed to open input stream for URI: " + uri);
            BitmapFactory.decodeStream(is, null, opts);
        }

        // Pass 2: decode at reduced resolution.
        opts.inSampleSize = calculateInSampleSize(opts, targetSize, targetSize);
        opts.inJustDecodeBounds = false;
        Bitmap source;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IOException("Failed to open input stream for URI: " + uri);
            source = BitmapFactory.decodeStream(is, null, opts);
        }

        if (source == null) throw new IOException("Failed to decode image from URI");
        return transform(source, targetSize);
    }

    private static Bitmap decodeAndResize(File file, int targetSize) throws IOException {
        // Pass 1: decode bounds only.
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        // Pass 2: decode at reduced resolution.
        opts.inSampleSize = calculateInSampleSize(opts, targetSize, targetSize);
        opts.inJustDecodeBounds = false;
        Bitmap source = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (source == null) throw new IOException("Failed to decode image from file: " + file.getName());
        return transform(source, targetSize);
    }

    private static Bitmap transform(Bitmap source, int targetSize) {
        Bitmap result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
        result.eraseColor(android.graphics.Color.TRANSPARENT);
        Canvas canvas = new Canvas(result);
        float scale = Math.min((float) targetSize / source.getWidth(), (float) targetSize / source.getHeight());
        float dx = (targetSize - source.getWidth() * scale) * 0.5f;
        float dy = (targetSize - source.getHeight() * scale) * 0.5f;
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        matrix.postTranslate(dx, dy);
        canvas.drawBitmap(source, matrix, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    private static void saveAsWebP(Bitmap bitmap, File file, int quality) throws IOException {
        File tempFile = new File(file.getParent(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tempFile)) {
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out);
        }
        if (file.exists()) file.delete();
        if (!tempFile.renameTo(file)) throw new IOException("Rename failed");
    }

    private static void saveAsWebP(Context context, Bitmap bitmap, Uri uri, int quality) throws IOException {
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("Failed to open output stream");
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out);
        }
    }

    public static void saveAsPng(Bitmap bitmap, File file) throws IOException {
        File tempFile = new File(file.getParent(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tempFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        if (file.exists()) file.delete();
        if (!tempFile.renameTo(file)) throw new IOException("Rename failed");
    }

    private static void saveAsPng(Context context, Bitmap bitmap, Uri uri) throws IOException {
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("Failed to open output stream");
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    public static void createThumbnail(File sourceFile, File destFile) throws IOException {
        Bitmap thumb = decodeAndResize(sourceFile, THUMB_SIZE);
        if (thumb != null) {
            saveAsWebP(thumb, destFile, 60);
            thumb.recycle();
        }
    }

    public static void createThumbnail(Context context, Uri sourceUri, Uri destUri) throws IOException {
        Bitmap thumb = decodeAndResize(context, sourceUri, THUMB_SIZE);
        if (thumb != null) {
            saveAsWebP(context, thumb, destUri, 60);
            thumb.recycle();
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    public static boolean stripWebPMetadata(File file) {
        try {
            byte[] data = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                if (fis.read(data) != data.length) return false;
            }
            return stripWebPMetadata(data, file);
        } catch (Exception e) { Log.e(TAG, "Strip failed", e); }
        return false;
    }

    public static boolean stripWebPMetadata(Context context, Uri uri) {
        try {
            byte[] data;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) return false;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
                data = bos.toByteArray();
            }
            byte[] stripped = stripWebPMetadataBytes(data);
            if (stripped != null) {
                try (OutputStream os = context.getContentResolver().openOutputStream(uri, "wt")) {
                    if (os == null) return false;
                    os.write(stripped);
                }
                return true;
            }
        } catch (Exception e) { Log.e(TAG, "SAF strip failed", e); }
        return false;
    }

    private static boolean stripWebPMetadata(byte[] data, File file) throws IOException {
        byte[] stripped = stripWebPMetadataBytes(data);
        if (stripped != null) {
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(stripped);
            }
            return true;
        }
        return false;
    }

    private static byte[] stripWebPMetadataBytes(byte[] data) {
        if (data.length < 12 || data[0] != 'R' || data[8] != 'W') return null;
        ByteBuffer input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer output = ByteBuffer.allocate(data.length).order(ByteOrder.LITTLE_ENDIAN);
        output.put(data, 0, 12);
        int offset = 12;
        boolean modified = false;
        int vp8xPos = -1;
        while (offset + 8 <= data.length) {
            input.position(offset);
            byte[] chunkId = new byte[4];
            input.get(chunkId);
            int chunkSize = input.getInt();
            int paddedSize = (chunkSize + 1) & ~1;
            String type = new String(chunkId);
            if ("VP8X".equals(type)) vp8xPos = output.position();
            if ("EXIF".equals(type) || "XMP ".equals(type) || "ICCP".equals(type)) {
                modified = true;
            } else {
                if (offset + 8 + paddedSize > data.length) break;
                output.put(chunkId);
                output.putInt(chunkSize);
                output.put(data, offset + 8, paddedSize);
            }
            offset += 8 + paddedSize;
        }
        if (modified) {
            int finalSize = output.position();
            output.putInt(4, finalSize - 8);
            if (vp8xPos != -1) {
                int flags = output.get(vp8xPos + 8) & 0xFF;
                flags &= ~0x20; flags &= ~0x08; flags &= ~0x04;
                output.put(vp8xPos + 8, (byte) flags);
            }
            byte[] result = new byte[finalSize];
            System.arraycopy(output.array(), 0, result, 0, finalSize);
            return result;
        }
        return null;
    }
}
