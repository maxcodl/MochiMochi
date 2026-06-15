package com.kawai.mochi;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import com.kawai.mochi.R;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StickerInfoAdapter extends RecyclerView.Adapter<StickerInfoAdapter.ViewHolder> {

    private final Context context;
    private final List<Sticker> stickers;
    private final String packId;
    private final boolean animationsEnabled;

    // Background executor and main-thread handler for async WebP info loading
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // Use a static LruCache to persist metadata across activity recreations, significantly reducing lag on second entry.
    private static final LruCache<String, WebPInfo> infoCache = new LruCache<>(250);

    public StickerInfoAdapter(Context context, List<Sticker> stickers, String packId) {
        this.context = context;
        this.stickers = stickers;
        this.packId = packId;
        this.animationsEnabled = SettingsActivity.isAnimationsEnabled(context);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_sticker_info, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Sticker sticker = stickers.get(position);

        // Use the content provider URI — works for both SAF and internal storage
        final Uri contentUri = StickerPackLoader.getStickerAssetUri(packId, sticker.imageFileName);
        final String cacheKey = packId + "/" + sticker.imageFileName;

        WebPInfo cached = infoCache.get(cacheKey);

        // Preview image setup
        final int previewSize = context.getResources().getDimensionPixelSize(R.dimen.sticker_info_preview_size);
        int baseSize = previewSize > 0 ? previewSize : 128;
        // Animated previews are downscaled further to save memory/cpu if animations are enabled
        int infoRenderSize = (cached != null && cached.isAnimated && animationsEnabled) ? (int) (baseSize * 0.50f) : baseSize;

        ImageRequest request = ImageRequestBuilder.newBuilderWithSource(contentUri)
                .setResizeOptions(new ResizeOptions(infoRenderSize, infoRenderSize))
                .build();
        
        DraweeController controller = Fresco.newDraweeControllerBuilder()
                .setImageRequest(request)
                .setAutoPlayAnimations(animationsEnabled)
                .setOldController(holder.preview.getController())
                .build();
        holder.preview.setController(controller);

        holder.name.setText(sticker.imageFileName);

        if (cached != null) {
            updateUIWithInfo(holder, cached);
        } else {
            // Initial placeholder state
            holder.size.setText("…");
            holder.dimens.setVisibility(View.GONE);
            holder.type.setText("…");
            holder.color.setText("…");
            holder.frames.setVisibility(View.GONE);

            WeakReference<ViewHolder> holderRef = new WeakReference<>(holder);
            executor.execute(() -> {
                // Combined task: metadata + file size
                WebPInfo info = readWebPInfo(context, contentUri, packId, sticker.imageFileName);
                if (info != null) {
                    infoCache.put(cacheKey, info);
                    mainHandler.post(() -> {
                        ViewHolder h = holderRef.get();
                        if (h != null) {
                            int currentPos = h.getAdapterPosition();
                            if (currentPos != RecyclerView.NO_POSITION
                                    && stickers.get(currentPos).imageFileName.equals(sticker.imageFileName)) {
                                updateUIWithInfo(h, info);
                            }
                        }
                    });
                }
            });
        }
    }

    private void updateUIWithInfo(ViewHolder holder, WebPInfo info) {
        holder.size.setText(Formatter.formatShortFileSize(context, info.fileSize));
        
        // Type label
        String typeLabel;
        if (info.isAnimated) {
            typeLabel = context.getString(R.string.animated);
        } else if (info.isLossless) {
            typeLabel = context.getString(R.string.lossless);
        } else {
            typeLabel = context.getString(R.string.lossy);
        }
        holder.type.setText(typeLabel);

        // Dimensions
        if (info.width > 0 && info.height > 0) {
            holder.dimens.setText(context.getString(R.string.sticker_info_dimens_format, info.width, info.height));
            holder.dimens.setVisibility(View.VISIBLE);
        } else {
            holder.dimens.setVisibility(View.GONE);
        }

        // Color space / metadata flags
        StringBuilder colorBuilder = new StringBuilder();
        colorBuilder.append(info.hasAlpha ? "RGBA" : "RGB");
        if (info.bitDepth > 0) colorBuilder.append("  ·  ").append(info.bitDepth).append("-bit");
        if (info.hasExif) colorBuilder.append("  +EXIF");
        if (info.hasXmp)  colorBuilder.append("  +XMP");
        if (info.hasIcc)  colorBuilder.append("  +ICC");
        holder.color.setText(colorBuilder.toString());

        // Frame / FPS details for animated
        if (info.isAnimated) {
            StringBuilder infoBuilder = new StringBuilder();
            infoBuilder.append(context.getString(R.string.sticker_info_frames_format, info.frameCount));
            if (info.fps > 0) {
                infoBuilder.append("  ·  ");
                infoBuilder.append(context.getString(R.string.sticker_info_fps_format, info.fps));
            }
            if (info.durationMs > 0) {
                infoBuilder.append("  ·  ").append(info.durationMs).append(" ms");
            }
            holder.frames.setText(infoBuilder.toString());
            holder.frames.setVisibility(View.VISIBLE);
        } else {
            holder.frames.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return stickers != null ? stickers.size() : 0;
    }

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

    /**
     * Reads WebP metadata and file size from a Uri.
     */
    public static WebPInfo readWebPInfo(Context context, Uri uri) {
        return readWebPInfo(context, uri, null, null);
    }

    /**
     * Reads WebP metadata and file size in a single go to minimize I/O overhead.
     */
    public static WebPInfo readWebPInfo(Context context, Uri uri, String packId, String fileName) {
        WebPInfo info = new WebPInfo();
        try {
            // Get file size
            if (packId != null && fileName != null) {
                try {
                    info.fileSize = StickerPackLoader.fetchStickerAssetLength(
                            packId, fileName, context.getContentResolver());
                } catch (Exception ignored) {}
            }

            if (info.fileSize <= 0) {
                try (android.content.res.AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                    if (afd != null) info.fileSize = afd.getLength();
                } catch (Exception ignored) {}
            }

            // --- Dimensions via BitmapFactory (no pixel decode) ---
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream dimStream = context.getContentResolver().openInputStream(uri)) {
                if (dimStream != null) BitmapFactory.decodeStream(dimStream, null, opts);
            }
            info.width  = opts.outWidth;
            info.height = opts.outHeight;

            // --- RIFF / WebP container parsing ---
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) return info;

                byte[] header = new byte[30];
                if (readFully(is, header, 30) < 30) return info;

                // Validate RIFF….WEBP magic
                if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F' ||
                    header[8] != 'W' || header[9] != 'E' || header[10] != 'B' || header[11] != 'P') {
                    return info;
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

                // --- Frame scan for animated WebP ---
                if (info.isAnimated) {
                    try (InputStream fs = context.getContentResolver().openInputStream(uri)) {
                        if (fs != null) parseAnimFrames(fs, info);
                    }
                }
            }
        } catch (IOException ignored) {}
        return info;
    }

    /**
     * Overload for File-based callers (e.g. diagnostics).
     */
    public static WebPInfo readWebPInfo(File file) {
        WebPInfo info = new WebPInfo();
        if (!file.exists()) return info;
        info.fileSize = file.length();

        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        info.width  = opts.outWidth;
        info.height = opts.outHeight;

        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] header = new byte[30];
            if (readFully(fis, header, 30) < 30) return info;
            if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F' ||
                header[8] != 'W' || header[9] != 'E' || header[10] != 'B' || header[11] != 'P') {
                return info;
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
                    info.bitDepth = 8;
                    break;
            }
        } catch (IOException ignored) {}

        if (info.isAnimated) {
            try (java.io.FileInputStream fis2 = new java.io.FileInputStream(file)) {
                parseAnimFrames(fis2, info);
            } catch (IOException ignored) {}
        }
        return info;
    }

    private static void parseAnimFrames(InputStream is, WebPInfo info) throws IOException {
        if (is.skip(12) < 12) return;
        int frameCount   = 0;
        long totalDurMs  = 0;
        byte[] buf       = new byte[8];
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
        if (frameCount > 1 && totalDurMs > 0) info.fps = (int) Math.round(frameCount * 1000.0 / totalDurMs);
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

    static class ViewHolder extends RecyclerView.ViewHolder {
        SimpleDraweeView preview;
        TextView name, size, type, frames, dimens, color;
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            preview = itemView.findViewById(R.id.sticker_info_preview);
            name    = itemView.findViewById(R.id.sticker_info_name);
            size    = itemView.findViewById(R.id.sticker_info_size);
            type    = itemView.findViewById(R.id.sticker_info_type);
            frames  = itemView.findViewById(R.id.sticker_info_frames);
            dimens  = itemView.findViewById(R.id.sticker_info_dimens);
            color   = itemView.findViewById(R.id.sticker_info_color);
        }
    }
}
