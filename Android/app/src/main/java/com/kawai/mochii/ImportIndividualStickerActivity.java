package com.kawai.mochii;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ImportIndividualStickerActivity extends BaseActivity {
    private static final String TAG = "ImportIndividual";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        Uri uri = null;

        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        }

        if (uri == null) {
            Toast.makeText(this, "No file provided", Toast.LENGTH_SHORT).show();
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
        WeakReference<ImportIndividualStickerActivity> ref = new WeakReference<>(this);
        executor.execute(() -> {
            ImportIndividualStickerActivity activity = ref.get();
            if (activity == null) return;
            ExtractResult result = extractSticker(activity, uri);
            mainHandler.post(() -> {
                ImportIndividualStickerActivity act = ref.get();
                if (act == null || result == null) return;
                if (result.error != null) {
                    Toast.makeText(act, "Error: " + result.error, Toast.LENGTH_LONG).show();
                    act.finish();
                    return;
                }
                if (result.webpFiles.isEmpty()) {
                    Toast.makeText(act, "No stickers found in file", Toast.LENGTH_SHORT).show();
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
        result.defaultTitle = "Imported Pack";
        result.defaultAuthor = "Unknown";
        try {
            result.tempDir = new File(activity.getCacheDir(), "idwasticker_" + System.currentTimeMillis());
            result.tempDir.mkdirs();
            InputStream is = activity.getContentResolver().openInputStream(uri);
            if (is == null) { result.error = "Cannot open file"; return result; }
            byte[] peek = new byte[2];
            int peekRead = is.read(peek);
            is.close();
            boolean isZip = peekRead >= 2 && peek[0] == 0x50 && peek[1] == 0x4B;
            if (isZip) {
                InputStream is2 = activity.getContentResolver().openInputStream(uri);
                ZipInputStream zis = new ZipInputStream(is2);
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File file = new File(result.tempDir, entry.getName());
                    if (entry.isDirectory()) { file.mkdirs(); } else {
                        file.getParentFile().mkdirs();
                        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file));
                        byte[] buffer = new byte[8192]; int len;
                        while ((len = zis.read(buffer)) > 0) bos.write(buffer, 0, len);
                        bos.close();
                    }
                    zis.closeEntry();
                }
                zis.close(); is2.close();
                File[] files = result.tempDir.listFiles();
                if (files != null) for (File f : files) if (f.getName().toLowerCase().endsWith(".webp")) result.webpFiles.add(f);
                File titleFile = new File(result.tempDir, "title.txt");
                if (titleFile.exists()) result.defaultTitle = WastickerParser.readStringFromFile(titleFile).trim();
                File authorFile = new File(result.tempDir, "author.txt");
                if (authorFile.exists()) result.defaultAuthor = WastickerParser.readStringFromFile(authorFile).trim();
            } else {
                InputStream is2 = activity.getContentResolver().openInputStream(uri);
                if (is2 == null) { result.error = "Cannot open file"; return result; }
                File webpFile = new File(result.tempDir, "sticker_" + System.currentTimeMillis() + ".webp");
                BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(webpFile));
                byte[] buffer = new byte[8192]; int len;
                while ((len = is2.read(buffer)) > 0) bos.write(buffer, 0, len);
                bos.close(); is2.close();
                result.webpFiles.add(webpFile);
            }
            result.eligiblePacks = new ArrayList<>();
            try {
                ArrayList<StickerPack> allPacks = StickerPackLoader.fetchStickerPacks(activity);
                for (StickerPack pack : allPacks) if (pack.getStickers() != null && pack.getStickers().size() < 30) result.eligiblePacks.add(pack);
            } catch (Exception ignored) {}
        } catch (Exception e) { result.error = e.getMessage(); }
        return result;
    }

    private void showPackPicker(ExtractResult result) {
        List<String> options = new ArrayList<>();
        for (StickerPack pack : result.eligiblePacks) {
            options.add(pack.name + " (" + pack.getStickers().size() + "/30)");
        }
        options.add("Create New Pack");

        new MaterialAlertDialogBuilder(this)
                .setTitle("Add to which pack?")
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
                        for (int i = 1; i < result.webpFiles.size(); i++) {
                            WastickerParser.addWebpStickerToPack(activity, newId, result.webpFiles.get(i));
                        }
                    }
                } else {
                    for (File webpFile : result.webpFiles) {
                        WastickerParser.addWebpStickerToPack(activity, identifier, webpFile);
                    }
                }
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
                    Toast.makeText(act, "Error: " + finalError, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(act, "Sticker(s) imported successfully!", Toast.LENGTH_SHORT).show();
                }
                act.finish();
            });
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
