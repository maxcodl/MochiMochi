/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.kawai.mochii;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Animatable;
import android.net.Uri;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;

import java.util.ArrayList;
import java.util.List;

public class StickerPreviewAdapter extends RecyclerView.Adapter<StickerPreviewViewHolder> {

    private static final float COLLAPSED_STICKER_PREVIEW_BACKGROUND_ALPHA = 1f;
    private static final float EXPANDED_STICKER_PREVIEW_BACKGROUND_ALPHA = 0.2f;

    @NonNull
    private final StickerPack stickerPack;

    private final int cellSize;
    private final int cellLimit;
    private final int cellPadding;
    private final int errorResource;
    private final View expandedStickerPreview;

    private final LayoutInflater layoutInflater;
    private RecyclerView recyclerView;
    private View clickedStickerPreview;

    StickerPreviewAdapter(
            @NonNull final LayoutInflater layoutInflater,
            final int errorResource,
            final int cellSize,
            final int cellPadding,
            @NonNull final StickerPack stickerPack,
            final View expandedStickerView) {
        this.cellSize = cellSize;
        this.cellPadding = cellPadding;
        this.cellLimit = 0;
        this.layoutInflater = layoutInflater;
        this.errorResource = errorResource;
        this.stickerPack = stickerPack;
        this.expandedStickerPreview = expandedStickerView;
    }

    @NonNull
    @Override
    public StickerPreviewViewHolder onCreateViewHolder(@NonNull final ViewGroup viewGroup, final int i) {
        View itemView = layoutInflater.inflate(R.layout.sticker_image_item, viewGroup, false);
        StickerPreviewViewHolder vh = new StickerPreviewViewHolder(itemView);

        ViewGroup.LayoutParams layoutParams = vh.stickerPreviewView.getLayoutParams();
        layoutParams.height = cellSize;
        layoutParams.width = cellSize;
        vh.stickerPreviewView.setLayoutParams(layoutParams);
        vh.stickerPreviewView.setPadding(cellPadding, cellPadding, cellPadding, cellPadding);

        return vh;
    }

    @Override
    public void onBindViewHolder(@NonNull final StickerPreviewViewHolder stickerPreviewViewHolder, final int i) {
        Sticker sticker = stickerPack.getStickers().get(i);
        stickerPreviewViewHolder.stickerPreviewView.setImageResource(errorResource);
        
        Uri uri = StickerPackLoader.getStickerAssetUri(stickerPack.identifier, sticker.imageFileName);
        boolean animationsEnabled = SettingsActivity.isAnimationsEnabled(stickerPreviewViewHolder.stickerPreviewView.getContext());

        // PERFORMANCE: Resize image to cell size for static stickers.
        // We use cellSize directly to maintain high quality in the grid.
        ImageRequest request = ImageRequestBuilder.newBuilderWithSource(uri)
                .setResizeOptions(new ResizeOptions(cellSize, cellSize))
                .build();

        DraweeController controller = Fresco.newDraweeControllerBuilder()
                .setImageRequest(request)
                .setAutoPlayAnimations(animationsEnabled)
                .setOldController(stickerPreviewViewHolder.stickerPreviewView.getController())
                .build();
        stickerPreviewViewHolder.stickerPreviewView.setController(controller);

        stickerPreviewViewHolder.stickerPreviewView.setOnClickListener(v -> expandPreview(i, stickerPreviewViewHolder.stickerPreviewView));
        
        android.widget.ImageView warningIcon = stickerPreviewViewHolder.itemView.findViewById(R.id.sticker_warning);
        if (warningIcon != null) {
            warningIcon.setVisibility(sticker.validationError != null ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onViewRecycled(@NonNull StickerPreviewViewHolder holder) {
        super.onViewRecycled(holder);
        holder.stickerPreviewView.setController(null);
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.recyclerView = recyclerView;
        recyclerView.addOnScrollListener(hideExpandedViewScrollListener);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        recyclerView.removeOnScrollListener(hideExpandedViewScrollListener);
        this.recyclerView = null;
    }

    private final RecyclerView.OnScrollListener hideExpandedViewScrollListener =
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    if (dx != 0 || dy != 0) {
                        hideExpandedStickerPreview();
                    }
                }
            };

    private void positionExpandedStickerPreview() {
        if (expandedStickerPreview != null && recyclerView != null) {
            float centerX = recyclerView.getWidth() / 2f;
            float centerY = recyclerView.getHeight() / 2f;

            expandedStickerPreview.setX(centerX - expandedStickerPreview.getWidth() / 2f);
            expandedStickerPreview.setY(centerY - expandedStickerPreview.getHeight() / 2f);
        }
    }

    private void expandPreview(int position, View clickedStickerPreview) {
        if (isStickerPreviewExpanded()) {
            hideExpandedStickerPreview();
            return;
        }

        this.clickedStickerPreview = clickedStickerPreview;
        if (expandedStickerPreview != null) {
            positionExpandedStickerPreview();

            Sticker sticker = stickerPack.getStickers().get(position);
            final Uri stickerAssetUri = StickerPackLoader.getStickerAssetUri(stickerPack.identifier, sticker.imageFileName);
            
            SimpleDraweeView expandedImage = expandedStickerPreview.findViewById(R.id.sticker_details_expanded_sticker);
            
            int expandedSize = expandedImage != null ? expandedImage.getLayoutParams().width : 300;
            if (expandedSize <= 0) expandedSize = 300;

            // PERFORMANCE: Resize for the expanded view to its display size.
            ImageRequest request = ImageRequestBuilder.newBuilderWithSource(stickerAssetUri)
                    .setResizeOptions(new ResizeOptions(expandedSize, expandedSize))
                    .build();

            DraweeController controller = Fresco.newDraweeControllerBuilder()
                    .setImageRequest(request)
                    .setAutoPlayAnimations(true)
                    .build();

            android.widget.TextView nameText = expandedStickerPreview.findViewById(R.id.expanded_sticker_name);
            android.widget.TextView emojisText = expandedStickerPreview.findViewById(R.id.expanded_sticker_emojis);
            android.widget.TextView sizeText = expandedStickerPreview.findViewById(R.id.expanded_sticker_size);
            View editEmojiButton = expandedStickerPreview.findViewById(R.id.edit_emoji_button);
            View deleteStickerButton = expandedStickerPreview.findViewById(R.id.delete_sticker_button);

            if (expandedImage != null) {
                expandedImage.setImageResource(errorResource);
                expandedImage.setController(controller);
            }
            if (nameText != null) {
                nameText.setText(sticker.imageFileName);
                if (sticker.validationError != null) {
                    nameText.setTextColor(android.graphics.Color.parseColor("#E53935"));
                }
            }
            if (emojisText != null) {
                emojisText.setText(sticker.emojis != null ? android.text.TextUtils.join(" ", sticker.emojis) : "");
            }
            if (sizeText != null) {
                String sizeStr = android.text.format.Formatter.formatShortFileSize(expandedStickerPreview.getContext(), sticker.size);
                if (sticker.validationError != null) {
                    sizeText.setText(sizeStr + "  " + sticker.validationError);
                    sizeText.setTextColor(android.graphics.Color.parseColor("#E53935"));
                } else {
                    sizeText.setText(sizeStr);
                    sizeText.setAlpha(0.7f);
                }
            }

            if (editEmojiButton != null) {
                editEmojiButton.setOnClickListener(v -> {
                    final android.widget.EditText editText = new android.widget.EditText(expandedStickerPreview.getContext());
                    editText.setText(sticker.emojis != null ? android.text.TextUtils.join("", sticker.emojis) : "");
                    editText.setHint("Enter up to 3 emojis");
                    
                    android.widget.FrameLayout container = new android.widget.FrameLayout(expandedStickerPreview.getContext());
                    android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.leftMargin = params.rightMargin = 60;
                    params.topMargin = 20;
                    editText.setLayoutParams(params);
                    container.addView(editText);

                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(expandedStickerPreview.getContext())
                            .setTitle("Edit Sticker Emojis")
                            .setView(container)
                            .setPositiveButton("Save", (dialog, which) -> {
                                String text = editText.getText().toString();
                                List<String> newEmojis = new ArrayList<>();
                                for (int i = 0; i < text.length(); ) {
                                    int codePoint = text.codePointAt(i);
                                    newEmojis.add(new String(Character.toChars(codePoint)));
                                    i += Character.charCount(codePoint);
                                }
                                if (newEmojis.isEmpty()) newEmojis.add("\uD83D\uDE00");
                                if (newEmojis.size() > 3) newEmojis = new ArrayList<>(newEmojis.subList(0, 3));

                                try {
                                    WastickerParser.updateStickerEmojis(expandedStickerPreview.getContext(), stickerPack.identifier, sticker.imageFileName, newEmojis);
                                    sticker.setEmojis(newEmojis);
                                    if (emojisText != null) {
                                        emojisText.setText(android.text.TextUtils.join(" ", newEmojis));
                                    }
                                    notifyItemChanged(position);
                                    android.widget.Toast.makeText(expandedStickerPreview.getContext(), "Emojis updated", android.widget.Toast.LENGTH_SHORT).show();
                                } catch (Exception e) {
                                    android.widget.Toast.makeText(expandedStickerPreview.getContext(), "Error updating emojis", android.widget.Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            }

            if (deleteStickerButton != null) {
                deleteStickerButton.setOnClickListener(v -> {
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(expandedStickerPreview.getContext())
                            .setTitle("Delete Sticker")
                            .setMessage("Delete this sticker from the pack?")
                            .setPositiveButton("Delete", (dialog, which) -> {
                                deleteSticker(position);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            }

            expandedStickerPreview.setVisibility(View.VISIBLE);
            
            View overlay = null;
            if (expandedStickerPreview.getParent() instanceof ViewGroup) {
                overlay = ((ViewGroup) expandedStickerPreview.getParent()).findViewById(R.id.expanded_sticker_overlay);
            }
            if (overlay != null) {
                overlay.setVisibility(View.VISIBLE);
                overlay.setOnClickListener(v -> hideExpandedStickerPreview());
            }

            applyBlurAndDim(true);
            toggleBackgroundAnimations(false);
        }
    }

    private void deleteSticker(int position) {
        if (position < 0 || position >= stickerPack.getStickers().size()) return;
        
        Sticker sticker = stickerPack.getStickers().get(position);
        View contextView = expandedStickerPreview != null ? expandedStickerPreview : recyclerView;
        if (contextView == null) return;

        try {
            WastickerParser.deleteSticker(contextView.getContext(), stickerPack.identifier, sticker.imageFileName);
            stickerPack.getStickers().remove(position);
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, stickerPack.getStickers().size());
            hideExpandedStickerPreview();
            
            StickerContentProvider provider = StickerContentProvider.getInstance();
            if (provider != null) provider.invalidateStickerPackList();
            
            android.widget.Toast.makeText(contextView.getContext(), "Sticker deleted", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(contextView.getContext(), "Error deleting: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    public void hideExpandedStickerPreview() {
        if (isStickerPreviewExpanded() && expandedStickerPreview != null) {
            expandedStickerPreview.setVisibility(View.INVISIBLE);
            
            View overlay = null;
            if (expandedStickerPreview.getParent() instanceof ViewGroup) {
                overlay = ((ViewGroup) expandedStickerPreview.getParent()).findViewById(R.id.expanded_sticker_overlay);
            }
            if (overlay != null) {
                overlay.setVisibility(View.INVISIBLE);
            }

            applyBlurAndDim(false);
            toggleBackgroundAnimations(true);
        }
    }

    private void toggleBackgroundAnimations(boolean start) {
        if (recyclerView == null) return;
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            SimpleDraweeView draweeView = child.findViewById(R.id.sticker_preview);
            if (draweeView != null) {
                DraweeController controller = draweeView.getController();
                if (controller != null) {
                    Animatable animatable = controller.getAnimatable();
                    if (animatable != null) {
                        if (start) {
                            if (!animatable.isRunning()) animatable.start();
                        } else {
                            if (animatable.isRunning()) animatable.stop();
                        }
                    }
                }
            }
        }
    }

    private void applyBlurAndDim(boolean active) {
        if (recyclerView == null) return;
        
        float alpha = active ? EXPANDED_STICKER_PREVIEW_BACKGROUND_ALPHA : COLLAPSED_STICKER_PREVIEW_BACKGROUND_ALPHA;
        float blurRadius = 20f;

        recyclerView.setAlpha(alpha);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            recyclerView.setRenderEffect(active ? RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP) : null);
        }

        ViewGroup container = (ViewGroup) recyclerView.getParent();
        if (container != null && container.getParent() instanceof ViewGroup) {
            ViewGroup root = (ViewGroup) container.getParent();
            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i);
                if (child != container) {
                    child.setAlpha(alpha);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        child.setRenderEffect(active ? RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP) : null);
                    }
                }
            }
        }
    }

    private boolean isStickerPreviewExpanded() {
        return expandedStickerPreview != null && expandedStickerPreview.getVisibility() == View.VISIBLE;
    }

    @Override
    public int getItemCount() {
        return stickerPack.getStickers().size();
    }
}
