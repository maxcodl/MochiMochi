package com.kawai.mochi;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
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

import java.util.ArrayList;
import java.util.List;

public class EditStickerAdapter extends RecyclerView.Adapter<EditStickerAdapter.ViewHolder> {

    public static class StickerItem {
        public String packIdentifier;
        public String fileName;
        public Uri newUri;
        public List<String> emojis;
        public boolean isAnimated;

        public StickerItem(String packIdentifier, String fileName, List<String> emojis) {
            this.packIdentifier = packIdentifier;
            this.fileName = fileName;
            this.emojis = emojis != null ? new ArrayList<>(emojis) : new ArrayList<>();
        }

        public StickerItem(Uri newUri) {
            this.newUri = newUri;
            this.emojis = new ArrayList<>();
        }
    }

    public interface OnStickerActionListener {
        void onRemoveClicked(int position);
        void onStickerClicked(int position, View stickerView);
    }

    private final List<StickerItem> items;
    private final OnStickerActionListener listener;

    public EditStickerAdapter(List<StickerItem> items, OnStickerActionListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.sticker_edit_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        StickerItem item = items.get(position);
        Context context = holder.itemView.getContext();

        Uri uri = null;
        if (item.newUri != null) {
            uri = item.newUri;
        } else if (item.packIdentifier != null && item.fileName != null) {
            uri = StickerPackLoader.getStickerAssetUri(item.packIdentifier, item.fileName);
        }

        if (uri != null) {
            int size = holder.stickerImage.getWidth();
            if (size <= 0) {
                size = (int) (96 * holder.itemView.getContext().getResources().getDisplayMetrics().density);
            }
            // Animated stickers decode at 75% of display size to reduce per-frame memory
            int renderSize = item.isAnimated ? (int) (size * 0.40f) : size;
            ImageRequest request = ImageRequestBuilder.newBuilderWithSource(uri)
                    .setResizeOptions(new ResizeOptions(renderSize, renderSize))
                    .build();
            DraweeController controller = Fresco.newDraweeControllerBuilder()
                    .setImageRequest(request)
                    .setAutoPlayAnimations(true)
                    .setOldController(holder.stickerImage.getController())
                    .build();
            holder.stickerImage.setController(controller);
        }

        // Show emojis
        if (item.emojis != null && !item.emojis.isEmpty()) {
            StringBuilder emojiStr = new StringBuilder();
            for (String e : item.emojis) emojiStr.append(e);
            holder.emojisText.setText(emojiStr.toString());
            holder.emojisText.setVisibility(View.VISIBLE);
        } else {
            holder.emojisText.setVisibility(View.GONE);
        }

        holder.removeButton.setOnClickListener(v -> {
            if (listener != null) listener.onRemoveClicked(position);
        });

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onStickerClicked(position, v);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        SimpleDraweeView stickerImage;
        ImageButton removeButton;
        TextView emojisText;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            stickerImage = itemView.findViewById(R.id.sticker_image);
            removeButton = itemView.findViewById(R.id.remove_button);
            emojisText = itemView.findViewById(R.id.sticker_emojis_text);
        }
    }
}
