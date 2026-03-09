package com.kawai.mochi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class StickerProcessor {
    private static final String TAG = "StickerProcessor";
    public static final int STICKER_SIZE = 512;
    public static final int TRAY_SIZE = 96;

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

    public static void processTrayIcon(File sourceFile, File destFile) throws IOException {
        Bitmap bitmap = decodeAndResize(sourceFile, TRAY_SIZE);
        // WhatsApp requires PNG for tray icon in many versions, especially for stability
        if (destFile.getName().toLowerCase().endsWith(".png")) {
            saveAsPng(bitmap, destFile);
        } else {
            saveAsWebP(bitmap, destFile, 80);
        }
        bitmap.recycle();
    }

    private static Bitmap decodeAndResize(Context context, Uri uri, int targetSize) throws IOException {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            Bitmap source = BitmapFactory.decodeStream(is);
            if (source == null) throw new IOException("Failed to decode image from URI");
            return transform(source, targetSize);
        }
    }

    private static Bitmap decodeAndResize(File file, int targetSize) throws IOException {
        Bitmap source = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (source == null) throw new IOException("Failed to decode image from file: " + file.getName());
        return transform(source, targetSize);
    }

    private static Bitmap transform(Bitmap source, int targetSize) {
        Bitmap result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        float scale = Math.min((float) targetSize / source.getWidth(), (float) targetSize / source.getHeight());
        float dx = (targetSize - source.getWidth() * scale) * 0.5f;
        float dy = (targetSize - source.getHeight() * scale) * 0.5f;
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        matrix.postTranslate(dx, dy);
        canvas.drawBitmap(source, matrix, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        source.recycle();
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

    public static void saveAsPng(Bitmap bitmap, File file) throws IOException {
        File tempFile = new File(file.getParent(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tempFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        if (file.exists()) file.delete();
        if (!tempFile.renameTo(file)) throw new IOException("Rename failed");
    }

    public static boolean stripWebPMetadata(File file) {
        try {
            byte[] data = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                if (fis.read(data) != data.length) return false;
            }
            if (data.length < 12 || data[0] != 'R' || data[8] != 'W') return false;

            ByteBuffer input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer output = ByteBuffer.allocate(data.length).order(ByteOrder.LITTLE_ENDIAN);
            output.put(data, 0, 12); // RIFF header

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
                    flags &= ~0x20; // Clear ICC
                    flags &= ~0x08; // Clear EXIF
                    flags &= ~0x04; // Clear XMP
                    output.put(vp8xPos + 8, (byte) flags);
                }
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(output.array(), 0, finalSize);
                }
                return true;
            }
        } catch (Exception e) { Log.e(TAG, "Strip failed", e); }
        return false;
    }

    public static void createThumbnail(File sourceFile, File destFile) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(sourceFile.getAbsolutePath(), options);

        options.inSampleSize = calculateInSampleSize(options, 128, 128);
        options.inJustDecodeBounds = false;

        Bitmap thumb = BitmapFactory.decodeFile(sourceFile.getAbsolutePath(), options);
        if (thumb != null) {
            Bitmap result = transform(thumb, 128);
            saveAsWebP(result, destFile, 75);
            result.recycle();
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
}
