/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.kawai.mochi;

import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.kawai.mochi.R;

class StickerPackListItemViewHolder extends RecyclerView.ViewHolder {

    final View container;
    final TextView titleView;
    final TextView publisherView;
    final TextView filesizeView;
    final TextView countView;
    final MaterialButton addButton;
    final View animatedStickerPackIndicator;
    final RecyclerView imageRowView;
    // Cached adapter so we can update data in-place instead of recreating on every bind
    StickerPreviewAdapter previewAdapter;

    StickerPackListItemViewHolder(final View itemView) {
        super(itemView);
        // FIX: Point to the overlay area that covers the entire card
        View detailsArea = itemView.findViewById(R.id.details_clickable_area);
        container = (detailsArea != null) ? detailsArea : itemView;

        titleView = itemView.findViewById(R.id.sticker_pack_title);
        publisherView = itemView.findViewById(R.id.sticker_pack_publisher);
        filesizeView = itemView.findViewById(R.id.sticker_pack_filesize);
        countView = itemView.findViewById(R.id.sticker_pack_count);
        addButton = itemView.findViewById(R.id.add_button_on_list);
        imageRowView = itemView.findViewById(R.id.sticker_packs_list_item_image_list);
        animatedStickerPackIndicator = itemView.findViewById(R.id.sticker_pack_animation_indicator);
        
        // Optimize horizontal RecyclerView
        imageRowView.setHasFixedSize(true);
        imageRowView.setNestedScrollingEnabled(false);
    }
}
