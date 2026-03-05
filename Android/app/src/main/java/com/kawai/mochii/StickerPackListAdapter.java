/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.kawai.mochii;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import com.google.android.material.button.MaterialButton;

import java.util.List;

public class StickerPackListAdapter extends RecyclerView.Adapter<StickerPackListItemViewHolder> {
    @NonNull
    private List<StickerPack> stickerPacks;
    @NonNull
    private final OnAddButtonClickedListener onAddButtonClickedListener;
    private int maxNumberOfStickersInARow;
    private int minMarginBetweenImages;

    StickerPackListAdapter(@NonNull List<StickerPack> stickerPacks, @NonNull OnAddButtonClickedListener onAddButtonClickedListener) {
        this.stickerPacks = stickerPacks;
        this.onAddButtonClickedListener = onAddButtonClickedListener;
        setHasStableIds(true);
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
        return new StickerPackListItemViewHolder(stickerPackRow);
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

        // Populate sticker preview image row
        if (pack.getStickers() != null && maxNumberOfStickersInARow > 0) {
            final int previewSize = context.getResources().getDimensionPixelSize(R.dimen.sticker_pack_list_item_preview_image_size);
            int numToShow = Math.min(maxNumberOfStickersInARow, pack.getStickers().size());
            boolean animationsEnabled = SettingsActivity.isAnimationsEnabled(context);

            // QUALITY FIX: Use the actual preview size instead of aggressive downscaling
            int renderSize = previewSize;

            int currentChildCount = viewHolder.imageRowView.getChildCount();
            
            // Critical fix for ClassCastException: remove any views that aren't SimpleDraweeView
            for (int i = 0; i < currentChildCount; i++) {
                if (!(viewHolder.imageRowView.getChildAt(i) instanceof SimpleDraweeView)) {
                    viewHolder.imageRowView.removeViewAt(i);
                    currentChildCount--;
                    i--;
                }
            }

            if (currentChildCount > numToShow) {
                viewHolder.imageRowView.removeViews(numToShow, currentChildCount - numToShow);
            }

            for (int i = 0; i < numToShow; i++) {
                SimpleDraweeView rowImage;
                if (i < viewHolder.imageRowView.getChildCount()) {
                    rowImage = (SimpleDraweeView) viewHolder.imageRowView.getChildAt(i);
                } else {
                    rowImage = (SimpleDraweeView) LayoutInflater.from(context)
                            .inflate(R.layout.sticker_packs_list_image_item, viewHolder.imageRowView, false);
                    viewHolder.imageRowView.addView(rowImage);
                }

                rowImage.setLayoutParams(new LinearLayout.LayoutParams(previewSize, previewSize));
                if (i != 0) {
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) rowImage.getLayoutParams();
                    lp.setMarginStart(minMarginBetweenImages);
                    lp.leftMargin = minMarginBetweenImages;
                    rowImage.setLayoutParams(lp);
                }
                
                final Uri fileUri = StickerPackLoader.getStickerAssetUri(
                        pack.identifier, pack.getStickers().get(i).imageFileName);
                
                ImageRequest request = ImageRequestBuilder.newBuilderWithSource(fileUri)
                        .setResizeOptions(new ResizeOptions(renderSize, renderSize))
                        .build();

                DraweeController controller = Fresco.newDraweeControllerBuilder()
                        .setImageRequest(request)
                        .setAutoPlayAnimations(animationsEnabled)
                        .setOldController(rowImage.getController())
                        .build();
                rowImage.setController(controller);
            }
        } else {
            viewHolder.imageRowView.removeAllViews();
        }
    }

    @Override
    public void onViewRecycled(@NonNull StickerPackListItemViewHolder viewHolder) {
        super.onViewRecycled(viewHolder);
        // PERFORMANCE: Stop animations and release memory immediately when row scrolls away
        for (int i = 0; i < viewHolder.imageRowView.getChildCount(); i++) {
            View child = viewHolder.imageRowView.getChildAt(i);
            if (child instanceof SimpleDraweeView) {
                ((SimpleDraweeView) child).setController(null);
            }
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

    public interface OnAddButtonClickedListener {
        void onAddButtonClicked(StickerPack stickerPack);
    }
}
