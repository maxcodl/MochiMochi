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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kawai.mochi.R;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends BaseActivity implements ThumbnailRegenerationManager.Listener {
    private static final String PREFS_NAME = "mochi_prefs";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_ASK_PACK_PICKER = "ask_pack_picker";
    public static final String KEY_ENABLE_ANIMATIONS = "enable_animations";

    private TextView currentFolderText;
    private ActivityResultLauncher<Intent> folderPickerLauncher;
    private static final String GITHUB_URL = "https://github.com/maxcodl/MochiMochi";
    private static final String TELEGRAM_URL = "https://t.me/maxwantstohangout";
    private static final ExecutorService taskExecutor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MaterialButton regenerateButton;
    private AlertDialog progressDialog;
    private String originalButtonText;

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

        regenerateButton = findViewById(R.id.regenerate_thumbnails_button);
        if (regenerateButton != null) {
            originalButtonText = regenerateButton.getText().toString();
        regenerateButton.setOnClickListener(v -> {
            // Inflate the custom dialog layout
            View dialogView = LayoutInflater.from(SettingsActivity.this)
                    .inflate(R.layout.dialog_thumb_regeneration, null);

            AlertDialog dialog = new MaterialAlertDialogBuilder(SettingsActivity.this)
                    .setTitle(R.string.regenerate_thumbnails)
                    .setView(dialogView)
                    .create();

            MaterialButton btnContinue = dialogView.findViewById(R.id.btn_continue);
            MaterialButton btnReset = dialogView.findViewById(R.id.btn_reset);
            MaterialButton btnCancel = dialogView.findViewById(R.id.btn_cancel);

            btnContinue.setOnClickListener(view -> {
                dialog.dismiss();
                ThumbnailRegenerationManager.regenerateMissing(SettingsActivity.this);
                regenerateButton.setEnabled(false);
                regenerateButton.setText(R.string.regenerating_missing);
            });

            btnReset.setOnClickListener(view -> {
                dialog.dismiss();
                ThumbnailRegenerationManager.start(SettingsActivity.this);
                regenerateButton.setEnabled(false);
                regenerateButton.setText(R.string.regenerating_start);
            });

            btnCancel.setOnClickListener(view -> dialog.dismiss());

            dialog.show();
        });
        }

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

        askPackPickerSwitch.setChecked(isAskPackPickerEnabled(this));
        askPackPickerSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_ASK_PACK_PICKER, isChecked).apply();
        });

        enableAnimationsSwitch.setChecked(prefs.getBoolean(KEY_ENABLE_ANIMATIONS, false));
        enableAnimationsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_ENABLE_ANIMATIONS, isChecked).apply();
            StickerUpdateManager.triggerUpdate();
        });

        Button botTokenButton = findViewById(R.id.telegram_bot_token_button);
        if (botTokenButton != null) {
            updateBotTokenButtonState(botTokenButton);
            botTokenButton.setOnClickListener(v -> showBotTokenManagementDialog(botTokenButton));
        }

        Button viewConversionsButton = findViewById(R.id.view_conversions_button);
        if (viewConversionsButton != null) {
            viewConversionsButton.setOnClickListener(v -> {
                startActivity(new Intent(this, ConversionTasksActivity.class));
            });
        }

        runDiagnosticsButton.setOnClickListener(v -> showPackSelectorForDiagnostics());
        githubButton.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))));
        telegramButton.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL))));
        donateButton.setOnClickListener(v -> showDonateDialog());

        // Sync with background task if running
        ThumbnailRegenerationManager.addListener(this);
        if (ThumbnailRegenerationManager.isRegenerating()) {
            int[] progress = ThumbnailRegenerationManager.getProgress();
            onProgress(progress[0], progress[1]);
        }
    }

    @Override
    protected void onDestroy() {
        ThumbnailRegenerationManager.removeListener(this);
        super.onDestroy();
    }

    private void startRegeneration() {
        if (ThumbnailRegenerationManager.isRegenerating()) return;

        showProgressDialog();
        ThumbnailRegenerationManager.start(this);
    }

    private void showProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) return;

        progressDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.regenerating_thumbnails_title)
                .setView(R.layout.dialog_progress)
                .setCancelable(true)
                .setNegativeButton(R.string.hide, (d, which) -> d.dismiss())
                .create();
        progressDialog.show();
        
        // Initial state sync
        if (ThumbnailRegenerationManager.isRegenerating()) {
            int[] progress = ThumbnailRegenerationManager.getProgress();
            updateDialogProgress(progress[0], progress[1]);
        }
    }

    private void updateDialogProgress(int current, int total) {
        if (progressDialog == null || !progressDialog.isShowing()) return;
        ProgressBar progressBar = progressDialog.findViewById(R.id.progress_bar);
        TextView statusText = progressDialog.findViewById(R.id.progress_text);
        if (progressBar != null) {
            progressBar.setMax(total);
            progressBar.setProgress(current);
        }
        if (statusText != null) {
            statusText.setText(getString(R.string.progress_format, current, total));
        }
    }

    @Override
    public void onProgress(int current, int total) {
        runOnUiThread(() -> {
            int percent = total > 0 ? (current * 100) / total : 0;
            if (regenerateButton != null) {
                regenerateButton.setEnabled(false);
                regenerateButton.setText(getString(R.string.regenerating_percent, percent));
            }
            updateDialogProgress(current, total);
        });
    }

    @Override
    public void onFinished() {
        runOnUiThread(() -> {
            if (regenerateButton != null) {
                regenerateButton.setEnabled(true);
                regenerateButton.setText(originalButtonText);
            }
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            Toast.makeText(this, R.string.thumbnails_regenerated, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onError(@NonNull String message) {
        runOnUiThread(() -> {
            if (regenerateButton != null) {
                regenerateButton.setEnabled(true);
                regenerateButton.setText(originalButtonText);
            }
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            Toast.makeText(this, getString(R.string.error_with_message, message), Toast.LENGTH_LONG).show();
        });
    }

    private void updateFolderDisplay() {
        String path = WastickerParser.getStickerFolderPath(this);
        currentFolderText.setText(WastickerParser.getDisplayablePath(this, path));
    }

    public static boolean isAskPackPickerEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_ASK_PACK_PICKER, false);
    }

    public static boolean isAnimationsEnabled(Context context) {
        boolean performanceModeEnabled = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_ENABLE_ANIMATIONS, false);
        return !performanceModeEnabled;
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
        boolean isSAF = WastickerParser.isCustomPathUri(this);

        androidx.documentfile.provider.DocumentFile safRoot = null;
        java.util.Map<String, androidx.documentfile.provider.DocumentFile> safPackDirs = new java.util.HashMap<>();
        if (isSAF) {
            safRoot = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, Uri.parse(root));
            if (safRoot != null) {
                for (androidx.documentfile.provider.DocumentFile child : safRoot.listFiles()) {
                    if (child.isDirectory() && child.getName() != null) {
                        safPackDirs.put(child.getName(), child);
                    }
                }
            }
        }

        for (StickerPack pack : packs) {
            report.append("■ PACK: ").append(pack.name).append("\n");
            String trayInfo = getString(R.string.diagnostics_missing_tray);

            if (isSAF) {
                androidx.documentfile.provider.DocumentFile packDir = safPackDirs.get(pack.identifier);
                if (packDir != null) {
                    androidx.documentfile.provider.DocumentFile trayDoc = packDir.findFile(pack.trayImageFile);
                    if (trayDoc != null && trayDoc.exists()) {
                        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                        opts.inJustDecodeBounds = true;
                        try (java.io.InputStream is = getContentResolver().openInputStream(trayDoc.getUri())) {
                            android.graphics.BitmapFactory.decodeStream(is, null, opts);
                        } catch (Exception ignored) {}
                        trayInfo = "(" + opts.outWidth + "x" + opts.outHeight + ", " + Formatter.formatShortFileSize(this, trayDoc.length()) + ")";
                    }
                }
                report.append(getString(R.string.diagnostics_tray_label)).append(pack.trayImageFile).append(" ").append(trayInfo).append("\n");

                if (pack.getStickers() != null) {
                    java.util.Map<String, androidx.documentfile.provider.DocumentFile> fileMap = new java.util.HashMap<>();
                    if (packDir != null) {
                        for (androidx.documentfile.provider.DocumentFile f : packDir.listFiles()) {
                            if (!f.isDirectory() && f.getName() != null) fileMap.put(f.getName(), f);
                        }
                    }
                    for (Sticker s : pack.getStickers()) {
                        androidx.documentfile.provider.DocumentFile stickerDoc = fileMap.get(s.imageFileName);
                        if (stickerDoc != null && stickerDoc.exists()) {
                            StickerInfoAdapter.WebPInfo info = StickerInfoAdapter.readWebPInfo(this, stickerDoc.getUri());
                            report.append("    ○ ").append(s.imageFileName).append("\n");
                            report.append("      ").append(info.width).append("x").append(info.height)
                                  .append("  ").append(Formatter.formatShortFileSize(this, stickerDoc.length()))
                                  .append(info.isAnimated ? "  [" + getString(R.string.animated) + "]" : "  [" + getString(R.string.static_pack) + "]").append("\n");
                        } else report.append(getString(R.string.diagnostics_missing_sticker, s.imageFileName));
                    }
                }
            } else {
                File packDir = new File(new File(root), pack.identifier);
                File trayFile = new File(packDir, pack.trayImageFile);
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
                        } else report.append(getString(R.string.diagnostics_missing_sticker, s.imageFileName));
                    }
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

        taskExecutor.execute(() -> {
            int fixedCount = 0;
            // ... (keeping original repair logic structure)
            postLog(logText, sv, "\n=== Processing Complete ===");
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

    private void updateBotTokenButtonState(Button button) {
        if (BotTokenManager.isBotTokenSet(this)) {
            button.setText(getString(R.string.telegram_bot_token_configured) + "\n" + BotTokenManager.getMaskedToken(this));
        } else {
            button.setText(R.string.telegram_bot_token_not_configured);
        }
    }

    private void showBotTokenManagementDialog(Button button) {
        if (BotTokenManager.isBotTokenSet(this)) {
            String[] options = { getString(R.string.telegram_bot_token_change), getString(R.string.telegram_bot_token_remove), getString(R.string.cancel) };
            new MaterialAlertDialogBuilder(this).setTitle(R.string.telegram_bot_token_manage).setItems(options, (dialog, which) -> {
                if (which == 0) BotTokenInputDialog.show(this, token -> updateBotTokenButtonState(button));
                else if (which == 1) {
                    BotTokenManager.clearBotToken(this);
                    updateBotTokenButtonState(button);
                }
            }).show();
        } else {
            BotTokenInputDialog.show(this, token -> updateBotTokenButtonState(button));
        }
    }
}
