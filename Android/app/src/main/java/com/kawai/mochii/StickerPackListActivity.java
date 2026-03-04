package com.kawai.mochii;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class StickerPackListActivity extends AddStickerPackActivity {
    public static final String EXTRA_STICKER_PACK_LIST_DATA = "sticker_pack_list";
    private static final int STICKER_PREVIEW_DISPLAY_LIMIT = 5;
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayoutManager packLayoutManager;
    private RecyclerView packRecyclerView;
    private StickerPackListAdapter allStickerPacksListAdapter;
    private volatile boolean whitelistCheckCancelled;
    private ArrayList<StickerPack> stickerPackList;
    private View emptyStateLayout;
    private FloatingActionButton importFab;
    private ActivityResultLauncher<Intent> filePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sticker_pack_list);
        packRecyclerView = findViewById(R.id.sticker_pack_list);
        emptyStateLayout = findViewById(R.id.empty_state_layout);
        importFab = findViewById(R.id.import_button_fab);

        stickerPackList = getIntent().getParcelableArrayListExtra(EXTRA_STICKER_PACK_LIST_DATA);
        if (stickerPackList == null) stickerPackList = new ArrayList<>();

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getResources().getQuantityString(R.plurals.title_activity_sticker_packs_list, stickerPackList.size()));
        }

        // Setup file picker for .wasticker import
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            importWastickerFile(uri);
                        }
                    }
                });

        showStickerPackList(stickerPackList);

        // Empty state import button
        Button emptyImportButton = findViewById(R.id.import_button);
        if (emptyImportButton != null) {
            emptyImportButton.setOnClickListener(v -> openFilePicker());
        }

        // FAB import button
        importFab.setOnClickListener(v -> openFilePicker());

        // Swipe to delete
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                if (position >= 0 && position < stickerPackList.size()) {
                    StickerPack pack = stickerPackList.get(position);
                    new MaterialAlertDialogBuilder(StickerPackListActivity.this)
                            .setTitle("Delete Pack")
                            .setMessage("Delete \"" + pack.name + "\"?")
                            .setPositiveButton("Delete", (dialog, which) -> deletePack(position))
                            .setNegativeButton("Cancel", (dialog, which) -> {
                                allStickerPacksListAdapter.notifyItemChanged(position);
                            })
                            .setOnCancelListener(d -> allStickerPacksListAdapter.notifyItemChanged(position))
                            .show();
                }
            }
        }).attachToRecyclerView(packRecyclerView);

        updateEmptyState();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(Intent.createChooser(intent, "Select .wasticker file"));
    }

    private void importWastickerFile(Uri uri) {
        WeakReference<StickerPackListActivity> ref = new WeakReference<>(this);
        executor.execute(() -> {
            StickerPackListActivity activity = ref.get();
            if (activity == null) return;
            String error;
            try {
                WastickerParser.importStickerPack(activity, uri);
                StickerContentProvider provider = StickerContentProvider.getInstance();
                if (provider != null) provider.invalidateStickerPackList();
                error = null;
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String finalError = error;
            mainHandler.post(() -> {
                StickerPackListActivity act = ref.get();
                if (act == null) return;
                if (finalError != null) {
                    Toast.makeText(act, "Import error: " + finalError, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(act, "Pack imported! Reloading...", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(act, EntryActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(intent);
                    act.finish();
                }
            });
        });
    }

    private void deletePack(int position) {
        StickerPack pack = stickerPackList.get(position);
        WeakReference<StickerPackListActivity> ref = new WeakReference<>(this);
        executor.execute(() -> {
            StickerPackListActivity activity = ref.get();
            if (activity == null) return;
            String error;
            try {
                WastickerParser.deleteStickerPack(activity, pack.identifier);
                error = null;
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String finalError = error;
            mainHandler.post(() -> {
                StickerPackListActivity act = ref.get();
                if (act == null) return;
                if (finalError != null) {
                    Toast.makeText(act, "Error: " + finalError, Toast.LENGTH_LONG).show();
                    act.allStickerPacksListAdapter.notifyItemChanged(position);
                } else {
                    act.stickerPackList.remove(position);
                    act.allStickerPacksListAdapter.notifyItemRemoved(position);
                    act.updateEmptyState();
                    Toast.makeText(act, "Pack deleted", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void updateEmptyState() {
        if (stickerPackList.isEmpty()) {
            packRecyclerView.setVisibility(View.GONE);
            emptyStateLayout.setVisibility(View.VISIBLE);
            importFab.setVisibility(View.GONE);
        } else {
            packRecyclerView.setVisibility(View.VISIBLE);
            emptyStateLayout.setVisibility(View.GONE);
            importFab.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, android.R.id.home, Menu.NONE, "Settings")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if ("Settings".equals(item.getTitle())) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        whitelistCheckCancelled = false;
        WeakReference<StickerPackListActivity> ref = new WeakReference<>(this);
        final StickerPack[] packs = stickerPackList.toArray(new StickerPack[0]);
        executor.execute(() -> {
            if (whitelistCheckCancelled) return;
            StickerPackListActivity activity = ref.get();
            if (activity == null) return;
            for (StickerPack stickerPack : packs) {
                stickerPack.setIsWhitelisted(WhitelistCheck.isWhitelisted(activity, stickerPack.identifier));
            }
            final List<StickerPack> result = Arrays.asList(packs);
            mainHandler.post(() -> {
                if (whitelistCheckCancelled) return;
                StickerPackListActivity act = ref.get();
                if (act == null) return;
                act.allStickerPacksListAdapter.setStickerPackList(result);
                act.allStickerPacksListAdapter.notifyDataSetChanged();
            });
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        whitelistCheckCancelled = true;
    }

    private void showStickerPackList(List<StickerPack> stickerPackList) {
        allStickerPacksListAdapter = new StickerPackListAdapter(stickerPackList, onAddButtonClickedListener);
        packRecyclerView.setAdapter(allStickerPacksListAdapter);
        packLayoutManager = new LinearLayoutManager(this);
        packLayoutManager.setOrientation(RecyclerView.VERTICAL);
        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(
                packRecyclerView.getContext(),
                packLayoutManager.getOrientation()
        );
        packRecyclerView.addItemDecoration(dividerItemDecoration);
        packRecyclerView.setLayoutManager(packLayoutManager);
        packRecyclerView.getViewTreeObserver().addOnGlobalLayoutListener(this::recalculateColumnCount);
    }

    private final StickerPackListAdapter.OnAddButtonClickedListener onAddButtonClickedListener = pack -> addStickerPackToWhatsApp(pack.identifier, pack.name);

    private void recalculateColumnCount() {
        final int previewSize = getResources().getDimensionPixelSize(R.dimen.sticker_pack_list_item_preview_image_size);
        int firstVisibleItemPosition = packLayoutManager.findFirstVisibleItemPosition();
        StickerPackListItemViewHolder viewHolder = (StickerPackListItemViewHolder) packRecyclerView.findViewHolderForAdapterPosition(firstVisibleItemPosition);
        if (viewHolder != null) {
            final int widthOfImageRow = viewHolder.imageRowView.getMeasuredWidth();
            final int max = Math.max(widthOfImageRow / previewSize, 1);
            int maxNumberOfImagesInARow = Math.min(STICKER_PREVIEW_DISPLAY_LIMIT, max);
            int minMarginBetweenImages = (widthOfImageRow - maxNumberOfImagesInARow * previewSize) / (maxNumberOfImagesInARow - 1);
            allStickerPacksListAdapter.setImageRowSpec(maxNumberOfImagesInARow, minMarginBetweenImages);
        }
    }

}
