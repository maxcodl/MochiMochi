package com.kawai.mochi;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import com.kawai.mochi.R;

import java.util.List;

public class StickerPreviewAdapter extends RecyclerView.Adapter<StickerPreviewAdapter.ViewHolder> {
    private List<Sticker> stickers;
    private String packIdentifier;
    private int previewSize;
    private int marginBetween;
    private boolean isAnimatedPack;
    private boolean animationsEnabled;
    private boolean isScrolling;
    /** True when used in a GridLayoutManager (details view); false for the horizontal list strip. */
    private final boolean isGridMode;

    public StickerPreviewAdapter(List<Sticker> stickers, String packIdentifier, int previewSize, int marginBetween, boolean isAnimatedPack, boolean animationsEnabled, boolean isGridMode) {
        this.stickers = stickers;
        this.packIdentifier = packIdentifier;
        this.previewSize = previewSize;
        this.marginBetween = marginBetween;
        this.isAnimatedPack = isAnimatedPack;
        this.animationsEnabled = animationsEnabled;
        this.isGridMode = isGridMode;
        setHasStableIds(true);
    }

    /** Convenience constructor for horizontal list strip mode (isGridMode = false). */
    public StickerPreviewAdapter(List<Sticker> stickers, String packIdentifier, int previewSize, int marginBetween, boolean isAnimatedPack, boolean animationsEnabled) {
        this(stickers, packIdentifier, previewSize, marginBetween, isAnimatedPack, animationsEnabled, false);
    }

    public void setScrolling(boolean isScrolling) {
        this.isScrolling = isScrolling;
    }

    /**
     * Update the adapter's data in-place.
     * If the pack identifier is unchanged, images are still in Fresco's memory cache and no
     * rebind is needed. Only triggers a full rebind when the actual pack changes.
     */
    public void updateData(List<Sticker> newStickers, String newPackId,
                           int newPreviewSize, int newMargin,
                           boolean newIsAnimated, boolean newAnimEnabled, boolean newIsScrolling) {
        boolean packChanged = !newPackId.equals(packIdentifier)
                || newIsAnimated != isAnimatedPack
                || newAnimEnabled != animationsEnabled
                || newIsScrolling != isScrolling;
        this.stickers = newStickers;
        this.packIdentifier = newPackId;
        this.previewSize = newPreviewSize;
        this.marginBetween = newMargin;
        this.isAnimatedPack = newIsAnimated;
        this.animationsEnabled = newAnimEnabled;
        this.isScrolling = newIsScrolling;
        if (packChanged) {
            notifyDataSetChanged();
        }
    }

    @Override
    public long getItemId(int position) {
        return stickers.get(position).imageFileName.hashCode();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        SimpleDraweeView view = (SimpleDraweeView) LayoutInflater.from(parent.getContext())
                .inflate(R.layout.sticker_packs_list_image_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Sticker sticker = stickers.get(position);
        
        // Use pre-generated thumbnail if in list mode
        String fileName = isGridMode ? sticker.imageFileName : "thumbs/thumb_" + sticker.imageFileName;
        final Uri fileUri = StickerPackLoader.getStickerAssetUri(packIdentifier, fileName);
        
        // Set render resolution: 96px for list strip (Retina-sharp for small previews), full previewSize for grid
        int decodeSize = isGridMode ? previewSize : 96;

        ImageRequest request = ImageRequestBuilder.newBuilderWithSource(fileUri)
                .setResizeOptions(new ResizeOptions(decodeSize, decodeSize))
                .build();

        DraweeController controller = Fresco.newDraweeControllerBuilder()
                .setImageRequest(request)
                .setAutoPlayAnimations(animationsEnabled && !isScrolling)
                .setOldController(holder.draweeView.getController())
                .build();
        
        holder.draweeView.setController(controller);

        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.draweeView.getLayoutParams();
        lp.width = previewSize;
        lp.height = previewSize;
        if (isGridMode) {
            // In grid mode apply a uniform half-margin on every side so inter-item gaps are
            // consistent both horizontally and vertically.
            int half = marginBetween / 2;
            lp.topMargin = half;
            lp.bottomMargin = half;
            lp.setMarginStart(half);
            lp.setMarginEnd(half);
        } else {
            // Horizontal list strip: only separate items from each other with a start margin.
            lp.topMargin = 0;
            lp.bottomMargin = 0;
            lp.setMarginEnd(0);
            lp.setMarginStart(position > 0 ? marginBetween : 0);
        }
        holder.draweeView.setLayoutParams(lp);
    }

    @Override
    public int getItemCount() {
        return stickers == null ? 0 : stickers.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final SimpleDraweeView draweeView;

        ViewHolder(SimpleDraweeView itemView) {
            super(itemView);
            this.draweeView = itemView;
        }
    }
}
