package com.kawai.mochi;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kawai.mochi.R;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StickerPackDetailsActivity extends AddStickerPackActivity {
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static final String EXTRA_STICKER_PACK_ID = "sticker_pack_id";
    public static final String EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority";
    public static final String EXTRA_STICKER_PACK_NAME = "sticker_pack_name";
    public static final String EXTRA_STICKER_PACK_WEBSITE = "sticker_pack_website";
    public static final String EXTRA_STICKER_PACK_EMAIL = "sticker_pack_email";
    public static final String EXTRA_STICKER_PACK_PRIVACY_POLICY = "sticker_pack_privacy_policy";
    public static final String EXTRA_STICKER_PACK_LICENSE_AGREEMENT = "sticker_pack_license_agreement";
    public static final String EXTRA_STICKER_PACK_TRAY_ICON = "sticker_pack_tray_icon";
    public static final String EXTRA_SHOW_UP_BUTTON = "show_up_button";
    public static final String EXTRA_STICKER_PACK_DATA = "sticker_pack";
    public static final String EXTRA_STICKER_PACK_PUBLISHER = "sticker_pack_publisher";
    public static final String EXTRA_STICKER_PACK_STICKER_COUNT = "sticker_pack_sticker_count";
    public static final String EXTRA_STICKER_PACK_TOTAL_SIZE = "sticker_pack_total_size";
    public static final String EXTRA_STICKER_PACK_ANIMATED = "sticker_pack_animated";

    private RecyclerView recyclerView;
    private GridLayoutManager layoutManager;
    private StickerPreviewAdapter stickerPreviewAdapter;
    private int numColumns;
    private View addButton;
    private View alreadyAddedText;
    private StickerPack stickerPack;
    private View divider;
    private volatile boolean whitelistCheckCancelled;
    private ActivityResultLauncher<Intent> editPackLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sticker_pack_details);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        boolean showUpButton = getIntent().getBooleanExtra(EXTRA_SHOW_UP_BUTTON, false);
        stickerPack = getIntent().getParcelableExtra(EXTRA_STICKER_PACK_DATA);

        TextView packNameTextView = findViewById(R.id.pack_name);
        TextView packPublisherTextView = findViewById(R.id.author);
        ImageView packTrayIcon = findViewById(R.id.tray_image);
        TextView packSizeTextView = findViewById(R.id.pack_size);
        MaterialButton editPackButton = findViewById(R.id.edit_pack_button);
        MaterialButton deletePackButton = findViewById(R.id.delete_pack_button);

        addButton = findViewById(R.id.add_to_whatsapp_button);
        alreadyAddedText = findViewById(R.id.already_added_text);
        layoutManager = new GridLayoutManager(this, 1);
        recyclerView = findViewById(R.id.sticker_list);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.getViewTreeObserver().addOnGlobalLayoutListener(pageLayoutListener);
        recyclerView.addOnScrollListener(dividerScrollListener);
        divider = findViewById(R.id.divider);
        
        setupAdapter();
        // Disable item animations so stickers don't flicker/fade-in when the grid is first laid out
        recyclerView.setItemAnimator(null);

        packNameTextView.setText(stickerPack.name);
        packPublisherTextView.setText(stickerPack.publisher);
        packTrayIcon.setImageURI(StickerPackLoader.getStickerAssetUri(stickerPack.identifier, stickerPack.trayImageFile));
        
        int count = stickerPack.getStickers() != null ? stickerPack.getStickers().size() : 0;
        String countStr = getResources().getString(R.string.sticker_count, count);
        String sizeStr = Formatter.formatShortFileSize(this, stickerPack.getTotalSize());
        packSizeTextView.setText(getString(R.string.sticker_pack_size_summary, sizeStr, countStr));

        addButton.setOnClickListener(v -> addStickerPackToWhatsApp(stickerPack.identifier, stickerPack.name));

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(showUpButton);
            getSupportActionBar().setTitle(showUpButton ? getResources().getString(R.string.title_activity_sticker_pack_details_multiple_pack) : getResources().getQuantityString(R.plurals.title_activity_sticker_packs_list, 1));
        }
        findViewById(R.id.sticker_pack_animation_indicator).setVisibility(stickerPack.animatedStickerPack ? View.VISIBLE : View.GONE);

        editPackLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == 201) {
                        reloadStickerPack();
                    }
                });

        editPackButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, EditStickerPackActivity.class);
            intent.putExtra(EditStickerPackActivity.EXTRA_STICKER_PACK, stickerPack);
            editPackLauncher.launch(intent);
        });

        deletePackButton.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_pack_title)
                    .setMessage(R.string.delete_pack_confirm)
                    .setPositiveButton(R.string.delete_button, (dialog, which) -> {
                        WeakReference<StickerPackDetailsActivity> ref = new WeakReference<>(this);
                        final String packId = stickerPack.identifier;
                        executor.execute(() -> {
                            String error;
                            try {
                                WastickerParser.deleteStickerPack(ref.get(), packId);
                                StickerContentProvider provider = StickerContentProvider.getInstance();
                                if (provider != null) provider.invalidateStickerPackList();
                                error = null;
                            } catch (Exception e) {
                                error = e.getMessage();
                            }
                            final String finalError = error;
                            mainHandler.post(() -> {
                                StickerPackDetailsActivity act = ref.get();
                                if (act == null) return;
                                if (finalError != null) {
                                    Toast.makeText(act, getString(R.string.error_with_message, finalError), Toast.LENGTH_LONG).show();
                                } else {
                                    Toast.makeText(act, R.string.pack_deleted, Toast.LENGTH_SHORT).show();
                                    Intent intent = new Intent(act, EntryActivity.class);
                                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                                    act.startActivity(intent);
                                    act.finish();
                                }
                            });
                        });
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
    }

    private void setupAdapter() {
        if (stickerPreviewAdapter == null) {
            boolean animationsEnabled = SettingsActivity.isAnimationsEnabled(this);
            stickerPreviewAdapter = new StickerPreviewAdapter(
                    stickerPack.getStickers(),
                    stickerPack.identifier,
                    getResources().getDimensionPixelSize(R.dimen.sticker_pack_details_image_size),
                    getResources().getDimensionPixelSize(R.dimen.sticker_pack_details_image_padding),
                    stickerPack.animatedStickerPack,
                    animationsEnabled,
                    /* isGridMode= */ true);
            recyclerView.setAdapter(stickerPreviewAdapter);
        }
    }

    private void reloadStickerPack() {
        WeakReference<StickerPackDetailsActivity> ref = new WeakReference<>(this);
        final String id = stickerPack.identifier;
        executor.execute(() -> {
            StickerPack updatedPack = null;
            try {
                ArrayList<StickerPack> packs = StickerPackLoader.fetchStickerPacks(ref.get());
                for (StickerPack pack : packs) {
                    if (pack.identifier.equals(id)) {
                        StickerPackValidator.verifyStickerPackValidity(ref.get(), pack);
                        updatedPack = pack;
                        break;
                    }
                }
            } catch (Exception ignored) {}
            final StickerPack finalPack = updatedPack;
            mainHandler.post(() -> {
                StickerPackDetailsActivity act = ref.get();
                if (act != null) act.updateUIWithPack(finalPack);
            });
        });
    }

    private void updateUIWithPack(StickerPack updatedPack) {
        if (updatedPack == null) {
            finish();
            return;
        }
        this.stickerPack = updatedPack;
        
        TextView packNameTextView = findViewById(R.id.pack_name);
        TextView packPublisherTextView = findViewById(R.id.author);
        ImageView packTrayIcon = findViewById(R.id.tray_image);
        TextView packSizeTextView = findViewById(R.id.pack_size);
        packNameTextView.setText(stickerPack.name);
        packPublisherTextView.setText(stickerPack.publisher);
        packTrayIcon.setImageURI(StickerPackLoader.getStickerAssetUri(stickerPack.identifier, stickerPack.trayImageFile));
        
        int count = stickerPack.getStickers() != null ? stickerPack.getStickers().size() : 0;
        String countStr = getResources().getString(R.string.sticker_count, count);
        String sizeStr = Formatter.formatShortFileSize(this, stickerPack.getTotalSize());
        packSizeTextView.setText(getString(R.string.sticker_pack_size_summary, sizeStr, countStr));

        findViewById(R.id.sticker_pack_animation_indicator).setVisibility(stickerPack.animatedStickerPack ? View.VISIBLE : View.GONE);
        
        stickerPreviewAdapter = null;
        setupAdapter();

        whitelistCheckCancelled = false;
        WeakReference<StickerPackDetailsActivity> wcRef = new WeakReference<>(this);
        final String wcId = stickerPack.identifier;
        executor.execute(() -> {
            if (whitelistCheckCancelled) return;
            StickerPackDetailsActivity wcActivity = wcRef.get();
            if (wcActivity == null) return;
            boolean whitelisted = WhitelistCheck.isWhitelisted(wcActivity, wcId);
            mainHandler.post(() -> {
                if (whitelistCheckCancelled) return;
                StickerPackDetailsActivity act = wcRef.get();
                if (act != null) act.updateAddUI(whitelisted);
            });
        });
    }

    private void launchInfoActivity(String publisherWebsite, String publisherEmail, String privacyPolicyWebsite, String licenseAgreementWebsite, String trayIconUriString) {
        Intent intent = new Intent(StickerPackDetailsActivity.this, StickerPackInfoActivity.class);
        intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_DATA, stickerPack);   // full parcelable for sticker list
        intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_ID, stickerPack.identifier);
        intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_NAME, stickerPack.name);
        intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_PUBLISHER, stickerPack.publisher);
        intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_WEBSITE, publisherWebsite);
        intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_EMAIL, publisherEmail);
        intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_PRIVACY_POLICY, privacyPolicyWebsite);
        intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_LICENSE_AGREEMENT, licenseAgreementWebsite);
        intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_TRAY_ICON, trayIconUriString);
        int count = stickerPack.getStickers() != null ? stickerPack.getStickers().size() : 0;
        intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_STICKER_COUNT, count);
        intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_TOTAL_SIZE, stickerPack.getTotalSize());
        intent.putExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_ANIMATED, stickerPack.animatedStickerPack);
        startActivity(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Single info icon, themed to match the toolbar text colour
        menu.add(Menu.NONE, R.id.action_info, Menu.NONE, R.string.action_info)
                .setIcon(R.drawable.sticker_3rdparty_info)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        android.view.MenuItem infoItem = menu.findItem(R.id.action_info);
        if (infoItem != null && infoItem.getIcon() != null) {
            int[] attrs = {com.google.android.material.R.attr.colorOnSurface};
            android.content.res.TypedArray ta = getTheme().obtainStyledAttributes(attrs);
            int color = ta.getColor(0, android.graphics.Color.WHITE);
            ta.recycle();
            infoItem.getIcon().setTint(color);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_info && stickerPack != null) {
            Uri trayIconUri = StickerPackLoader.getStickerAssetUri(stickerPack.identifier, stickerPack.trayImageFile);
            launchInfoActivity(stickerPack.publisherWebsite, stickerPack.publisherEmail, stickerPack.privacyPolicyWebsite, stickerPack.licenseAgreementWebsite, trayIconUri.toString());
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final ViewTreeObserver.OnGlobalLayoutListener pageLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout() {
            int width = recyclerView.getWidth();
            if (width <= 0) return; // not measured yet
            // Exclude the RecyclerView's own horizontal padding before computing columns.
            int contentWidth = width - recyclerView.getPaddingStart() - recyclerView.getPaddingEnd();
            int imageSize = recyclerView.getContext().getResources().getDimensionPixelSize(R.dimen.sticker_pack_details_image_size);
            int cols = Math.max(contentWidth / imageSize, 1);
            if (numColumns != cols) {
                numColumns = cols;
                layoutManager.setSpanCount(numColumns);
                // No notifyDataSetChanged needed — span count change triggers re-layout automatically
            }
            // Remove listener once we have a valid measurement
            if (recyclerView.getViewTreeObserver().isAlive()) {
                recyclerView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        }
    };

    private final RecyclerView.OnScrollListener dividerScrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(@NonNull final RecyclerView recyclerView, final int newState) {
            super.onScrollStateChanged(recyclerView, newState);
            updateDivider(recyclerView);
        }

        @Override
        public void onScrolled(@NonNull final RecyclerView recyclerView, final int dx, final int dy) {
            super.onScrolled(recyclerView, dx, dy);
            updateDivider(recyclerView);
        }

        private void updateDivider(RecyclerView recyclerView) {
            boolean showDivider = recyclerView.computeVerticalScrollOffset() > 0;
            if (divider != null) {
                divider.setVisibility(showDivider ? View.VISIBLE : View.INVISIBLE);
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        whitelistCheckCancelled = false;
        WeakReference<StickerPackDetailsActivity> ref = new WeakReference<>(this);
        final String id = stickerPack.identifier;
        executor.execute(() -> {
            if (whitelistCheckCancelled) return;
            StickerPackDetailsActivity activity = ref.get();
            if (activity == null) return;
            boolean whitelisted = WhitelistCheck.isWhitelisted(activity, id);
            mainHandler.post(() -> {
                if (whitelistCheckCancelled) return;
                StickerPackDetailsActivity act = ref.get();
                if (act != null) act.updateAddUI(whitelisted);
            });
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        whitelistCheckCancelled = true;
    }

    private void updateAddUI(Boolean isWhitelisted) {
        if (isWhitelisted) {
            addButton.setVisibility(View.GONE);
            alreadyAddedText.setVisibility(View.VISIBLE);
            findViewById(R.id.sticker_pack_details_tap_to_preview).setVisibility(View.GONE);
        } else {
            addButton.setVisibility(View.VISIBLE);
            alreadyAddedText.setVisibility(View.GONE);
            findViewById(R.id.sticker_pack_details_tap_to_preview).setVisibility(View.VISIBLE);
        }
    }

}
