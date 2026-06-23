package com.kawai.mochi;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.kawai.mochi.R;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImportIndividualStickerActivity extends BaseActivity {
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private LinearProgressIndicator progressBar;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import_individual_sticker);
        
        progressBar = findViewById(R.id.import_progress_bar);
        statusText = findViewById(R.id.import_status_text);

        Intent intent = getIntent();
        Uri uri = null;

        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
                uri = intent.getClipData().getItemAt(0).getUri();
            }
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        }

        if (uri == null) {
            Toast.makeText(this, R.string.no_file_provided, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        extractAndShow(uri);
    }

    static class ExtractResult {
        File tempDir;
        List<File> webpFiles;
        String defaultTitle;
        String defaultAuthor;
        ArrayList<StickerPack> eligiblePacks;
        String error;
    }

    private void extractAndShow(Uri uri) {
        if (statusText != null) statusText.setText(R.string.importing_pack);
        WeakReference<ImportIndividualStickerActivity> ref = new WeakReference<>(this);
        executor.execute(() -> {
            ImportIndividualStickerActivity activity = ref.get();
            if (activity == null) return;
            ExtractResult result = extractSticker(activity, uri);
            mainHandler.post(() -> {
                ImportIndividualStickerActivity act = ref.get();
                if (act == null) return;
                if (result.error != null) {
                    Toast.makeText(act, getString(R.string.error_with_message, result.error), Toast.LENGTH_LONG).show();
                    act.finish();
                    return;
                }
                if (result.webpFiles.isEmpty()) {
                    Toast.makeText(act, R.string.error_no_stickers_found, Toast.LENGTH_SHORT).show();
                    act.finish();
                    return;
                }
                if (SettingsActivity.isAskPackPickerEnabled(act) && !result.eligiblePacks.isEmpty()) {
                    act.showPackPicker(result);
                } else {
                    act.autoImport(result);
                }
            });
        });
    }

    private static ExtractResult extractSticker(ImportIndividualStickerActivity activity, Uri uri) {
        ExtractResult result = new ExtractResult();
        result.webpFiles = new ArrayList<>();
        result.defaultTitle = activity.getString(R.string.imported_pack_default_title);
        result.defaultAuthor = activity.getString(R.string.unknown_author);
        try {
            // Strictly allow only .wasticker and .idwasticker
            String fileName = null;
            try {
                fileName = getFileNameFromUri(activity, uri);
            } catch (Exception ignored) {}
            if (fileName == null) fileName = uri.getLastPathSegment();
            String lower = fileName != null ? fileName.toLowerCase() : "";
            if (!(lower.endsWith(".wasticker") || lower.endsWith(".idwasticker"))) {
                result.error = activity.getString(R.string.error_invalid_file_type);
                return result;
            }

            // Block ZIP files even if renamed
            try (InputStream is = activity.getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    result.error = activity.getString(R.string.error_cannot_open_file);
                    return result;
                }
                byte[] peek = new byte[2];
                int peekRead = is.read(peek);
                if (peekRead >= 2 && peek[0] == 0x50 && peek[1] == 0x4B) {
                    result.error = activity.getString(R.string.error_invalid_file_type);
                    return result;
                }
            }

            result.tempDir = new File(activity.getCacheDir(), "idwasticker_" + System.currentTimeMillis());
            if (!result.tempDir.exists() && !result.tempDir.mkdirs()) {
                result.error = "Failed to create temp directory";
                return result;
            }

            File webpFile = new File(result.tempDir, "sticker_" + System.currentTimeMillis() + ".webp");
            try (InputStream is = activity.getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(webpFile)) {
                if (is == null) {
                    result.error = activity.getString(R.string.error_cannot_open_file);
                    return result;
                }
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) > 0) fos.write(buffer, 0, len);
            }

            result.webpFiles.add(webpFile);
            result.eligiblePacks = new ArrayList<>();
            try {
                ArrayList<StickerPack> allPacks = StickerPackLoader.fetchStickerPacks(activity);
                for (StickerPack pack : allPacks) {
                    if (pack.getStickers() != null && pack.getStickers().size() < 30) {
                        result.eligiblePacks.add(pack);
                    }
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            result.error = e.getMessage();
        }
        return result;
    }

    private static String getFileNameFromUri(ImportIndividualStickerActivity activity, Uri uri) {
        String result = null;
        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = activity.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            }
        }
        if (result == null) {
            String path = uri.getPath();
            if (path != null) {
                int cut = path.lastIndexOf('/');
                if (cut != -1 && cut + 1 < path.length()) {
                    result = path.substring(cut + 1);
                } else {
                    result = path;
                }
            }
        }
        return result;
    }

    private void showPackPicker(ExtractResult result) {
        List<String> options = new ArrayList<>();
        for (StickerPack pack : result.eligiblePacks) {
            options.add(getString(R.string.pack_count_format, pack.name, pack.getStickers().size()));
        }
        options.add(getString(R.string.create_new_pack_option));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_to_pack_title)
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    if (which < result.eligiblePacks.size()) {
                        StickerPack selectedPack = result.eligiblePacks.get(which);
                        addToExistingPack(result, selectedPack.identifier);
                    } else {
                        addToNewPack(result);
                    }
                })
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void autoImport(ExtractResult result) {
        if (!result.eligiblePacks.isEmpty()) {
            addToExistingPack(result, result.eligiblePacks.get(0).identifier);
        } else {
            addToNewPack(result);
        }
    }

    private void addToExistingPack(ExtractResult result, String identifier) {
        runAddSticker(result, identifier, false);
    }

    private void addToNewPack(ExtractResult result) {
        runAddSticker(result, null, true);
    }

    private void runAddSticker(ExtractResult result, String identifier, boolean createNew) {
        if (progressBar != null) {
            progressBar.setIndeterminate(false);
            progressBar.setMax(result.webpFiles.size());
            progressBar.setProgressCompat(0, true);
        }
        
        WeakReference<ImportIndividualStickerActivity> ref = new WeakReference<>(this);
        executor.execute(() -> {
            ImportIndividualStickerActivity activity = ref.get();
            if (activity == null) return;
            String error;
            try {
                if (createNew) {
                    if (!result.webpFiles.isEmpty()) {
                        String newId = WastickerParser.createPackWithSticker(
                                activity, result.defaultTitle, result.defaultAuthor, result.webpFiles.get(0));
                        updateStatus(1, result.webpFiles.size());
                        for (int i = 1; i < result.webpFiles.size(); i++) {
                            WastickerParser.addWebpStickerToPack(activity, newId, result.webpFiles.get(i));
                            updateStatus(i + 1, result.webpFiles.size());
                        }
                    }
                } else {
                    int count = 0;
                    for (File webpFile : result.webpFiles) {
                        WastickerParser.addWebpStickerToPack(activity, identifier, webpFile);
                        count++;
                        updateStatus(count, result.webpFiles.size());
                    }
                }
                
                // Invalidate cache so the user sees the new sticker immediately
                StickerContentProvider provider = StickerContentProvider.getInstance();
                if (provider != null) provider.invalidateStickerPackList();

                error = null;
            } catch (Exception e) {
                error = e.getMessage();
            }
            if (result.tempDir != null && result.tempDir.exists()) deleteRecursive(result.tempDir);
            final String finalError = error;
            mainHandler.post(() -> {
                ImportIndividualStickerActivity act = ref.get();
                if (act == null) return;
                if (finalError != null) {
                    Toast.makeText(act, getString(R.string.error_with_message, finalError), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(act, R.string.import_success_toast, Toast.LENGTH_SHORT).show();
                }
                act.finish();
            });
        });
    }

    private void updateStatus(int current, int total) {
        mainHandler.post(() -> {
            if (progressBar != null) progressBar.setProgressCompat(current, true);
            if (statusText != null) statusText.setText(getString(R.string.import_progress, current, total));
        });
    }

    private void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        fileOrDir.delete();
    }
}
