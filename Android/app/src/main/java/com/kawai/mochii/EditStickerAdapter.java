package com.kawai.mochii;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.facebook.drawee.view.SimpleDraweeView;

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
            this.emojis = emojis != null ? emojis : new ArrayList<>();
        }

        public StickerItem(Uri newUri) {
            this.newUri = newUri;
            this.emojis = new ArrayList<>();
        }
    }

    public interface OnStickerActionListener {
        void onRemoveClicked(int position);
        void onStickerClicked(int position);
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

        if (item.newUri != null) {
            holder.stickerImage.setImageURI(item.newUri);
        } else if (item.packIdentifier != null && item.fileName != null) {
            holder.stickerImage.setImageURI(
                    StickerPackLoader.getStickerAssetUri(item.packIdentifier, item.fileName));
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
            if (listener != null) listener.onStickerClicked(position);
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
