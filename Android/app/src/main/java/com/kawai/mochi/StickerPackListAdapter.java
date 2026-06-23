/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.kawai.mochi;

import android.content.Context;
import android.content.Intent;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.kawai.mochi.R;

import java.util.List;

import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.DiffUtil;

public class StickerPackListAdapter extends ListAdapter<StickerPack, StickerPackListItemViewHolder> {
    @NonNull
    private final OnAddButtonClickedListener onAddButtonClickedListener;
    private int maxNumberOfStickersInARow;
    private int minMarginBetweenImages;
    private boolean isScrolling;
    
    private final RecyclerView.RecycledViewPool sharedPool = new RecyclerView.RecycledViewPool();
    Boolean animationsEnabledCache = null;

    StickerPackListAdapter(@NonNull OnAddButtonClickedListener onAddButtonClickedListener) {
        super(new DiffUtil.ItemCallback<StickerPack>() {
            @Override
            public boolean areItemsTheSame(@NonNull StickerPack oldItem, @NonNull StickerPack newItem) {
                return oldItem.identifier.equals(newItem.identifier);
            }

            @Override
            public boolean areContentsTheSame(@NonNull StickerPack oldItem, @NonNull StickerPack newItem) {
                // If anything that affects UI changes, return false
                if (!oldItem.name.equals(newItem.name)) return false;
                if (!oldItem.publisher.equals(newItem.publisher)) return false;
                if (oldItem.getTotalSize() != newItem.getTotalSize()) return false;
                if (oldItem.getIsWhitelisted() != newItem.getIsWhitelisted()) return false;
                int oldCount = oldItem.getStickers() != null ? oldItem.getStickers().size() : 0;
                int newCount = newItem.getStickers() != null ? newItem.getStickers().size() : 0;
                if (oldCount != newCount) return false;
                return true;
            }
        });
        this.onAddButtonClickedListener = onAddButtonClickedListener;
        setHasStableIds(true);
        // Keep the nested preview pool modest to avoid bitmap/view churn.
        sharedPool.setMaxRecycledViews(0, 8);
    }

    public void setScrolling(boolean isScrolling) {
        if (this.isScrolling != isScrolling) {
            this.isScrolling = isScrolling;
            // Efficiently notify only about the scroll state change
            notifyItemRangeChanged(0, getItemCount(), "scroll_state_change");
        }
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).identifier.hashCode();
    }

    @NonNull
    @Override
    public StickerPackListItemViewHolder onCreateViewHolder(@NonNull final ViewGroup viewGroup, final int i) {
        final Context context = viewGroup.getContext();
        final LayoutInflater layoutInflater = LayoutInflater.from(context);
        final View stickerPackRow = layoutInflater.inflate(R.layout.sticker_packs_list_item, viewGroup, false);
        StickerPackListItemViewHolder vh = new StickerPackListItemViewHolder(stickerPackRow);
        vh.imageRowView.setRecycledViewPool(sharedPool);
        return vh;
    }

    @Override
    public void onBindViewHolder(@NonNull StickerPackListItemViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.contains("scroll_state_change")) {
            if (holder.previewAdapter != null) {
                holder.previewAdapter.setScrolling(isScrolling);
            }
        } else {
            super.onBindViewHolder(holder, position, payloads);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final StickerPackListItemViewHolder viewHolder, final int index) {
        StickerPack pack = getItem(index);
        final Context context = viewHolder.titleView.getContext();

        int count = pack.getStickers() != null ? pack.getStickers().size() : 0;
        viewHolder.countView.setText(context.getString(R.string.sticker_count, count));

        viewHolder.titleView.setText(pack.name);
        if (viewHolder.publisherView != null) {
            viewHolder.publisherView.setText(pack.publisher);
        }
        if (viewHolder.filesizeView != null) {
            viewHolder.filesizeView.setText(Formatter.formatShortFileSize(context, pack.getTotalSize()));
        }

        viewHolder.container.setOnClickListener(view -> {
            Intent intent = new Intent(view.getContext(), StickerPackDetailsActivity.class);
            intent.putExtra(StickerPackDetailsActivity.EXTRA_SHOW_UP_BUTTON, true);
            intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_DATA, pack);
            view.getContext().startActivity(intent);
        });

        setAddButtonAppearance(viewHolder.addButton, pack);
        viewHolder.animatedStickerPackIndicator.setVisibility(pack.animatedStickerPack ? View.VISIBLE : View.GONE);

        if (pack.getStickers() != null && maxNumberOfStickersInARow > 0) {
            final int previewSize = context.getResources().getDimensionPixelSize(R.dimen.sticker_pack_list_item_preview_image_size);
            int numToShow = Math.min(maxNumberOfStickersInARow, pack.getStickers().size());
            List<Sticker> previewStickers = pack.getStickers().subList(0, numToShow);
            
            if (animationsEnabledCache == null) {
                animationsEnabledCache = SettingsActivity.isAnimationsEnabled(context);
            }
            boolean animationsEnabled = animationsEnabledCache;

            if (viewHolder.previewAdapter != null) {
                viewHolder.previewAdapter.updateData(previewStickers, pack.identifier,
                        previewSize, minMarginBetweenImages,
                        pack.animatedStickerPack, animationsEnabled, isScrolling);
            } else {
                StickerPreviewAdapter adapter = new StickerPreviewAdapter(
                        previewStickers, pack.identifier, previewSize,
                        minMarginBetweenImages, pack.animatedStickerPack, animationsEnabled, false, null);
                adapter.setScrolling(isScrolling);
                viewHolder.previewAdapter = adapter;
                viewHolder.imageRowView.setAdapter(adapter);
            }
            viewHolder.imageRowView.setVisibility(View.VISIBLE);
        } else {
            viewHolder.imageRowView.setVisibility(View.GONE);
        }
    }

    private void setAddButtonAppearance(MaterialButton addButton, StickerPack pack) {
        if (pack.getIsWhitelisted()) {
            addButton.setIconResource(R.drawable.sticker_3rdparty_added);
            addButton.setAlpha(0.5f);
            addButton.setClickable(false);
            addButton.setOnClickListener(null);
        } else {
            addButton.setIconResource(R.drawable.sticker_3rdparty_add);
            addButton.setAlpha(1.0f);
            addButton.setClickable(true);
            addButton.setOnClickListener(v -> onAddButtonClickedListener.onAddButtonClicked(pack));
        }
    }

    void setImageRowSpec(int maxNumberOfStickersInARow, int minMarginBetweenImages) {
        this.minMarginBetweenImages = minMarginBetweenImages;
        if (this.maxNumberOfStickersInARow != maxNumberOfStickersInARow) {
            this.maxNumberOfStickersInARow = maxNumberOfStickersInARow;
            notifyItemRangeChanged(0, getItemCount());
        }
    }

    void invalidateAnimationsCache() {
        animationsEnabledCache = null;
    }

    public interface OnAddButtonClickedListener {
        void onAddButtonClicked(StickerPack stickerPack);
    }
}
