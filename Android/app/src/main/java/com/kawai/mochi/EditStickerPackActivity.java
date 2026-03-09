package com.kawai.mochi;

import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.kawai.mochi.R;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EditStickerPackActivity extends BaseActivity implements EditStickerAdapter.OnStickerActionListener {
    public static final String EXTRA_STICKER_PACK = "sticker_pack";
    private static final int MAX_STICKERS = 30;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isEditMode;
    private String packIdentifier;
    private TextInputEditText packNameEdit;
    private TextInputEditText authorEdit;
    private ImageView trayIconPreview;
    private RecyclerView stickerGrid;
    private TextView stickerCountText;
    private EditStickerAdapter adapter;
    private List<EditStickerAdapter.StickerItem> stickerItems;
    private Uri trayIconUri;

    private ActivityResultLauncher<Intent> trayIconPickerLauncher;
    private ActivityResultLauncher<Intent> stickerPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_sticker_pack);

        packNameEdit = findViewById(R.id.pack_name_edit);
        authorEdit = findViewById(R.id.author_edit);
        trayIconPreview = findViewById(R.id.tray_icon_preview);
        stickerGrid = findViewById(R.id.sticker_grid);
        stickerCountText = findViewById(R.id.sticker_count_text);
        MaterialButton changeTrayIconButton = findViewById(R.id.change_tray_icon_button);
        MaterialButton addStickerButton = findViewById(R.id.add_sticker_button);
        MaterialButton saveButton = findViewById(R.id.save_button);

        stickerItems = new ArrayList<>();
        adapter = new EditStickerAdapter(stickerItems, this);
        stickerGrid.setLayoutManager(new GridLayoutManager(this, 4));
        stickerGrid.setAdapter(adapter);

        // Check if edit mode
        StickerPack pack = getIntent().getParcelableExtra(EXTRA_STICKER_PACK);
        if (pack != null) {
            isEditMode = true;
            packIdentifier = pack.identifier;
            packNameEdit.setText(pack.name);
            authorEdit.setText(pack.publisher);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(R.string.edit_pack);
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            }

            // Load tray icon
            if (pack.trayImageFile != null) {
                trayIconPreview.setImageURI(StickerPackLoader.getStickerAssetUri(pack.identifier, pack.trayImageFile));
            }

            // Load existing stickers
            if (pack.getStickers() != null) {
                for (Sticker sticker : pack.getStickers()) {
                    EditStickerAdapter.StickerItem item = new EditStickerAdapter.StickerItem(
                            pack.identifier, sticker.imageFileName, sticker.emojis);
                    stickerItems.add(item);
                }
                adapter.notifyDataSetChanged();
            }
        } else {
            isEditMode = false;
            packIdentifier = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(R.string.create_pack);
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            }
        }

        updateStickerCount();

        // Setup launchers
        trayIconPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        trayIconUri = result.getData().getData();
                        if (trayIconUri != null) {
                            trayIconPreview.setImageURI(trayIconUri);
                        }
                    }
                });

        stickerPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        // Handle multiple selection
                        if (result.getData().getClipData() != null) {
                            int count = result.getData().getClipData().getItemCount();
                            for (int i = 0; i < count; i++) {
                                Uri uri = result.getData().getClipData().getItemAt(i).getUri();
                                addStickerFromUri(uri);
                            }
                        } else if (result.getData().getData() != null) {
                            addStickerFromUri(result.getData().getData());
                        }
                        adapter.notifyDataSetChanged();
                        updateStickerCount();
                    }
                });

        changeTrayIconButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            trayIconPickerLauncher.launch(intent);
        });

        addStickerButton.setOnClickListener(v -> {
            if (stickerItems.size() >= MAX_STICKERS) {
                Toast.makeText(this, R.string.sticker_limit_reached, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            stickerPickerLauncher.launch(Intent.createChooser(intent, getString(R.string.select_stickers_title)));
        });

        saveButton.setOnClickListener(v -> {
            String name = packNameEdit.getText() != null ? packNameEdit.getText().toString().trim() : "";
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.pack_name_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            String author = authorEdit.getText() != null ? authorEdit.getText().toString().trim() : "";
            if (author.isEmpty()) author = getString(R.string.unknown_author);
            savePack(name, author, packIdentifier, stickerItems, trayIconUri);
        });
    }

    private void savePack(String name, String author, String identifier,
                          List<EditStickerAdapter.StickerItem> items, Uri trayUri) {
        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage(getString(R.string.saving));
        progressDialog.setCancelable(false);
        progressDialog.show();
        WeakReference<EditStickerPackActivity> ref = new WeakReference<>(this);
        final List<EditStickerAdapter.StickerItem> itemsCopy = new ArrayList<>(items);
        executor.execute(() -> {
            String error;
            try {
                WastickerParser.savePack(ref.get(), name, author, identifier, itemsCopy, trayUri);
                error = null;
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String finalError = error;
            mainHandler.post(() -> {
                if (progressDialog.isShowing()) progressDialog.dismiss();
                EditStickerPackActivity activity = ref.get();
                if (activity == null) return;
                if (finalError != null) {
                    Toast.makeText(activity, getString(R.string.error_with_message, finalError), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(activity, activity.isEditMode ?
                            R.string.edit_saved : R.string.pack_created, Toast.LENGTH_SHORT).show();
                    activity.setResult(201);
                    activity.finish();
                }
            });
        });
    }

    private void addStickerFromUri(Uri uri) {
        if (stickerItems.size() >= MAX_STICKERS) return;
        stickerItems.add(new EditStickerAdapter.StickerItem(uri));
    }

    private void updateStickerCount() {
        stickerCountText.setText(getString(R.string.sticker_count_format, stickerItems.size(), MAX_STICKERS));
    }

    @Override
    public void onRemoveClicked(int position) {
        if (position >= 0 && position < stickerItems.size()) {
            stickerItems.remove(position);
            adapter.notifyItemRemoved(position);
            adapter.notifyItemRangeChanged(position, stickerItems.size());
            updateStickerCount();
        }
    }

    @Override
    public void onStickerClicked(int position, View stickerView) {
        if (position < 0 || position >= stickerItems.size()) return;
        EditStickerAdapter.StickerItem item = stickerItems.get(position);

        // Build current emoji string for display
        StringBuilder sb = new StringBuilder();
        if (item.emojis != null) for (String e : item.emojis) sb.append(e);

        final com.google.android.material.textfield.TextInputEditText input =
                new com.google.android.material.textfield.TextInputEditText(this);
        input.setText(sb.toString());
        input.setHint(R.string.edit_emojis_hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);

        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        int dp16 = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(dp16, 0, dp16, 0);
        container.addView(input);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.edit_emojis_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String text = input.getText() != null ? input.getText().toString().trim() : "";
                    // Split into grapheme clusters so multi-codepoint emoji count as one
                    java.text.BreakIterator bi = java.text.BreakIterator.getCharacterInstance();
                    bi.setText(text);
                    List<String> newEmojis = new ArrayList<>();
                    int start = bi.first();
                    for (int end = bi.next();
                         end != java.text.BreakIterator.DONE && newEmojis.size() < 3;
                         end = bi.next()) {
                        String cluster = text.substring(start, end);
                        // Only include non-ASCII characters (emoji)
                        if (cluster.codePointAt(0) > 0xFF) newEmojis.add(cluster);
                        start = end;
                    }
                    if (newEmojis.isEmpty()) newEmojis.add("\uD83D\uDE00"); // fallback: 😀
                    item.emojis = newEmojis;
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
