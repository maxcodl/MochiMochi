package com.kawai.mochi;

import android.content.Context;
import android.graphics.drawable.Animatable;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.drawable.ScalingUtils;
import com.facebook.drawee.controller.BaseControllerListener;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.imagepipeline.image.ImageInfo;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import com.facebook.shimmer.ShimmerFrameLayout;

import java.io.File;
import java.util.List;

public class StickerPreviewAdapter extends RecyclerView.Adapter<StickerPreviewAdapter.ViewHolder> {

    public interface StickerInteractionListener {
        void onStickerHoldStarted(@NonNull Sticker sticker, @NonNull Uri stickerUri, boolean animatedPack);
        void onStickerHoldEnded();
    }

    private static final float GRID_DECODE_SCALE = 0.30f;

    private final Context context;
    private List<Sticker> stickers;
    private String packIdentifier;
    private int previewSize;
    private int marginBetween;
    private boolean animationsEnabled;          // from user settings (disable all animations)
    private boolean isAnimationsPaused;
    private final boolean isGridMode;
    @Nullable
    private final StickerInteractionListener interactionListener;

    public StickerPreviewAdapter(Context context,
                                 List<Sticker> stickers,
                                 String packIdentifier,
                                 int previewSize,
                                 int marginBetween,
                                 boolean animationsEnabled,
                                 boolean isGridMode,
                                 @Nullable StickerInteractionListener interactionListener) {
        this.context = context;
        this.stickers = stickers;
        this.packIdentifier = packIdentifier;
        this.previewSize = previewSize;
        this.marginBetween = marginBetween;
        this.animationsEnabled = animationsEnabled;
        this.isGridMode = isGridMode;
        this.interactionListener = interactionListener;
        setHasStableIds(true);
    }

    public void setAnimationsPaused(boolean paused) {
        if (this.isAnimationsPaused != paused) {
            this.isAnimationsPaused = paused;
            notifyItemRangeChanged(0, getItemCount(), "animation_state_change");
        }
    }

    public void updateData(List<Sticker> newStickers,
                           String newPackId,
                           int newPreviewSize,
                           int newMargin,
                           boolean newAnimEnabled) {
        boolean packChanged = !java.util.Objects.equals(newPackId, packIdentifier);
        this.stickers = newStickers;
        this.packIdentifier = newPackId;
        this.previewSize = newPreviewSize;
        this.marginBetween = newMargin;
        this.animationsEnabled = newAnimEnabled;

        if (packChanged) {
            notifyDataSetChanged();
        } else {
            notifyItemRangeChanged(0, getItemCount(), "animation_state_change");
        }
    }

    public void prefetchThumbnails() {
        if (stickers == null) return;
        for (Sticker sticker : stickers) {
            String thumbName = "thumbnails/thumb_" + sticker.imageFileName;
            Uri thumbUri = StickerPackLoader.getStickerAssetUri(packIdentifier, thumbName);
            ImageRequest request = ImageRequestBuilder.newBuilderWithSource(thumbUri)
                    .setLocalThumbnailPreviewsEnabled(true)
                    .build();
            Fresco.getImagePipeline().prefetchToDiskCache(request, null);
        }
    }

    @Override
    public long getItemId(int position) {
        return (packIdentifier + "/" + stickers.get(position).imageFileName).hashCode();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.sticker_packs_list_image_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.contains("animation_state_change")) {
            updateContentState(holder);
        } else {
            super.onBindViewHolder(holder, position, payloads);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Sticker sticker = stickers.get(position);
        holder.sticker = sticker;

        holder.errorView.setVisibility(View.GONE);
        holder.bitmapView.setVisibility(View.GONE);
        holder.draweeView.setVisibility(View.VISIBLE);

        String thumbName = "thumbnails/thumb_" + sticker.imageFileName;
        final Uri thumbUri = StickerPackLoader.getStickerAssetUri(packIdentifier, thumbName);
        final Uri fullUri = StickerPackLoader.getStickerAssetUri(packIdentifier, sticker.imageFileName);

        setupInteractions(holder, sticker, fullUri);

        boolean thumbExists = thumbnailFileExists(packIdentifier, thumbName);
        Uri loadUri = thumbExists ? thumbUri : fullUri;
        final int decodeSize;

        if (!thumbExists) {
            int calculated = Math.round(previewSize * GRID_DECODE_SCALE);
            decodeSize = Math.max(1, calculated);
        } else {
            decodeSize = 0;
        }

        boolean isCached = Fresco.getImagePipeline().isInBitmapMemoryCache(loadUri);
        if (isCached) {
            holder.skeletonView.stopShimmer();
            holder.skeletonView.setVisibility(View.GONE);
        } else {
            holder.skeletonView.setVisibility(View.VISIBLE);
            holder.skeletonView.startShimmer();
        }

        bindFrescoImage(holder, sticker, loadUri, fullUri, decodeSize);
        applyLayout(holder, position);
    }

    private void bindFrescoImage(@NonNull final ViewHolder holder,
                                 final Sticker sticker,
                                 @NonNull final Uri sourceUri,
                                 @NonNull final Uri fullUri,
                                 int decodeSize) {
        holder.draweeView.getHierarchy().setActualImageScaleType(ScalingUtils.ScaleType.FIT_CENTER);

        ImageRequestBuilder builder = ImageRequestBuilder.newBuilderWithSource(sourceUri)
                .setLocalThumbnailPreviewsEnabled(true);

        if (decodeSize > 0) {
            builder.setResizeOptions(
                    new com.facebook.imagepipeline.common.ResizeOptions(decodeSize, decodeSize));
        }

        ImageRequest mainRequest = builder.build();

        boolean shouldAutoPlay = sticker.isAnimated
                && animationsEnabled
                && !isAnimationsPaused;

        holder.controllerListener.thumbUri = sourceUri;
        holder.controllerListener.fullUri = fullUri;
        holder.controllerListener.sticker = sticker;
        holder.controllerListener.decodeSize = decodeSize;

        DraweeController controller = Fresco.newDraweeControllerBuilder()
                .setImageRequest(mainRequest)
                .setAutoPlayAnimations(shouldAutoPlay)
                .setControllerListener(holder.controllerListener)
                .setOldController(holder.draweeView.getController())
                .build();

        holder.draweeView.setController(controller);
    }

    private boolean thumbnailFileExists(String packId, String thumbName) {
        String rootPath = WastickerParser.getStickerFolderPath(context);
        if (WastickerParser.isCustomPathUri(context)) {
            return true;
        } else {
            // thumbName is "thumbnails/thumb_<file>" — resolve relative to pack dir
            String flatName = thumbName.contains("/")
                    ? thumbName.substring(thumbName.lastIndexOf('/') + 1)
                    : thumbName;
            File thumbFile = new File(new File(new File(rootPath, packId), "thumbnails"), flatName);
            // Also accept legacy flat location
            if (!thumbFile.exists()) {
                thumbFile = new File(new File(rootPath, packId), flatName);
            }
            return thumbFile.exists();
        }
    }

    private void updateContentState(@NonNull ViewHolder holder) {
        if (holder.sticker == null || !holder.sticker.isAnimated) return;

        DraweeController controller = holder.draweeView.getController();
        if (controller != null && controller.getAnimatable() != null) {
            if (!isAnimationsPaused && animationsEnabled) {
                controller.getAnimatable().start();
            } else {
                controller.getAnimatable().stop();
            }
        }
    }

    private void setupInteractions(@NonNull ViewHolder holder, Sticker sticker, Uri fullUri) {
        holder.itemView.setOnClickListener(v -> {
            if (interactionListener != null) {
                interactionListener.onStickerHoldStarted(sticker, fullUri, sticker.isAnimated);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (interactionListener != null) {
                interactionListener.onStickerHoldStarted(sticker, fullUri, sticker.isAnimated);
                return true;
            }
            return false;
        });
        holder.itemView.setOnTouchListener((v, event) -> {
            if (interactionListener != null) {
                int action = event.getActionMasked();
                switch (action) {
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        interactionListener.onStickerHoldEnded();
                        break;
                }
            }
            return false;
        });
    }

    private void applyLayout(@NonNull ViewHolder holder, int position) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.itemView.getLayoutParams();
        if (lp == null) lp = new ViewGroup.MarginLayoutParams(previewSize, previewSize);
        lp.width = previewSize;
        lp.height = previewSize;
        if (isGridMode) {
            int half = marginBetween / 2;
            lp.topMargin = half;
            lp.bottomMargin = half;
            lp.setMarginStart(half);
            lp.setMarginEnd(half);
        } else {
            lp.topMargin = 0;
            lp.bottomMargin = 0;
            lp.setMarginEnd(0);
            lp.setMarginStart(position > 0 ? marginBetween : 0);
        }
        holder.itemView.setLayoutParams(lp);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        holder.sticker = null;
        holder.draweeView.setController(null);
        holder.skeletonView.stopShimmer();
        holder.skeletonView.setVisibility(View.GONE);
        holder.errorView.setVisibility(View.GONE);
        holder.controllerListener.thumbUri = null;
        holder.controllerListener.fullUri = null;
        holder.controllerListener.sticker = null;
    }

    @Override
    public int getItemCount() {
        return stickers == null ? 0 : stickers.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        final ShimmerFrameLayout skeletonView;
        final ImageView bitmapView;
        @SuppressWarnings("deprecation")
        final SimpleDraweeView draweeView;
        final ImageView errorView;
        @Nullable Sticker sticker;

        final HolderControllerListener controllerListener = new HolderControllerListener();

        public ViewHolder(View itemView) {
            super(itemView);
            this.skeletonView = itemView.findViewById(R.id.sticker_skeleton);
            this.bitmapView = itemView.findViewById(R.id.sticker_bitmap_preview);
            this.draweeView = itemView.findViewById(R.id.sticker_pack_list_item_image);
            this.errorView = itemView.findViewById(R.id.sticker_preview_error);
            this.draweeView.getHierarchy().setFadeDuration(0);
        }

        class HolderControllerListener extends BaseControllerListener<ImageInfo> {
            @Nullable Uri thumbUri;
            @Nullable Uri fullUri;
            @Nullable Sticker sticker;
            int decodeSize;

            @Override
            public void onFinalImageSet(String id, @Nullable ImageInfo imageInfo,
                                        @Nullable Animatable animatable) {
                skeletonView.stopShimmer();
                skeletonView.setVisibility(View.GONE);
            }

            @Override
            public void onFailure(String id, Throwable throwable) {
                if (thumbUri != null && fullUri != null && !java.util.Objects.equals(thumbUri, fullUri)) {
                    bindFrescoImage(ViewHolder.this, sticker, fullUri, fullUri, decodeSize);
                } else {
                    draweeView.setVisibility(View.GONE);
                    errorView.setVisibility(View.VISIBLE);
                    skeletonView.stopShimmer();
                    skeletonView.setVisibility(View.GONE);
                }
            }
        }
    }
}
