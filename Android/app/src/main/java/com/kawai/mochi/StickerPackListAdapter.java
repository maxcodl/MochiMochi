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

public class StickerPackListAdapter extends RecyclerView.Adapter<StickerPackListItemViewHolder> {
    @NonNull
    private List<StickerPack> stickerPacks;
    @NonNull
    private final OnAddButtonClickedListener onAddButtonClickedListener;
    private int maxNumberOfStickersInARow;
    private int minMarginBetweenImages;
    private boolean isScrolling;
    private final RecyclerView.RecycledViewPool sharedPool = new RecyclerView.RecycledViewPool();
    // Cache so we don't hit SharedPreferences on every bind
    Boolean animationsEnabledCache = null;

    StickerPackListAdapter(@NonNull List<StickerPack> stickerPacks, @NonNull OnAddButtonClickedListener onAddButtonClickedListener) {
        this.stickerPacks = stickerPacks;
        this.onAddButtonClickedListener = onAddButtonClickedListener;
        setHasStableIds(true);
        // Performance: Increase pool size to hold enough views for ~5 visible rows
        sharedPool.setMaxRecycledViews(0, 25);
    }

    public void setScrolling(boolean isScrolling) {
        this.isScrolling = isScrolling;
    }

    @Override
    public long getItemId(int position) {
        return stickerPacks.get(position).identifier.hashCode();
    }

    @NonNull
    @Override
    public StickerPackListItemViewHolder onCreateViewHolder(@NonNull final ViewGroup viewGroup, final int i) {
        final Context context = viewGroup.getContext();
        final LayoutInflater layoutInflater = LayoutInflater.from(context);
        final View stickerPackRow = layoutInflater.inflate(R.layout.sticker_packs_list_item, viewGroup, false);
        StickerPackListItemViewHolder vh = new StickerPackListItemViewHolder(stickerPackRow);
        
        // Performance: Use a shared pool for all horizontal preview lists
        vh.imageRowView.setRecycledViewPool(sharedPool);
        return vh;
    }

    @Override
    public void onBindViewHolder(@NonNull final StickerPackListItemViewHolder viewHolder, final int index) {
        StickerPack pack = stickerPacks.get(index);
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
                // Reuse existing adapter — Fresco keeps images in memory cache,
                // so scrolling back to a seen item costs nothing.
                viewHolder.previewAdapter.updateData(previewStickers, pack.identifier,
                        previewSize, minMarginBetweenImages,
                        pack.animatedStickerPack, animationsEnabled, isScrolling);
            } else {
                StickerPreviewAdapter adapter = new StickerPreviewAdapter(
                        previewStickers, pack.identifier, previewSize,
                        minMarginBetweenImages, pack.animatedStickerPack, animationsEnabled);
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

    @Override
    public int getItemCount() {
        return stickerPacks.size();
    }

    void setImageRowSpec(int maxNumberOfStickersInARow, int minMarginBetweenImages) {
        this.minMarginBetweenImages = minMarginBetweenImages;
        if (this.maxNumberOfStickersInARow != maxNumberOfStickersInARow) {
            this.maxNumberOfStickersInARow = maxNumberOfStickersInARow;
            notifyDataSetChanged();
        }
    }

    void setStickerPackList(List<StickerPack> stickerPackList) {
        this.stickerPacks = stickerPackList;
    }

    /** Call when the animations setting changes so the next bind picks up the new value. */
    void invalidateAnimationsCache() {
        animationsEnabledCache = null;
    }

    public interface OnAddButtonClickedListener {
        void onAddButtonClicked(StickerPack stickerPack);
    }
}
