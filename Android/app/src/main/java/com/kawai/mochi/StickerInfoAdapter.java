package com.kawai.mochi;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
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
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StickerInfoAdapter extends RecyclerView.Adapter<StickerInfoAdapter.ViewHolder> {

    private final Context context;
    private final List<Sticker> stickers;
    private final String packId;
    private final String folderPath;
    
    // PERFORMANCE: Background executor and cache for WebP info to prevent main-thread lag
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, WebPInfo> infoCache = new HashMap<>();

    public StickerInfoAdapter(Context context, List<Sticker> stickers, String packId) {
        this.context = context;
        this.stickers = stickers;
        this.packId = packId;
        this.folderPath = WastickerParser.getStickerFolderPath(context);
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
        File stickerFile = new File(new File(folderPath, packId), sticker.imageFileName);

        // PERFORMANCE: Resize animation to match the 64dp display size
        final int previewSize = context.getResources().getDimensionPixelSize(R.dimen.sticker_info_preview_size);
        Uri contentUri = StickerPackLoader.getStickerAssetUri(packId, sticker.imageFileName);
        // Use cached info if available; animated stickers decode at 75% of display size
        WebPInfo cached = infoCache.get(stickerFile.getAbsolutePath());
        int baseSize = previewSize > 0 ? previewSize : 128;
        int infoRenderSize = (cached != null && cached.isAnimated) ? (int) (baseSize * 0.40f) : baseSize;
        
        ImageRequest request = ImageRequestBuilder.newBuilderWithSource(contentUri)
                .setResizeOptions(new ResizeOptions(infoRenderSize, infoRenderSize))
                .build();

        DraweeController controller = Fresco.newDraweeControllerBuilder()
                .setImageRequest(request)
                .setAutoPlayAnimations(true) // Animations enabled at lower scale
                .setOldController(holder.preview.getController())
                .build();
        holder.preview.setController(controller);

        holder.name.setText(sticker.imageFileName);
        long fileSize = stickerFile.exists() ? stickerFile.length() : 0;
        holder.size.setText(Formatter.formatShortFileSize(context, fileSize));

        // Reset fields that will be populated asynchronously
        holder.dimens.setVisibility(View.GONE);
        holder.type.setText("…");
        holder.color.setText("…");
        holder.frames.setVisibility(View.GONE);

        // PERFORMANCE: Load detailed WebP info in background
        final String cacheKey = stickerFile.getAbsolutePath();
        if (infoCache.containsKey(cacheKey)) {
            updateUIWithInfo(holder, infoCache.get(cacheKey));
        } else {
            executor.execute(() -> {
                WebPInfo info = readWebPInfo(stickerFile);
                infoCache.put(cacheKey, info);
                mainHandler.post(() -> {
                    // Check if holder is still showing the same sticker
                    int currentPos = holder.getAdapterPosition();
                    if (currentPos != RecyclerView.NO_POSITION && stickers.get(currentPos).imageFileName.equals(sticker.imageFileName)) {
                        updateUIWithInfo(holder, info);
                    }
                });
            });
        }
    }

    private void updateUIWithInfo(ViewHolder holder, WebPInfo info) {
        holder.type.setText(info.isAnimated ? context.getString(R.string.animated) : (info.isLossless ? context.getString(R.string.lossless) : context.getString(R.string.lossy)));
        
        if (info.width > 0 && info.height > 0) {
            holder.dimens.setText(context.getString(R.string.sticker_info_dimens_format, info.width, info.height));
            holder.dimens.setVisibility(View.VISIBLE);
        }

        StringBuilder colorBuilder = new StringBuilder();
        colorBuilder.append(info.hasAlpha ? "RGBA" : "RGB");
        if (info.hasExif) colorBuilder.append(" + EXIF");
        if (info.hasXmp) colorBuilder.append(" + XMP");
        if (info.hasIcc) colorBuilder.append(" + ICC");
        holder.color.setText(colorBuilder.toString());

        StringBuilder infoBuilder = new StringBuilder();
        if (info.isAnimated && info.frameCount > 1) {
            infoBuilder.append(context.getString(R.string.sticker_info_frames_format, info.frameCount));
            if (info.fps > 0) {
                infoBuilder.append("  ");
                infoBuilder.append(context.getString(R.string.sticker_info_fps_format, info.fps));
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
        public boolean isAnimated = false;
        public boolean isLossless = false;
        public boolean hasAlpha = false;
        public boolean hasExif = false;
        public boolean hasXmp = false;
        public boolean hasIcc = false;
        public int frameCount = 1;
        public int fps = 0;
        public int width = 0;
        public int height = 0;
    }

    public static WebPInfo readWebPInfo(File file) {
        WebPInfo info = new WebPInfo();
        if (!file.exists()) return info;

        // Use BitmapFactory for dimensions - efficient enough
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        info.width = options.outWidth;
        info.height = options.outHeight;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[30];
            if (fis.read(header) < 30) return info;

            if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F' ||
                header[8] != 'W' || header[9] != 'E' || header[10] != 'B' || header[11] != 'P') {
                return info;
            }

            String chunkType = new String(header, 12, 4);
            if ("VP8X".equals(chunkType)) {
                byte flags = header[20];
                info.hasIcc = (flags & 0x20) != 0;
                info.hasAlpha = (flags & 0x10) != 0;
                info.hasExif = (flags & 0x08) != 0;
                info.hasXmp = (flags & 0x04) != 0;
                info.isAnimated = (flags & 0x02) != 0;
            } else if ("VP8L".equals(chunkType)) {
                info.isLossless = true;
                info.hasAlpha = (header[21] & 0x10) != 0;
            }

            if (info.isAnimated) {
                try (FileInputStream fisFrames = new FileInputStream(file)) {
                    fisFrames.skip(12);
                    int frameCount = 0;
                    long totalDurationMs = 0;
                    byte[] buf = new byte[8];
                    while (fisFrames.read(buf) == 8) {
                        String type = new String(buf, 0, 4);
                        int size = (buf[4] & 0xFF) | ((buf[5] & 0xFF) << 8) | ((buf[6] & 0xFF) << 16) | ((buf[7] & 0xFF) << 24);
                        if (size < 0) break;

                        if ("ANMF".equals(type)) {
                            byte[] frameData = new byte[16];
                            if (fisFrames.read(frameData) == 16) {
                                int duration = (frameData[12] & 0xFF) | ((frameData[13] & 0xFF) << 8) | ((frameData[14] & 0xFF) << 16);
                                totalDurationMs += duration;
                                frameCount++;
                                fisFrames.skip(size - 16);
                            }
                        } else {
                            fisFrames.skip(size);
                        }
                        if ((size & 1) == 1) fisFrames.skip(1);
                    }
                    info.frameCount = frameCount;
                    if (frameCount > 1 && totalDurationMs > 0) {
                        info.fps = (int) Math.round(frameCount * 1000.0 / totalDurationMs);
                    }
                }
            }
        } catch (IOException ignored) {}
        return info;
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
