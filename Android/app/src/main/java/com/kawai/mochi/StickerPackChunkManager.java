package com.kawai.mochi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages splitting a large sticker pack (>30 stickers) into ≤30-sticker
 * chunks for sending to WhatsApp.
 *
 * <p>WhatsApp enforces a hard limit of 30 stickers per pack. Packs are stored
 * locally without this limit; chunks are generated dynamically in-memory
 * when adding to WhatsApp and serving queries.
 */
public class StickerPackChunkManager {

    private static final String TAG = "StickerPackChunkMgr";
    static final int CHUNK_SIZE = 30;
    /** Prefix appended to the original identifier for chunk packs. */
    static final String CHUNK_SUFFIX = "_chunk_";

    /**
     * Returns true when {@code pack} has more stickers than WhatsApp allows in
     * a single pack and therefore needs to be split before adding.
     */
    public static boolean needsChunking(StickerPack pack) {
        List<Sticker> stickers = pack.getStickers();
        return stickers != null && stickers.size() > CHUNK_SIZE;
    }

    /**
     * Splits the sticker list of {@code original} into sub-lists of at most
     * {@link #CHUNK_SIZE} stickers. Does not write anything to disk.
     *
     * @return list of {@code StickerPack} objects; each shares the same
     *         metadata as {@code original} but has a derived identifier and a
     *         sub-list of stickers.
     */
    public static List<StickerPack> splitIntoChunks(StickerPack original) {
        List<Sticker> all = original.getStickers();
        if (all == null || all.isEmpty()) return Collections.emptyList();

        List<StickerPack> chunks = new ArrayList<>();
        int total = all.size();
        int chunkIndex = 0;

        for (int start = 0; start < total; start += CHUNK_SIZE) {
            int end = Math.min(start + CHUNK_SIZE, total);
            List<Sticker> slice = new ArrayList<>(all.subList(start, end));

            String chunkId = original.identifier + CHUNK_SUFFIX + chunkIndex;
            String chunkName = original.name;

            StickerPack chunk = new StickerPack(
                    chunkId,
                    chunkName,
                    original.publisher,
                    original.trayImageFile,
                    original.publisherEmail,
                    original.publisherWebsite,
                    original.privacyPolicyWebsite,
                    original.licenseAgreementWebsite,
                    original.imageDataVersion,
                    original.avoidCache,
                    original.animatedStickerPack
            );
            chunk.setStickers(slice);
            chunks.add(chunk);
            chunkIndex++;
        }
        return chunks;
    }

    /** Returns the number of chunks needed for {@code totalStickers}. */
    public static int numChunks(int totalStickers) {
        return (totalStickers + CHUNK_SIZE - 1) / CHUNK_SIZE;
    }
}
