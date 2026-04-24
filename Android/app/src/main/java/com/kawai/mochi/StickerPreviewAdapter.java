package com.kawai.mochi;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
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
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.image.ImageInfo;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import com.facebook.shimmer.ShimmerFrameLayout;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class StickerPreviewAdapter extends RecyclerView.Adapter<StickerPreviewAdapter.ViewHolder> {
    public interface StickerInteractionListener {
        void onStickerHoldStarted(@NonNull Sticker sticker, @NonNull Uri stickerUri, boolean animatedPack);
        void onStickerHoldEnded();
    }

    private static final ExecutorService decodeExecutor = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final StickerBitmapLruCache bitmapCache = StickerBitmapLruCache.getInstance();
    
    // POWER-OF-TWO RESOLUTION: 32px is 1/16th of 512px, which optimizes hardware downsampling
    private static final int LIST_DECODE_SIZE_PX = 32;
    private static final int LIST_ANIMATED_DECODE_SIZE_PX = 16;

    private List<Sticker> stickers;
    private String packIdentifier;
    private int previewSize;
    private int marginBetween;
    private boolean isAnimatedPack;
    private boolean animationsEnabled;
    private boolean isScrolling;
    private final boolean isGridMode;
    @Nullable
    private final StickerInteractionListener interactionListener;

    public StickerPreviewAdapter(List<Sticker> stickers, String packIdentifier, int previewSize,
                                 int marginBetween, boolean isAnimatedPack, boolean animationsEnabled,
                                 boolean isGridMode, @Nullable StickerInteractionListener interactionListener) {
        this.stickers = stickers;
        this.packIdentifier = packIdentifier;
        this.previewSize = previewSize;
        this.marginBetween = marginBetween;
        this.isAnimatedPack = isAnimatedPack;
        this.animationsEnabled = animationsEnabled;
        this.isGridMode = isGridMode;
        this.interactionListener = interactionListener;
        setHasStableIds(true);
    }

    public StickerPreviewAdapter(List<Sticker> stickers, String packIdentifier, int previewSize,
                                 int marginBetween, boolean isAnimatedPack, boolean animationsEnabled,
                                 boolean isGridMode) {
        this(stickers, packIdentifier, previewSize, marginBetween, isAnimatedPack, animationsEnabled, isGridMode, null);
    }

    public StickerPreviewAdapter(List<Sticker> stickers, String packIdentifier, int previewSize, int marginBetween, boolean isAnimatedPack, boolean animationsEnabled) {
        this(stickers, packIdentifier, previewSize, marginBetween, isAnimatedPack, animationsEnabled, false, null);
    }

    public void setScrolling(boolean isScrolling) {
        if (this.isScrolling != isScrolling) {
            this.isScrolling = isScrolling;
            notifyItemRangeChanged(0, getItemCount(), "scroll_state_change");
        }
    }

    public void updateData(List<Sticker> newStickers, String newPackId,
                           int newPreviewSize, int newMargin,
                           boolean newIsAnimated, boolean newAnimEnabled, boolean newIsScrolling) {
        boolean packChanged = !newPackId.equals(packIdentifier);
        this.stickers = newStickers;
        this.packIdentifier = newPackId;
        this.previewSize = newPreviewSize;
        this.marginBetween = newMargin;
        this.isAnimatedPack = newIsAnimated;
        this.animationsEnabled = newAnimEnabled;
        this.isScrolling = newIsScrolling;
        
        if (packChanged) {
            notifyDataSetChanged();
        } else {
            notifyItemRangeChanged(0, getItemCount(), "scroll_state_change");
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
        if (payloads.contains("scroll_state_change")) {
            updateContentState(holder);
        } else {
            super.onBindViewHolder(holder, position, payloads);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Sticker sticker = stickers.get(position);
        holder.sticker = sticker;
        
        String thumbName = "thumbs/thumb_" + sticker.imageFileName;
        int decodeSize = isGridMode ? previewSize : LIST_DECODE_SIZE_PX;
        String cacheKey = packIdentifier + "/" + (isGridMode ? sticker.imageFileName : thumbName) + "@" + decodeSize;

        if (cacheKey.equals(holder.boundCacheKey)) {
            updateContentState(holder);
            return;
        }

        holder.bindToken++;
        long token = holder.bindToken;
        cancelDecode(holder);
        
        holder.boundCacheKey = cacheKey;
        holder.errorView.setVisibility(View.GONE);
        holder.draweeView.setController(null);
        holder.draweeView.setVisibility(View.GONE);

        final Uri thumbUri = StickerPackLoader.getStickerAssetUri(packIdentifier, thumbName);
        final Uri fullUri = StickerPackLoader.getStickerAssetUri(packIdentifier, sticker.imageFileName);

        setupInteractions(holder, sticker, fullUri);

        Bitmap cached = bitmapCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            holder.bitmapView.setImageBitmap(cached);
            holder.bitmapView.setVisibility(View.VISIBLE);
            holder.skeletonView.setVisibility(View.GONE);
        } else {
            holder.bitmapView.setImageDrawable(null);
            holder.bitmapView.setVisibility(View.GONE);
            holder.skeletonView.setVisibility(View.VISIBLE);
            holder.skeletonView.startShimmer();
            bindStaticSticker(holder, isGridMode ? fullUri : thumbUri, fullUri, decodeSize, cacheKey, token);
        }

        if (sticker.isAnimated && animationsEnabled && !isScrolling) {
            // In list mode, use thumbnail for animated preview to keep resolution low.
            Uri animatedSourceUri = isGridMode ? fullUri : thumbUri;
            int animatedDecodeSize = isGridMode ? previewSize : LIST_ANIMATED_DECODE_SIZE_PX;
            bindAnimatedSticker(holder, animatedSourceUri, fullUri, animatedDecodeSize, token);
        }

        applyLayout(holder, position);
    }

    private void updateContentState(@NonNull ViewHolder holder) {
        if (holder.sticker == null) return;

        if (!isScrolling && animationsEnabled && holder.sticker.isAnimated) {
            if (holder.draweeView.getController() == null) {
                final Uri thumbUri = StickerPackLoader.getStickerAssetUri(packIdentifier, "thumbs/thumb_" + holder.sticker.imageFileName);
                final Uri fullUri = StickerPackLoader.getStickerAssetUri(packIdentifier, holder.sticker.imageFileName);
                Uri animatedSourceUri = isGridMode ? fullUri : thumbUri;
                int animatedDecodeSize = isGridMode ? previewSize : LIST_ANIMATED_DECODE_SIZE_PX;
                bindAnimatedSticker(holder, animatedSourceUri, fullUri, animatedDecodeSize, holder.bindToken);
            } else {
                DraweeController controller = holder.draweeView.getController();
                if (controller.getAnimatable() != null) controller.getAnimatable().start();
            }
        } else {
            DraweeController controller = holder.draweeView.getController();
            if (controller != null && controller.getAnimatable() != null) {
                controller.getAnimatable().stop();
            }
        }
    }

    private void setupInteractions(@NonNull ViewHolder holder, Sticker sticker, Uri fullUri) {
        // Single tap — show the expanded preview immediately
        holder.itemView.setOnClickListener(v -> {
            if (interactionListener != null) {
                interactionListener.onStickerHoldStarted(sticker, fullUri, sticker.isAnimated);
            }
        });
        // Long press — same expanded preview (kept for muscle memory)
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
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    interactionListener.onStickerHoldEnded();
                }
            }
            return false;
        });
    }

    private void bindAnimatedSticker(@NonNull final ViewHolder holder, @NonNull final Uri animatedSourceUri,
                                     @NonNull final Uri fullUri,
                                     final int decodeSize, final long token) {
        holder.draweeView.setVisibility(View.VISIBLE);
        holder.draweeView.getHierarchy().setActualImageScaleType(ScalingUtils.ScaleType.FIT_CENTER);
        
        // For animated stickers, use the source directly (thumb in list, full in grid)
        ImageRequest mainRequest = ImageRequestBuilder.newBuilderWithSource(animatedSourceUri)
                .setResizeOptions(new ResizeOptions(decodeSize, decodeSize))
                .build();

        DraweeController controller = Fresco.newDraweeControllerBuilder()
                .setImageRequest(mainRequest)
                .setAutoPlayAnimations(animationsEnabled && !isScrolling)
                .setControllerListener(new BaseControllerListener<ImageInfo>() {
                    @Override
                    public void onFinalImageSet(String id, @Nullable ImageInfo imageInfo, @Nullable android.graphics.drawable.Animatable animatable) {
                        if (holder.bindToken == token) {
                            holder.bitmapView.setVisibility(View.GONE);
                            holder.skeletonView.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public void onFailure(String id, Throwable throwable) {
                        if (holder.bindToken == token) {
                            holder.draweeView.setVisibility(View.GONE);
                            // If animated thumb cannot be loaded, fall back to low-res static decode.
                            bindStaticSticker(holder, animatedSourceUri, fullUri, decodeSize, holder.boundCacheKey, token);
                        }
                    }
                })
                .setOldController(holder.draweeView.getController())
                .build();

        holder.draweeView.setController(controller);
    }

    private void bindStaticSticker(@NonNull final ViewHolder holder, @NonNull final Uri fileUri, @Nullable final Uri fallbackUri,
                                   final int decodeSize, @NonNull final String cacheKey, final long token) {
        if (holder.bitmapView.getVisibility() == View.VISIBLE) return;

        holder.decodeFuture = decodeExecutor.submit(() -> {
            Bitmap decoded = null;
            try {
                decoded = StickerStaticDecoder.decode(holder.itemView.getContext().getApplicationContext(), fileUri, decodeSize, decodeSize);
            } catch (Throwable t) {
                if (fallbackUri != null) {
                    try {
                        decoded = StickerStaticDecoder.decode(holder.itemView.getContext().getApplicationContext(), fallbackUri, decodeSize, decodeSize);
                    } catch (Throwable t2) { decoded = null; }
                }
            }

            final Bitmap result = decoded;
            mainHandler.post(() -> {
                if (holder.bindToken != token || !cacheKey.equals(holder.boundCacheKey)) return;
                if (result == null) {
                    showDecodeError(holder);
                    return;
                }
                bitmapCache.put(cacheKey, result);
                holder.bitmapView.setImageBitmap(result);
                holder.bitmapView.setVisibility(View.VISIBLE);
                holder.skeletonView.stopShimmer();
                holder.skeletonView.setVisibility(View.GONE);
            });
        });
    }

    private void applyLayout(@NonNull ViewHolder holder, int position) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.itemView.getLayoutParams();
        if (lp == null) lp = new ViewGroup.MarginLayoutParams(previewSize, previewSize);
        lp.width = previewSize;
        lp.height = previewSize;
        if (isGridMode) {
            int half = marginBetween / 2;
            lp.topMargin = half; lp.bottomMargin = half;
            lp.setMarginStart(half); lp.setMarginEnd(half);
        } else {
            lp.topMargin = 0; lp.bottomMargin = 0;
            lp.setMarginEnd(0); lp.setMarginStart(position > 0 ? marginBetween : 0);
        }
        holder.itemView.setLayoutParams(lp);
    }

    private void showDecodeError(@NonNull ViewHolder holder) {
        holder.bitmapView.setVisibility(View.GONE);
        holder.draweeView.setVisibility(View.GONE);
        holder.errorView.setVisibility(View.VISIBLE);
        holder.skeletonView.stopShimmer();
        holder.skeletonView.setVisibility(View.GONE);
    }

    private void cancelDecode(@NonNull ViewHolder holder) {
        if (holder.decodeFuture != null) {
            holder.decodeFuture.cancel(true);
            holder.decodeFuture = null;
        }
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        cancelDecode(holder);
        holder.bindToken++;
        holder.boundCacheKey = "";
        holder.sticker = null;
        holder.bitmapView.setImageDrawable(null);
        holder.draweeView.setController(null);
        holder.skeletonView.stopShimmer();
        holder.skeletonView.setVisibility(View.GONE);
        holder.errorView.setVisibility(View.GONE);
    }

    @Override
    public int getItemCount() {
        return stickers == null ? 0 : stickers.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ShimmerFrameLayout skeletonView;
        final ImageView bitmapView;
        final SimpleDraweeView draweeView;
        final ImageView errorView;
        @Nullable Sticker sticker;
        @Nullable Future<?> decodeFuture;
        long bindToken;
        @NonNull String boundCacheKey = "";

        ViewHolder(View itemView) {
            super(itemView);
            this.skeletonView = itemView.findViewById(R.id.sticker_skeleton);
            this.bitmapView = itemView.findViewById(R.id.sticker_bitmap_preview);
            this.draweeView = itemView.findViewById(R.id.sticker_pack_list_item_image);
            this.errorView = itemView.findViewById(R.id.sticker_preview_error);
            this.draweeView.getHierarchy().setFadeDuration(0);
        }
    }
}
