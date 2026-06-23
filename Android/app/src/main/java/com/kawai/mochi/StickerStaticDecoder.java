package com.kawai.mochi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;

final class StickerStaticDecoder {
    private static final int MAX_DIMENSION_PX = 2048;
    private static final long MAX_PIXELS = (long) MAX_DIMENSION_PX * MAX_DIMENSION_PX;

    private StickerStaticDecoder() {
    }

    @NonNull
    static Bitmap decode(@NonNull Context context, @NonNull Uri uri, int reqWidth, int reqHeight) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        decodeInternal(context, uri, bounds);

        int width = bounds.outWidth;
        int height = bounds.outHeight;
        if (width <= 0 || height <= 0) {
            throw new IOException("Invalid image bounds");
        }
        if (width > MAX_DIMENSION_PX || height > MAX_DIMENSION_PX || (long) width * height > MAX_PIXELS) {
            throw new IOException("Sticker image too large");
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateSampleSize(width, height, reqWidth, reqHeight);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inDither = false;

        Bitmap decoded = decodeInternal(context, uri, options);
        if (decoded == null) {
            throw new IOException("Failed to decode image");
        }
        return decoded;
    }

    private static int calculateSampleSize(int width, int height, int reqWidth, int reqHeight) {
        int sampleSize = 1;
        while ((height / sampleSize) > reqHeight * 2 || (width / sampleSize) > reqWidth * 2) {
            sampleSize *= 2;
        }
        return Math.max(1, sampleSize);
    }

    private static Bitmap decodeInternal(@NonNull Context context, @NonNull Uri uri, @NonNull BitmapFactory.Options options) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Unable to open image stream");
            }
            return BitmapFactory.decodeStream(input, null, options);
        }
    }
}