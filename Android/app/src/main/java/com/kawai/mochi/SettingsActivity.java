package com.kawai.mochi;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kawai.mochi.R;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends BaseActivity {
    private static final String PREFS_NAME = "mochi_prefs";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_ASK_PACK_PICKER = "ask_pack_picker";
    public static final String KEY_ENABLE_ANIMATIONS = "enable_animations";

    private TextView currentFolderText;
    private ActivityResultLauncher<Intent> folderPickerLauncher;
    private static final String GITHUB_URL = "https://github.com/maxcodl/MochiMochi";
    private static final String TELEGRAM_URL = "https://t.me/maxwantstohangout";
    private static final ExecutorService repairExecutor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.settings_title);
        }

        currentFolderText = findViewById(R.id.current_folder_text);
        Button chooseFolderButton = findViewById(R.id.choose_folder_button);
        Button resetToDefaultButton = findViewById(R.id.reset_to_default_button);
        RadioGroup themeRadioGroup = findViewById(R.id.theme_radio_group);
        SwitchMaterial askPackPickerSwitch = findViewById(R.id.ask_pack_picker_switch);
        SwitchMaterial enableAnimationsSwitch = findViewById(R.id.enable_animations_switch);
        Button runDiagnosticsButton = findViewById(R.id.run_diagnostics_button);
        Button githubButton = findViewById(R.id.github_button);
        Button telegramButton = findViewById(R.id.telegram_button);
        Button donateButton = findViewById(R.id.donate_button);

        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri treeUri = result.getData().getData();
                        if (treeUri != null) {
                            getContentResolver().takePersistableUriPermission(treeUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            WastickerParser.setStickerFolderPath(this, treeUri.toString());
                            updateFolderDisplay();
                        }
                    }
                });

        updateFolderDisplay();

        chooseFolderButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            folderPickerLauncher.launch(intent);
        });

        resetToDefaultButton.setOnClickListener(v -> {
            WastickerParser.setStickerFolderPath(this, null);
            updateFolderDisplay();
            Toast.makeText(this, R.string.reset_to_default_toast, Toast.LENGTH_SHORT).show();
        });

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int themeMode = prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        boolean isAmoled = prefs.getBoolean("theme_amoled", false);
        
        if (isAmoled) themeRadioGroup.check(R.id.theme_amoled);
        else {
            switch (themeMode) {
                case AppCompatDelegate.MODE_NIGHT_NO: themeRadioGroup.check(R.id.theme_light); break;
                case AppCompatDelegate.MODE_NIGHT_YES: themeRadioGroup.check(R.id.theme_dark); break;
                default: themeRadioGroup.check(R.id.theme_system); break;
            }
        }

        themeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int mode; boolean setAmoled = false;
            if (checkedId == R.id.theme_light) mode = AppCompatDelegate.MODE_NIGHT_NO;
            else if (checkedId == R.id.theme_dark) mode = AppCompatDelegate.MODE_NIGHT_YES;
            else if (checkedId == R.id.theme_amoled) { mode = AppCompatDelegate.MODE_NIGHT_YES; setAmoled = true; }
            else mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            
            prefs.edit().putInt(KEY_THEME, mode).putBoolean("theme_amoled", setAmoled).apply();
            AppCompatDelegate.setDefaultNightMode(mode);
            recreate();
        });

        // Pack picker switch
        askPackPickerSwitch.setChecked(isAskPackPickerEnabled(this));
        askPackPickerSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_ASK_PACK_PICKER, isChecked).apply();
        });

        // Animations switch
        enableAnimationsSwitch.setChecked(isAnimationsEnabled(this));
        enableAnimationsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_ENABLE_ANIMATIONS, isChecked).apply();
        });

        runDiagnosticsButton.setOnClickListener(v -> showPackSelectorForDiagnostics());
        githubButton.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))));
        telegramButton.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL))));
        donateButton.setOnClickListener(v -> showDonateDialog());
    }

    private void updateFolderDisplay() {
        String path = WastickerParser.getStickerFolderPath(this);
        currentFolderText.setText(path.equals(getFilesDir().getAbsolutePath()) ? getString(R.string.default_folder_display) : path);
    }

    public static boolean isAskPackPickerEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_ASK_PACK_PICKER, false);
    }

    public static boolean isAnimationsEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_ENABLE_ANIMATIONS, true);
    }

    private void showPackSelectorForDiagnostics() {
        try {
            ArrayList<StickerPack> packs = StickerPackLoader.fetchStickerPacks(this);
            if (packs.isEmpty()) {
                Toast.makeText(this, R.string.no_packs_to_diagnose, Toast.LENGTH_SHORT).show();
                return;
            }
            String[] names = new String[packs.size() + 1];
            names[0] = getString(R.string.all_packs_option);
            for (int i = 0; i < packs.size(); i++) names[i + 1] = packs.get(i).name;

            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.diagnostics_pack_selection_title)
                    .setItems(names, (dialog, which) -> {
                        if (which == 0) runPackDiagnostics(packs);
                        else {
                            ArrayList<StickerPack> s = new ArrayList<>();
                            s.add(packs.get(which - 1));
                            runPackDiagnostics(s);
                        }
                    }).show();
        } catch (Exception e) { Toast.makeText(this, getString(R.string.error_with_message, e.getMessage()), Toast.LENGTH_SHORT).show(); }
    }

    private void runPackDiagnostics(ArrayList<StickerPack> packs) {
        StringBuilder report = new StringBuilder();
        report.append(getString(R.string.deep_pack_diagnostics_report_title));
        String root = WastickerParser.getStickerFolderPath(this);

        for (StickerPack pack : packs) {
            report.append("■ PACK: ").append(pack.name).append("\n");
            File packDir = new File(new File(root), pack.identifier);
            File trayFile = new File(packDir, pack.trayImageFile);
            
            String trayInfo = getString(R.string.diagnostics_missing_tray);
            if (trayFile.exists()) {
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(trayFile.getAbsolutePath(), opts);
                trayInfo = "(" + opts.outWidth + "x" + opts.outHeight + ", " + Formatter.formatShortFileSize(this, trayFile.length()) + ")";
            }
            report.append(getString(R.string.diagnostics_tray_label)).append(pack.trayImageFile).append(" ").append(trayInfo).append("\n");

            if (pack.getStickers() != null) {
                for (Sticker s : pack.getStickers()) {
                    File file = new File(packDir, s.imageFileName);
                    if (file.exists()) {
                        StickerInfoAdapter.WebPInfo info = StickerInfoAdapter.readWebPInfo(file);
                        report.append("    ○ ").append(s.imageFileName).append("\n");
                        report.append("      ").append(info.width).append("x").append(info.height)
                              .append("  ").append(Formatter.formatShortFileSize(this, file.length()))
                              .append(info.isAnimated ? "  [" + getString(R.string.animated) + "]" : "  [" + getString(R.string.static_pack) + "]").append("\n");
                        
                        // Color/Alpha info
                        report.append("      Color: ").append(info.hasAlpha ? "RGBA" : "RGB");
                        if (info.hasIcc) report.append(" + ICC");
                        if (info.hasExif) report.append(" + EXIF");
                        report.append("\n");
                        
                        // Frames/FPS info
                        if (info.isAnimated) {
                            report.append("      Frames: ").append(info.frameCount);
                            if (info.fps > 0) report.append("  Rate: ").append(info.fps).append(" fps");
                            report.append("\n");
                        }
                    } else report.append(getString(R.string.diagnostics_missing_sticker, s.imageFileName));
                }
            }
            report.append("\n");
        }

        View v = LayoutInflater.from(this).inflate(R.layout.dialog_diagnostics, null);
        TextView logText = v.findViewById(R.id.diagnostics_report_text);
        logText.setText(report.toString());

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.diagnostics_results_title)
                .setView(v)
                .setPositiveButton(R.string.repair_process_title, (d, which) -> startRepairProcess(packs))
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.copy_label, null)
                .show();

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(android.content.ClipData.newPlainText("diag", report.toString()));
            Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
        });
    }

    private void startRepairProcess(ArrayList<StickerPack> packs) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_diagnostics, null);
        TextView logText = v.findViewById(R.id.diagnostics_report_text);
        logText.setText(R.string.repair_engine_init);
        ScrollView sv = (ScrollView) logText.getParent();

        AlertDialog diag = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.processing_stickers_title)
                .setView(v).setCancelable(false)
                .setPositiveButton(R.string.done, (d, w) -> recreate())
                .setNeutralButton(R.string.copy_log, null)
                .show();

        Button done = diag.getButton(AlertDialog.BUTTON_POSITIVE);
        done.setEnabled(false);

        diag.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(android.content.ClipData.newPlainText("repair_log", logText.getText().toString()));
            Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
        });

        repairExecutor.execute(() -> {
            int fixedCount = 0;
            String rootPath = WastickerParser.getStickerFolderPath(this);

            for (StickerPack pack : packs) {
                postLog(logText, sv, "\n■ PROCESSING: " + pack.name);
                File packDir = new File(new File(rootPath), pack.identifier);

                // 1. Repair Tray
                File trayFile = new File(packDir, pack.trayImageFile);
                boolean trayBad = !trayFile.exists() || trayFile.length() == 0;
                if (!trayBad) {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFile(trayFile.getAbsolutePath(), o);
                    if (o.outWidth != 96 || o.outHeight != 96) trayBad = true;
                }

                if (trayBad && pack.getStickers() != null && !pack.getStickers().isEmpty()) {
                    try {
                        postLog(logText, sv, "  ⚙ Standardizing Tray Icon (96x96)...");
                        File src = new File(packDir, pack.getStickers().get(0).imageFileName);
                        StickerProcessor.processTrayIcon(src, trayFile);
                        postLog(logText, sv, "    ✓ Fixed Tray.");
                        fixedCount++;
                    } catch (Exception e) { postLog(logText, sv, "    ✖ Tray failed: " + e.getMessage()); }
                }

                // 2. Repair Stickers
                if (pack.getStickers() != null) {
                    for (Sticker s : pack.getStickers()) {
                        File f = new File(packDir, s.imageFileName);
                        if (!f.exists()) continue;

                        StickerInfoAdapter.WebPInfo info = StickerInfoAdapter.readWebPInfo(f);
                        boolean needsFix = false;
                        
                        if (!info.isAnimated) {
                            if (info.width != 512 || info.height != 512 || f.length() > 100 * 1024) {
                                needsFix = true;
                            }
                        } else {
                            if (info.width != 512 || info.height != 512) {
                                postLog(logText, sv, "  ⚠ Animated sticker wrong size (" + info.width + "x" + info.height + "). Resizing animated WebP is limited.");
                            }
                            
                            postLog(logText, sv, "  ⚙ Cleaning Metadata: " + s.imageFileName);
                            if (StickerProcessor.stripWebPMetadata(f)) {
                                postLog(logText, sv, "    ✓ Metadata stripped.");
                                fixedCount++;
                            } else {
                                postLog(logText, sv, "    · No metadata found.");
                            }
                            
                            if (f.length() > 500 * 1024) {
                                postLog(logText, sv, "  ✖ CRITICAL: Animated sticker still over 500KB (" + Formatter.formatShortFileSize(this, f.length()) + ")");
                            }
                        }

                        if (needsFix && !info.isAnimated) {
                            try {
                                postLog(logText, sv, "  ⚙ Standardizing Static: " + s.imageFileName);
                                StickerProcessor.processStaticSticker(f, f);
                                postLog(logText, sv, "    ✓ Fixed (512x512).");
                                fixedCount++;
                            } catch (Exception e) {
                                postLog(logText, sv, "    ✖ Failed: " + e.getMessage());
                            }
                        }
                    }
                }
            }

            postLog(logText, sv, "\n=== Processing Complete ===");
            postLog(logText, sv, "Standardized Operations: " + fixedCount);
            mainHandler.post(() -> {
                StickerContentProvider.getInstance().invalidateStickerPackList();
                done.setEnabled(true);
                Toast.makeText(this, R.string.repair_complete, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void postLog(TextView t, ScrollView s, String m) {
        mainHandler.post(() -> { t.append(m + "\n"); s.post(() -> s.fullScroll(View.FOCUS_DOWN)); });
    }

    private void showDonateDialog() {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.donate_title).setMessage(R.string.donate_message)
            .setPositiveButton(R.string.donate_positive, (d, w) -> {
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setImageResource(R.drawable.kidney_meme); iv.setAdjustViewBounds(true);
                new MaterialAlertDialogBuilder(this).setTitle(R.string.sending_title).setView(iv).setPositiveButton(R.string.done, null).show();
            }).setNegativeButton(R.string.later, null).show();
    }
}
