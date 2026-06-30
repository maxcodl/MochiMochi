/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.kawai.mochi;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.kawai.mochi.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class StickerContentProvider extends ContentProvider {
    
    
    public static final String STICKER_PACK_IDENTIFIER_IN_QUERY = "sticker_pack_identifier";
    public static final String STICKER_PACK_NAME_IN_QUERY = "sticker_pack_name";
    public static final String STICKER_PACK_PUBLISHER_IN_QUERY = "sticker_pack_publisher";
    public static final String STICKER_PACK_ICON_IN_QUERY = "sticker_pack_icon";
    public static final String ANDROID_APP_DOWNLOAD_LINK_IN_QUERY = "android_play_store_link";
    public static final String IOS_APP_DOWNLOAD_LINK_IN_QUERY = "ios_app_download_link";
    public static final String PUBLISHER_EMAIL = "sticker_pack_publisher_email";
    public static final String PUBLISHER_WEBSITE = "sticker_pack_publisher_website";
    public static final String PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website";
    public static final String LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website";
    public static final String IMAGE_DATA_VERSION = "image_data_version";
    public static final String AVOID_CACHE = "whatsapp_will_not_cache_stickers";
    public static final String ANIMATED_STICKER_PACK = "animated_sticker_pack";

    public static final String STICKER_FILE_NAME_IN_QUERY = "sticker_file_name";
    public static final String STICKER_FILE_EMOJI_IN_QUERY = "sticker_emoji";
    public static final String STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY = "sticker_accessibility_text";
    private static final String CONTENT_FILE_NAME = "contents.json";
    private static final String THUMB_MIRROR_DIR = "thumb_mirror";

    /**
     * Path to the local (internal storage) mirror directory for a pack's
     * thumbnails. Used only when the sticker folder is a slow SAF location
     * (e.g. an SD card) — Fresco does not disk-cache local/content URIs, so
     * without this every cold start would re-read every thumbnail straight
     * from the SD card via SAF. Public so WastickerParser can clear stale
     * mirrors when thumbnails are deleted/regenerated.
     */
    public static File getThumbMirrorDir(@NonNull Context context, @NonNull String identifier) {
        return new File(new File(context.getCacheDir(), THUMB_MIRROR_DIR), identifier);
    }

    public static final Uri AUTHORITY_URI = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(BuildConfig.CONTENT_PROVIDER_AUTHORITY).appendPath(StickerContentProvider.METADATA).build();

    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static final String METADATA = "metadata";
    private static final int METADATA_CODE = 1;
    private static final int METADATA_CODE_FOR_SINGLE_PACK = 2;
    static final String STICKERS = "stickers";
    private static final int STICKERS_CODE = 3;
    static final String STICKERS_ASSET = "stickers_asset";
    private static final int STICKERS_ASSET_CODE = 4;
    private static final int STICKER_PACK_TRAY_ICON_CODE = 5;

    private List<StickerPack> stickerPackList;
    private Map<String, StickerPack> stickerPackMap = new LinkedHashMap<>();
    private static final String TAG = "StickerContentProvider";

    private static StickerContentProvider instance;

    // SAF lookups (DocumentFile.findFile) are expensive IPC round-trips to the
    // document provider that list every child of a directory and scan for a
    // name match. Doing that per-asset (every thumbnail, tray icon, sticker)
    // made list loads take many seconds on custom SAF folders. Cache the
    // directory listings instead and only rebuild them when invalidated.
    private final Object safCacheLock = new Object();
    private String safCachedRootPath;
    private DocumentFile safCachedRoot;
    private Map<String, DocumentFile> safPackDirCache; // identifier -> pack DocumentFile (children of root)
    private final Map<String, Map<String, DocumentFile>> safPackFileCache = new java.util.HashMap<>(); // identifier -> fileName -> DocumentFile

    public static StickerContentProvider getInstance() {
        return instance;
    }

    @Override
    public boolean onCreate() {
        instance = this;
        final String authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY;
        MATCHER.addURI(authority, METADATA, METADATA_CODE);
        MATCHER.addURI(authority, METADATA + "/*", METADATA_CODE_FOR_SINGLE_PACK);
        MATCHER.addURI(authority, STICKERS + "/*", STICKERS_CODE);
        MATCHER.addURI(authority, STICKERS_ASSET + "/*/*", STICKERS_ASSET_CODE);
        // Allow one nested folder level (e.g. thumbs/thumb_1.webp) for fast list previews.
        MATCHER.addURI(authority, STICKERS_ASSET + "/*/*/*", STICKERS_ASSET_CODE);
        return true;
    }

    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        final int code = MATCHER.match(uri);
        if (code == METADATA_CODE) return getPackForAllStickerPacks(uri);
        else if (code == METADATA_CODE_FOR_SINGLE_PACK) return getCursorForSingleStickerPack(uri);
        else if (code == STICKERS_CODE) return getStickersForAStickerPack(uri);
        else throw new IllegalArgumentException("Unknown URI: " + uri);
    }

    @Nullable
    @Override
    public AssetFileDescriptor openAssetFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        final int matchCode = MATCHER.match(uri);
        if (matchCode == STICKERS_ASSET_CODE || matchCode == STICKER_PACK_TRAY_ICON_CODE) {
            return getImageAsset(uri);
        }
        return null;
    }

    @Override
    public String getType(@NonNull Uri uri) {
        final int matchCode = MATCHER.match(uri);
        switch (matchCode) {
            case METADATA_CODE: return "vnd.android.cursor.dir/vnd." + BuildConfig.CONTENT_PROVIDER_AUTHORITY + "." + METADATA;
            case METADATA_CODE_FOR_SINGLE_PACK: return "vnd.android.cursor.item/vnd." + BuildConfig.CONTENT_PROVIDER_AUTHORITY + "." + METADATA;
            case STICKERS_CODE: return "vnd.android.cursor.dir/vnd." + BuildConfig.CONTENT_PROVIDER_AUTHORITY + "." + STICKERS;
            case STICKERS_ASSET_CODE:
            case STICKER_PACK_TRAY_ICON_CODE: return getMimeType(uri);
            default: throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }

    private String getMimeType(Uri uri) {
        String lastSegment = uri.getLastPathSegment();
        if (lastSegment == null) return "image/webp";
        String extension = MimeTypeMap.getFileExtensionFromUrl(lastSegment);
        if (TextUtils.isEmpty(extension)) {
            int lastDot = lastSegment.lastIndexOf('.');
            if (lastDot != -1) extension = lastSegment.substring(lastDot + 1);
        }
        if ("png".equalsIgnoreCase(extension)) return "image/png";
        return "image/webp";
    }

    private synchronized void readContentFile(@NonNull Context context) {
        // First app load: materialize bundled asset packs into configured storage.
        WastickerParser.seedBundledPacksIfNeeded(context);

        List<StickerPack> loadedPacks = null;
        String folderPath = WastickerParser.getStickerFolderPath(context);
        if (WastickerParser.isCustomPathUri(context)) {
            DocumentFile root = getSafRoot(context, folderPath);
            if (root != null) {
                DocumentFile contents = root.findFile(CONTENT_FILE_NAME);
                if (contents != null) {
                    try (InputStream is = context.getContentResolver().openInputStream(contents.getUri())) {
                        loadedPacks = ContentFileParser.parseStickerPacks(is);
                    } catch (Exception e) { Log.w("StickerCP", "Failed to read SAF contents.json", e); }
                }
            }
        } else {
            File userContents = new File(folderPath, CONTENT_FILE_NAME);
            if (userContents.exists()) {
                try (InputStream fis = new FileInputStream(userContents)) {
                    loadedPacks = ContentFileParser.parseStickerPacks(fis);
                } catch (Exception e) { Log.w("StickerCP", "Failed to read File contents.json", e); }
            }
        }

        // Safety fallback: if storage read fails, still allow bundled asset packs.
        if (loadedPacks == null) {
            try (InputStream contentsInputStream = context.getAssets().open(CONTENT_FILE_NAME)) {
                loadedPacks = ContentFileParser.parseStickerPacks(contentsInputStream);
            } catch (IOException | IllegalStateException ignored) {
                loadedPacks = new ArrayList<>();
            }
        }

        LinkedHashMap<String, StickerPack> byId = new LinkedHashMap<>();
        for (StickerPack pack : loadedPacks) {
            byId.put(pack.identifier, pack);
        }
        stickerPackList = new ArrayList<>(byId.values());
        stickerPackMap = byId;
    }

    public synchronized void invalidateStickerPackList() {
        stickerPackList = null;
        synchronized (safCacheLock) {
            safCachedRootPath = null;
            safCachedRoot = null;
            safPackDirCache = null;
            safPackFileCache.clear();
        }
    }

    /**
     * Lighter-weight than {@link #invalidateStickerPackList()}: only clears the
     * SAF directory-listing caches, not the parsed pack metadata. Call this
     * after creating a brand-new pack directory via SAF mid-session (e.g.
     * during import), so the next cached lookup picks it up instead of
     * working from a stale listing that predates the new folder.
     */
    public void invalidateSafDirCache() {
        synchronized (safCacheLock) {
            safPackDirCache = null;
            safPackFileCache.clear();
        }
    }

    /**
     * Returns (and caches) the SAF root DocumentFile, rebuilding only if the
     * configured folder path changed or the cache was invalidated.
     */
    private DocumentFile getSafRoot(@NonNull Context context, @NonNull String folderPath) {
        synchronized (safCacheLock) {
            if (safCachedRoot != null && folderPath.equals(safCachedRootPath)) {
                return safCachedRoot;
            }
            safCachedRoot = DocumentFile.fromTreeUri(context, Uri.parse(folderPath));
            safCachedRootPath = folderPath;
            safPackDirCache = null;
            safPackFileCache.clear();
            return safCachedRoot;
        }
    }

    /**
     * Deletes the local mirror directory for a given pack (thumbnails cached from SAF).
     * Called when a pack is deleted or when thumbnails are regenerated.
     */
    public static void deleteMirrorPack(Context context, String packId) {
        File mirrorRoot = new File(context.getCacheDir(), "thumb_mirror");
        if (!mirrorRoot.exists()) return;
        File packMirror = new File(mirrorRoot, packId);
        if (packMirror.exists()) {
            deleteRecursive(packMirror);
            Log.d("StickerContentProvider", "Deleted mirror cache for pack: " + packId);
        }
    }

    private static void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDir.delete();
    }

    /**
     * Returns (and caches) a map of pack identifier -> pack directory, built
     * from a single listFiles() call on the root instead of one findFile()
     * call per pack.
     */
    private Map<String, DocumentFile> getSafPackDirs(@NonNull Context context, @NonNull String folderPath) {
        synchronized (safCacheLock) {
            if (safPackDirCache != null) return safPackDirCache;
            Map<String, DocumentFile> dirs = new java.util.HashMap<>();
            DocumentFile root = getSafRoot(context, folderPath);
            if (root != null) {
                for (DocumentFile child : root.listFiles()) {
                    String name = child.getName();
                    if (name != null && child.isDirectory()) {
                        dirs.put(name, child);
                    }
                }
            }
            safPackDirCache = dirs;
            return dirs;
        }
    }

    /**
     * Returns (and caches) a map of fileName -> DocumentFile for a given pack,
     * built from a single listFiles() call on the pack directory instead of
     * one findFile() call per asset.
     */
    private Map<String, DocumentFile> getSafPackFiles(@NonNull Context context, @NonNull String folderPath, @NonNull String identifier) {
        synchronized (safCacheLock) {
            Map<String, DocumentFile> cached = safPackFileCache.get(identifier);
            if (cached != null) return cached;
            Map<String, DocumentFile> files = new java.util.HashMap<>();
            DocumentFile packDir = getSafPackDirs(context, folderPath).get(identifier);
            if (packDir != null) {
                for (DocumentFile f : packDir.listFiles()) {
                    String name = f.getName();
                    if (name != null && !f.isDirectory()) {
                        files.put(name, f);
                    }
                }
            }
            safPackFileCache.put(identifier, files);
            return files;
        }
    }

    /**
     * Cache-coherent lookup (and optional creation) of a file inside a pack's
     * SAF directory. Used by WastickerParser's thumbnail generation to avoid
     * the findFile()/findFile()/createFile() chain (2-3 SAF IPC round-trips
     * per sticker) that made bulk thumbnail generation slow on SD-card-backed
     * folders. Falls back to a plain (uncached) lookup if this provider
     * instance hasn't been created yet.
     *
     * @param mimeType if non-null and the file doesn't exist, it will be
     *                 created with this mime type and added to the cache.
     *                 If null, this is a lookup-only call.
     */
    @Nullable
    public DocumentFile getOrCreateSafFileCached(@NonNull Context context, @NonNull String packId,
                                                 @NonNull String fileName, @Nullable String mimeType) {
        String folderPath = WastickerParser.getStickerFolderPath(context);
        synchronized (safCacheLock) {
            Map<String, DocumentFile> files = getSafPackFiles(context, folderPath, packId);
            DocumentFile existing = files.get(fileName);
            if (existing != null) return existing;

            if (mimeType == null) {
                // Lookup-only miss: the file might have just been written outside
                // this cache (e.g. mid-import, before the cache knew about it).
                // Self-heal with one direct check rather than silently failing.
                DocumentFile packDir = getSafPackDirs(context, folderPath).get(packId);
                if (packDir == null) return null;
                DocumentFile direct = packDir.findFile(fileName);
                if (direct != null) files.put(fileName, direct);
                return direct;
            }

            DocumentFile packDir = getSafPackDirs(context, folderPath).get(packId);
            if (packDir == null) return null;
            DocumentFile created = packDir.createFile(mimeType, fileName);
            if (created != null) {
                files.put(fileName, created);
            }
            return created;
        }
    }

    private List<StickerPack> getStickerPackList() {
        if (stickerPackList == null) readContentFile(Objects.requireNonNull(getContext()));
        return stickerPackList;
    }

    private Map<String, StickerPack> getStickerPackMap() {
        if (stickerPackList == null) readContentFile(Objects.requireNonNull(getContext()));
        return stickerPackMap;
    }

    private boolean isCallerSelf() {
        Context context = getContext();
        if (context == null) return true;
        try {
            String callingPackage = getCallingPackage();
            if (callingPackage == null) {
                return true;
            }
            return callingPackage.equals(context.getPackageName());
        } catch (Exception e) {
            return true;
        }
    }

    private List<StickerPack> getStickerPackListForQuery() {
        List<StickerPack> all = getStickerPackList();
        if (isCallerSelf()) {
            return all;
        }

        List<StickerPack> processed = new ArrayList<>();
        for (StickerPack pack : all) {
            if (StickerPackChunkManager.needsChunking(pack)) {
                processed.addAll(StickerPackChunkManager.splitIntoChunks(pack));
            } else {
                processed.add(pack);
            }
        }
        return processed;
    }

    private StickerPack getStickerPackByIdentifier(String identifier) {
        if (identifier == null) return null;
        StickerPack pack = getStickerPackMap().get(identifier);
        if (pack != null) return pack;

        if (isChunkIdentifier(identifier)) {
            String originalId = getOriginalIdentifier(identifier);
            if (originalId != null) {
                StickerPack originalPack = getStickerPackMap().get(originalId);
                if (originalPack != null) {
                    List<StickerPack> chunks = StickerPackChunkManager.splitIntoChunks(originalPack);
                    for (StickerPack chunk : chunks) {
                        if (identifier.equals(chunk.identifier)) {
                            return chunk;
                        }
                    }
                }
            }
        }
        return null;
    }

    private Cursor getPackForAllStickerPacks(@NonNull Uri uri) {
        return getStickerPackInfo(uri, getStickerPackListForQuery());
    }

    private Cursor getCursorForSingleStickerPack(@NonNull Uri uri) {
        final String identifier = uri.getLastPathSegment();
        StickerPack stickerPack = getStickerPackByIdentifier(identifier);
        if (stickerPack != null) {
            return getStickerPackInfo(uri, Collections.singletonList(stickerPack));
        }
        return getStickerPackInfo(uri, new ArrayList<>());
    }

    @NonNull
    private Cursor getStickerPackInfo(@NonNull Uri uri, @NonNull List<StickerPack> stickerPackList) {
        MatrixCursor cursor = new MatrixCursor(new String[]{
                STICKER_PACK_IDENTIFIER_IN_QUERY, STICKER_PACK_NAME_IN_QUERY, STICKER_PACK_PUBLISHER_IN_QUERY,
                STICKER_PACK_ICON_IN_QUERY, ANDROID_APP_DOWNLOAD_LINK_IN_QUERY, IOS_APP_DOWNLOAD_LINK_IN_QUERY,
                PUBLISHER_EMAIL, PUBLISHER_WEBSITE, PRIVACY_POLICY_WEBSITE, LICENSE_AGREEMENT_WEBSITE,
                IMAGE_DATA_VERSION, AVOID_CACHE, ANIMATED_STICKER_PACK,
        });
        for (StickerPack stickerPack : stickerPackList) {
            MatrixCursor.RowBuilder builder = cursor.newRow();
            builder.add(stickerPack.identifier); builder.add(stickerPack.name); builder.add(stickerPack.publisher);
            builder.add(stickerPack.trayImageFile); builder.add(stickerPack.androidPlayStoreLink); builder.add(stickerPack.iosAppStoreLink);
            builder.add(stickerPack.publisherEmail); builder.add(stickerPack.publisherWebsite); builder.add(stickerPack.privacyPolicyWebsite);
            builder.add(stickerPack.licenseAgreementWebsite); builder.add(stickerPack.imageDataVersion);
            builder.add(stickerPack.avoidCache ? 1 : 0); builder.add(stickerPack.animatedStickerPack ? 1 : 0);
        }
        cursor.setNotificationUri(Objects.requireNonNull(getContext()).getContentResolver(), uri);
        return cursor;
    }

    @NonNull
    private Cursor getStickersForAStickerPack(@NonNull Uri uri) {
        final String identifier = uri.getLastPathSegment();
        MatrixCursor cursor = new MatrixCursor(new String[]{STICKER_FILE_NAME_IN_QUERY, STICKER_FILE_EMOJI_IN_QUERY, STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY});
        StickerPack stickerPack = getStickerPackByIdentifier(identifier);
        if (stickerPack != null) {
            for (Sticker sticker : stickerPack.getStickers()) {
                cursor.addRow(new Object[]{sticker.imageFileName, TextUtils.join(",", sticker.emojis), sticker.accessibilityText});
            }
        }
        cursor.setNotificationUri(Objects.requireNonNull(getContext()).getContentResolver(), uri);
        return cursor;
    }

    private AssetFileDescriptor getImageAsset(Uri uri) throws FileNotFoundException {
        final List<String> pathSegments = uri.getPathSegments();
        if (pathSegments.size() < 3) {
            return null;
        }

        int baseIndex = pathSegments.indexOf(STICKERS_ASSET);
        if (baseIndex == -1 || baseIndex + 2 >= pathSegments.size()) {
            return null;
        }

        final String identifier = pathSegments.get(baseIndex + 1);
        StringBuilder fileBuilder = new StringBuilder();
        for (int i = baseIndex + 2; i < pathSegments.size(); i++) {
            if (i > baseIndex + 2) {
                fileBuilder.append('/');
            }
            fileBuilder.append(pathSegments.get(i));
        }
        String fileName = fileBuilder.toString();

        StickerPack stickerPack = getStickerPackByIdentifier(identifier);
        if (stickerPack != null) {
            if (fileName.equals(stickerPack.trayImageFile)) return fetchFile(uri, fileName, identifier);

            // Fast-path: check if it's a thumbnail request and handle it
            boolean isThumb = fileName.startsWith("thumbs/thumb_");
            String originalFileName = isThumb ? fileName.substring("thumbs/thumb_".length()) : fileName;

            for (Sticker sticker : stickerPack.getStickers()) {
                if (originalFileName.equals(sticker.imageFileName)) {
                    if (isThumb) {
                        try {
                            AssetFileDescriptor afd = fetchFile(uri, fileName, identifier);
                            if (afd != null) return afd;
                        } catch (FileNotFoundException ignored) {}
                        // Do not fall back to original for thumbnail requests.
                        // This prevents high-res files from being served when list previews request thumbs.
                        throw new FileNotFoundException("Missing thumbnail: " + identifier + "/" + fileName);
                    } else {
                        return fetchFile(uri, fileName, identifier);
                    }
                }
            }
        }

        // Best-effort fallback: if metadata is stale (e.g., chunk just added),
        // try to serve the asset directly from storage.
        try {
            return fetchFile(uri, fileName, identifier);
        } catch (FileNotFoundException ignored) {
            return null;
        }
    }

    private AssetFileDescriptor fetchFile(@NonNull Uri uri, @NonNull String fileName, @NonNull String identifier) throws FileNotFoundException {
        Context context = getContext(); if (context == null) return null;
        String folderPath = WastickerParser.getStickerFolderPath(context);

        String resolvedIdentifier = identifier;
        if (isChunkIdentifier(identifier)) {
            String originalId = getOriginalIdentifier(identifier);
            if (originalId != null) {
                resolvedIdentifier = originalId;
            }
        }

        if (WastickerParser.isCustomPathUri(context)) {
            // Thumbnails are read in bulk every time a list/grid is shown. Fresco
            // does not disk-cache local/content URIs, so on an SD-card-backed SAF
            // folder every cold start would otherwise re-fetch every thumbnail via
            // slow SAF IPC. Mirror them into internal storage (real fast disk) on
            // first read; after that, serve straight from the mirror.
            if (fileName.startsWith("thumb_")) {
                File mirror = new File(getThumbMirrorDir(context, resolvedIdentifier), fileName);
                if (mirror.exists()) {
                    try {
                        return new AssetFileDescriptor(ParcelFileDescriptor.open(mirror, ParcelFileDescriptor.MODE_READ_ONLY), 0, mirror.length());
                    } catch (IOException e) {
                        // Mirror file vanished/corrupt; fall through and re-fetch + re-mirror below.
                    }
                }
                try {
                    DocumentFile file = getSafPackFiles(context, folderPath, resolvedIdentifier).get(fileName);
                    if (file != null) {
                        File parent = mirror.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        try (InputStream is = context.getContentResolver().openInputStream(file.getUri());
                             OutputStream os = new FileOutputStream(mirror)) {
                            if (is != null) {
                                byte[] buffer = new byte[8192];
                                int len;
                                while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
                            }
                        }
                        if (mirror.exists()) {
                            return new AssetFileDescriptor(ParcelFileDescriptor.open(mirror, ParcelFileDescriptor.MODE_READ_ONLY), 0, mirror.length());
                        }
                    }
                } catch (Exception e) {
                    Log.w("StickerCP", "Failed to mirror thumbnail locally, serving from SAF directly: " + fileName, e);
                }
                // Mirroring failed; fall through to serve directly from SAF below.
            }
            try {
                DocumentFile file = getSafPackFiles(context, folderPath, resolvedIdentifier).get(fileName);
                if (file != null) {
                    return context.getContentResolver().openAssetFileDescriptor(file.getUri(), "r");
                }
                AssetFileDescriptor fallback = tryFetchChunkTrayFallback(context, folderPath, identifier, fileName);
                if (fallback != null) return fallback;
            } catch (Exception e) { Log.e("StickerCP", "SAF fetch failed", e); }
        } else {
            try {
                File userFile = new File(new File(folderPath, resolvedIdentifier), fileName);
                if (userFile.exists()) {
                    return new AssetFileDescriptor(ParcelFileDescriptor.open(userFile, ParcelFileDescriptor.MODE_READ_ONLY), 0, userFile.length());
                }
                AssetFileDescriptor fallback = tryFetchChunkTrayFallback(folderPath, identifier, fileName);
                if (fallback != null) return fallback;
            } catch (IOException ignored) {}
        }

        try {
            AssetFileDescriptor afd = context.getAssets().openFd(resolvedIdentifier + "/" + fileName);
            return afd;
        } catch (IOException ignored) {
            // If it's not in assets and not in the user folder, it truly doesn't exist
            throw new FileNotFoundException(resolvedIdentifier + "/" + fileName);
        }
    }

    @Override public int delete(@NonNull Uri uri, @Nullable String s, String[] sa) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(@NonNull Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(@NonNull Uri uri, ContentValues v, String s, String[] sa) { throw new UnsupportedOperationException(); }

    @Nullable
    private AssetFileDescriptor tryFetchChunkTrayFallback(@NonNull Context context, @NonNull String folderPath,
                                                         @NonNull String identifier, @NonNull String fileName) {
        if (!isChunkIdentifier(identifier)) return null;
        if (!fileName.equals(getTrayFileNameForIdentifier(identifier))) return null;

        String originalId = getOriginalIdentifier(identifier);
        if (originalId == null) return null;

        DocumentFile file = getSafPackFiles(context, folderPath, originalId).get(fileName);
        if (file == null) return null;
        try {
            return context.getContentResolver().openAssetFileDescriptor(file.getUri(), "r");
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Nullable
    private AssetFileDescriptor tryFetchChunkTrayFallback(@NonNull String folderPath,
                                                         @NonNull String identifier, @NonNull String fileName) {
        if (!isChunkIdentifier(identifier)) return null;
        if (!fileName.equals(getTrayFileNameForIdentifier(identifier))) return null;

        String originalId = getOriginalIdentifier(identifier);
        if (originalId == null) return null;
        File userFile = new File(new File(folderPath, originalId), fileName);
        if (!userFile.exists()) return null;
        try {
            return new AssetFileDescriptor(ParcelFileDescriptor.open(userFile, ParcelFileDescriptor.MODE_READ_ONLY), 0, userFile.length());
        } catch (IOException e) {
            return null;
        }
    }

    private boolean isChunkIdentifier(@NonNull String identifier) {
        return identifier.contains(StickerPackChunkManager.CHUNK_SUFFIX);
    }

    @Nullable
    private String getOriginalIdentifier(@NonNull String identifier) {
        int idx = identifier.indexOf(StickerPackChunkManager.CHUNK_SUFFIX);
        if (idx <= 0) return null;
        return identifier.substring(0, idx);
    }

    @NonNull
    private String getTrayFileNameForIdentifier(@NonNull String identifier) {
        StickerPack pack = getStickerPackMap().get(identifier);
        if (pack != null && !TextUtils.isEmpty(pack.trayImageFile)) return pack.trayImageFile;
        return "tray.png";
    }
}