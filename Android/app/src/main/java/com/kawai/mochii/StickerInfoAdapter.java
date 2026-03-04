package com.kawai.mochii;

import android.content.Context;
import android.net.Uri;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.facebook.drawee.view.SimpleDraweeView;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

public class StickerInfoAdapter extends RecyclerView.Adapter<StickerInfoAdapter.ViewHolder> {

    private final Context context;
    private final List<Sticker> stickers;
    private final String packId;
    private final String folderPath;

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

        // Preview via Fresco (handles both static and animated WebP)
        Uri contentUri = StickerPackLoader.getStickerAssetUri(packId, sticker.imageFileName);
        holder.preview.setImageURI(contentUri);

        // Name (strip extension)
        String name = sticker.imageFileName;
        if (name.contains(".")) name = name.substring(0, name.lastIndexOf('.'));
        holder.name.setText(name);

        // File size
        long fileSize = stickerFile.exists() ? stickerFile.length() : 0;
        holder.size.setText(Formatter.formatShortFileSize(context, fileSize));

        // Per-sticker animated detection via actual file inspection
        boolean isAnimated = WastickerParser.isAnimatedWebPPublic(context, packId, sticker.imageFileName);
        holder.type.setText(isAnimated ? "ANIMATED" : "STATIC");

        // Frame info for animated stickers
        if (isAnimated && stickerFile.exists()) {
            int[] frameInfo = readWebPFrameInfo(stickerFile);
            int frameCount = frameInfo[0];
            int fps = frameInfo[1];
            if (frameCount > 1) {
                String frameText = frameCount + " frames";
                if (fps > 0) frameText += " · " + fps + " fps";
                holder.frames.setText(frameText);
                holder.frames.setVisibility(View.VISIBLE);
            } else {
                holder.frames.setVisibility(View.GONE);
            }
        } else {
            holder.frames.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return stickers != null ? stickers.size() : 0;
    }

    /**
     * Parses the RIFF/WEBP binary to extract frame count and average FPS.
     * Returns int[]{frameCount, fps}. Returns {1, 0} for static images or on error.
     */
    static int[] readWebPFrameInfo(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf4 = new byte[4];

            // Verify "RIFF" header
            if (fis.read(buf4) < 4 || buf4[0] != 'R' || buf4[1] != 'I' || buf4[2] != 'F' || buf4[3] != 'F')
                return new int[]{1, 0};
            fis.skip(4); // file size field

            // Verify "WEBP" format
            if (fis.read(buf4) < 4 || buf4[0] != 'W' || buf4[1] != 'E' || buf4[2] != 'B' || buf4[3] != 'P')
                return new int[]{1, 0};

            int frameCount = 0;
            long totalDurationMs = 0;

            // Walk chunks
            while (true) {
                // Chunk type (4 bytes)
                if (fis.read(buf4) < 4) break;
                String type = new String(buf4, "ASCII");

                // Chunk size (4 bytes, little-endian)
                if (fis.read(buf4) < 4) break;
                int chunkSize = (buf4[0] & 0xFF) | ((buf4[1] & 0xFF) << 8)
                        | ((buf4[2] & 0xFF) << 16) | ((buf4[3] & 0xFF) << 24);
                if (chunkSize < 0) break;

                if ("ANMF".equals(type) && chunkSize >= 16) {
                    // ANMF frame data layout:
                    //  0-2  Frame X (24-bit LE)
                    //  3-5  Frame Y
                    //  6-8  Width  - 1
                    //  9-11 Height - 1
                    // 12-14 Duration (ms, 24-bit LE)
                    // 15    Flags
                    byte[] frame = new byte[16];
                    if (fis.read(frame) < 16) break;
                    int durationMs = (frame[12] & 0xFF) | ((frame[13] & 0xFF) << 8) | ((frame[14] & 0xFF) << 16);
                    totalDurationMs += durationMs;
                    frameCount++;
                    long remaining = chunkSize - 16L;
                    if (remaining > 0) fis.skip(remaining);
                } else {
                    fis.skip(chunkSize);
                }
                // Chunks are padded to even sizes
                if ((chunkSize & 1) == 1) fis.skip(1);
            }

            if (frameCount <= 1) return new int[]{frameCount, 0};
            int fps = totalDurationMs > 0
                    ? (int) Math.round(frameCount * 1000.0 / totalDurationMs)
                    : 0;
            return new int[]{frameCount, fps};

        } catch (Exception e) {
            return new int[]{1, 0};
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        SimpleDraweeView preview;
        TextView name, size, type, frames;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            preview = itemView.findViewById(R.id.sticker_info_preview);
            name    = itemView.findViewById(R.id.sticker_info_name);
            size    = itemView.findViewById(R.id.sticker_info_size);
            type    = itemView.findViewById(R.id.sticker_info_type);
            frames  = itemView.findViewById(R.id.sticker_info_frames);
        }
    }
}
