package com.kawai.mochii;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.util.ArrayList;

public class SettingsActivity extends BaseActivity {
    private static final String PREFS_NAME = "mochii_prefs";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_ASK_PACK_PICKER = "ask_pack_picker";

    private TextView currentFolderText;
    private ActivityResultLauncher<Intent> folderPickerLauncher;
    private static final String GITHUB_URL = "https://github.com/maxculen/Mochii-Stickere";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Settings");
        }

        currentFolderText = findViewById(R.id.current_folder_text);
        Button chooseFolderButton = findViewById(R.id.choose_folder_button);
        Button resetToDefaultButton = findViewById(R.id.reset_to_default_button);
        RadioGroup themeRadioGroup = findViewById(R.id.theme_radio_group);
        SwitchMaterial askPackPickerSwitch = findViewById(R.id.ask_pack_picker_switch);
        Button runDiagnosticsButton = findViewById(R.id.run_diagnostics_button);
        Button githubButton = findViewById(R.id.github_button);
        Button donateButton = findViewById(R.id.donate_button);

        // Setup folder picker
        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri treeUri = result.getData().getData();
                        if (treeUri != null) {
                            getContentResolver().takePersistableUriPermission(treeUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            String path = treeUri.toString();
                            WastickerParser.setStickerFolderPath(this, path);
                            updateFolderDisplay();
                        }
                    }
                });

        // Update folder display
        updateFolderDisplay();

        chooseFolderButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            folderPickerLauncher.launch(intent);
        });

        resetToDefaultButton.setOnClickListener(v -> {
            WastickerParser.setStickerFolderPath(this, null);
            updateFolderDisplay();
            Toast.makeText(this, "Reset to default folder", Toast.LENGTH_SHORT).show();
        });

        // Theme radio group
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int themeMode = prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        // We will store AMOLED as MODE_NIGHT_YES + 10 (or a custom value), but for AppCompatDelegate it's just night mode. We'll handle Amoled via a custom style in re-create.
        boolean isAmoled = prefs.getBoolean("theme_amoled", false);
        
        if (isAmoled) {
            themeRadioGroup.check(R.id.theme_amoled);
        } else {
            switch (themeMode) {
                case AppCompatDelegate.MODE_NIGHT_NO:
                    themeRadioGroup.check(R.id.theme_light);
                    break;
                case AppCompatDelegate.MODE_NIGHT_YES:
                    themeRadioGroup.check(R.id.theme_dark);
                    break;
                default:
                    themeRadioGroup.check(R.id.theme_system);
                    break;
            }
        }

        themeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int mode;
            boolean setAmoled = false;
            
            if (checkedId == R.id.theme_light) {
                mode = AppCompatDelegate.MODE_NIGHT_NO;
            } else if (checkedId == R.id.theme_dark) {
                mode = AppCompatDelegate.MODE_NIGHT_YES;
            } else if (checkedId == R.id.theme_amoled) {
                mode = AppCompatDelegate.MODE_NIGHT_YES;
                setAmoled = true;
            } else {
                mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }
            
            prefs.edit()
                .putInt(KEY_THEME, mode)
                .putBoolean("theme_amoled", setAmoled)
                .apply();
                
            AppCompatDelegate.setDefaultNightMode(mode);
            // Recreate only this activity — AppCompatDelegate already applied the global
            // night mode so the rest of the back stack will pick it up on next resume.
            recreate();
        });

        // Pack picker switch
        askPackPickerSwitch.setChecked(isAskPackPickerEnabled(this));
        askPackPickerSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(KEY_ASK_PACK_PICKER, isChecked).apply();
        });

        // Diagnostics button
        runDiagnosticsButton.setOnClickListener(v -> runPackDiagnostics());

        // GitHub button
        githubButton.setOnClickListener(v -> {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(GITHUB_URL)));
        });

        // Donate button — the real currency is kidneys
        donateButton.setOnClickListener(v -> {
            android.widget.ImageView memeView = new android.widget.ImageView(this);
            memeView.setImageResource(R.drawable.kidney_meme);
            memeView.setAdjustViewBounds(true);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            memeView.setPadding(pad, pad, pad, 0);

            new AlertDialog.Builder(this)
                .setTitle("❤️ Donate $69")
                .setMessage("Send your kidney to Max ❤️")
                .setPositiveButton("Gladly 💌", (dialog, which) -> {
                    // Show the meme dialog
                    android.widget.ImageView memeImg = new android.widget.ImageView(this);
                    memeImg.setImageResource(R.drawable.kidney_meme);
                    memeImg.setAdjustViewBounds(true);
                    int p = (int) (12 * getResources().getDisplayMetrics().density);
                    memeImg.setPadding(p, p, p, 0);

                    new AlertDialog.Builder(this)
                        .setTitle("🚑 Sending kidney to Max...")
                        .setView(memeImg)
                        .setPositiveButton("You're welcome ❤️", null)
                        .show();
                })
                .setNegativeButton("Maybe later", null)
                .show();
        });
    }

    private void updateFolderDisplay() {
        String path = WastickerParser.getStickerFolderPath(this);
        currentFolderText.setText(path.equals(getFilesDir().getAbsolutePath()) ?
                "Default (internal storage)" : path);
    }

    public static boolean isAskPackPickerEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ASK_PACK_PICKER, false);
    }

    private void runPackDiagnostics() {
        StringBuilder report = new StringBuilder();
        report.append("=== Pack Diagnostics ===\n\n");
        String folderPath = WastickerParser.getStickerFolderPath(this);
        report.append("Sticker folder: ").append(folderPath).append("\n\n");

        try {
            ArrayList<StickerPack> packs = StickerPackLoader.fetchStickerPacks(this);
            report.append("Total packs: ").append(packs.size()).append("\n\n");

            for (StickerPack pack : packs) {
                report.append("--- Pack: ").append(pack.name).append(" ---\n");
                report.append("  ID: ").append(pack.identifier).append("\n");
                report.append("  Publisher: ").append(pack.publisher).append("\n");
                report.append("  Animated: ").append(pack.animatedStickerPack).append("\n");
                report.append("  Tray: ").append(pack.trayImageFile).append("\n");

                File trayFile = new File(new File(folderPath, pack.identifier), pack.trayImageFile);
                if (trayFile.exists()) {
                    report.append("  Tray size: ").append(Formatter.formatShortFileSize(this, trayFile.length())).append("\n");
                } else {
                    report.append("  ⚠ TRAY MISSING\n");
                }

                if (pack.getStickers() != null) {
                    report.append("  Stickers: ").append(pack.getStickers().size()).append("\n");
                    for (Sticker sticker : pack.getStickers()) {
                        File stickerFile = new File(new File(folderPath, pack.identifier), sticker.imageFileName);
                        if (stickerFile.exists()) {
                            boolean isAnimated = WastickerParser.isAnimatedWebPPublic(this, pack.identifier, sticker.imageFileName);
                            report.append("    ").append(sticker.imageFileName)
                                    .append(" (").append(Formatter.formatShortFileSize(this, stickerFile.length())).append(")")
                                    .append(isAnimated ? " [animated]" : " [static]");
                            if (pack.animatedStickerPack != isAnimated) {
                                report.append(" ⚠ TYPE MISMATCH");
                            }
                            report.append("\n");
                        } else {
                            report.append("    ").append(sticker.imageFileName).append(" ⚠ MISSING\n");
                        }
                    }
                }
                report.append("\n");
            }
        } catch (Exception e) {
            report.append("Error: ").append(e.getMessage()).append("\n");
        }

        String reportText = report.toString();
        new AlertDialog.Builder(this)
                .setTitle("Pack Diagnostics")
                .setMessage(reportText)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton("Copy", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("diagnostics", reportText));
                    Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
