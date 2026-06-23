package com.kawai.mochi;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.kawai.mochi.R;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lets the user select 2+ existing packs and merge them into a new pack.
 *
 * <p>Rules enforced:
 * <ul>
 *   <li>At least 2 packs must be selected to enable the merge button.</li>
 *   <li>The combined sticker count must not exceed 30 (WhatsApp limit). Stickers beyond 30 are
 *       silently dropped and the user is warned before confirming.</li>
 * </ul>
 */
public class MergeStickerPacksActivity extends BaseActivity {

    static final int MAX_STICKERS = 30;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private List<StickerPack> allPacks = new ArrayList<>();
    private boolean[] selectedMemo;

    private TextView countView;
    private TextView overLimitLabel;
    private ExtendedFloatingActionButton mergeButton;
    private LinearProgressIndicator progressBar;
    private MergePackAdapter adapter;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_merge_sticker_packs);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        countView     = findViewById(R.id.merge_sticker_count);
        overLimitLabel = findViewById(R.id.merge_over_limit_label);
        mergeButton   = findViewById(R.id.merge_button);
        progressBar   = findViewById(R.id.merge_progress_bar);

        RecyclerView recycler = findViewById(R.id.merge_pack_list);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        adapter = new MergePackAdapter();
        recycler.setAdapter(adapter);

        mergeButton.setEnabled(false);
        mergeButton.setOnClickListener(v -> confirmAndMerge());

        loadPacks();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    // -------------------------------------------------------------------------
    // Data loading
    // -------------------------------------------------------------------------

    private void loadPacks() {
        WeakReference<MergeStickerPacksActivity> ref = new WeakReference<>(this);
        executor.execute(() -> {
            List<StickerPack> packs;
            try {
                packs = StickerPackLoader.fetchStickerPacks(ref.get());
            } catch (Exception e) {
                packs = new ArrayList<>();
            }
            final List<StickerPack> finalPacks = packs;
            mainHandler.post(() -> {
                MergeStickerPacksActivity act = ref.get();
                if (act == null) return;
                act.allPacks = finalPacks;
                act.selectedMemo = new boolean[finalPacks.size()];
                act.adapter.notifyDataSetChanged();
                act.updateCounter();
            });
        });
    }

    // -------------------------------------------------------------------------
    // Selection helpers
    // -------------------------------------------------------------------------

    /** Returns the indices of packs the user has checked. */
    private List<Integer> selectedIndices() {
        List<Integer> result = new ArrayList<>();
        if (selectedMemo == null) return result;
        for (int i = 0; i < selectedMemo.length; i++) {
            if (selectedMemo[i]) result.add(i);
        }
        return result;
    }

    /** Total stickers that would end up in the merged pack (before any capping). */
    private int totalSelectedStickers() {
        int total = 0;
        for (int idx : selectedIndices()) {
            StickerPack p = allPacks.get(idx);
            if (p.getStickers() != null) total += p.getStickers().size();
        }
        return total;
    }

    /**
     * Returns true if all selected packs share the same animated/static type.
     * Returns true vacuously when fewer than 2 packs are selected.
     */
    private boolean selectedTypesCompatible() {
        List<Integer> sel = selectedIndices();
        if (sel.size() < 2) return true;
        boolean firstAnimated = allPacks.get(sel.get(0)).animatedStickerPack;
        for (int i = 1; i < sel.size(); i++) {
            if (allPacks.get(sel.get(i)).animatedStickerPack != firstAnimated) return false;
        }
        return true;
    }

    private void updateCounter() {
        int total = totalSelectedStickers();
        int capped = Math.min(total, MAX_STICKERS);
        countView.setText(capped + " / " + MAX_STICKERS);

        List<Integer> sel = selectedIndices();
        boolean overLimit = total > MAX_STICKERS;
        boolean compatible = selectedTypesCompatible();

        if (!compatible) {
            overLimitLabel.setText(R.string.merge_mixed_types_error);
            overLimitLabel.setVisibility(View.VISIBLE);
        } else if (overLimit) {
            overLimitLabel.setText(R.string.merge_over_limit);
            overLimitLabel.setVisibility(View.VISIBLE);
        } else {
            overLimitLabel.setVisibility(View.GONE);
        }

        mergeButton.setEnabled(sel.size() >= 2 && compatible);
    }

    // -------------------------------------------------------------------------
    // Merge confirm + execution
    // -------------------------------------------------------------------------

    private void confirmAndMerge() {
        List<Integer> idx = selectedIndices();
        if (idx.size() < 2) {
            Toast.makeText(this, R.string.merge_need_two_packs, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!selectedTypesCompatible()) {
            Toast.makeText(this, R.string.merge_mixed_types_error, Toast.LENGTH_LONG).show();
            return;
        }

        int total = totalSelectedStickers();
        String message;
        if (total > MAX_STICKERS) {
            int dropped = total - MAX_STICKERS;
            message = getString(R.string.merge_confirm_capped, MAX_STICKERS, dropped);
        } else {
            message = getString(R.string.merge_confirm, total, idx.size());
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.merge_packs_title)
                .setMessage(message)
                .setPositiveButton(R.string.merge_button_label, (d, w) -> executeMerge(idx))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void executeMerge(List<Integer> indices) {
        List<StickerPack> packsToMerge = new ArrayList<>();
        for (int i : indices) packsToMerge.add(allPacks.get(i));

        if (progressBar != null) {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(0);
            progressBar.setVisibility(View.VISIBLE);
        }
        mergeButton.setEnabled(false);

        WeakReference<MergeStickerPacksActivity> ref = new WeakReference<>(this);
        executor.execute(() -> {
            String error = null;
            try {
                WastickerParser.mergeStickerPacks(ref.get(), packsToMerge, MAX_STICKERS, (current, total) -> {
                    mainHandler.post(() -> {
                        if (ref.get() != null && ref.get().progressBar != null) {
                            ref.get().progressBar.setMax(total);
                            ref.get().progressBar.setProgress(current, true);
                        }
                    });
                });
                StickerContentProvider provider = StickerContentProvider.getInstance();
                if (provider != null) provider.invalidateStickerPackList();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String finalError = error;
            mainHandler.post(() -> {
                MergeStickerPacksActivity act = ref.get();
                if (act == null) return;
                if (act.progressBar != null) act.progressBar.setVisibility(View.GONE);
                act.mergeButton.setEnabled(true);

                if (finalError != null) {
                    Toast.makeText(act, act.getString(R.string.error_with_message, finalError), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(act, R.string.merge_success, Toast.LENGTH_SHORT).show();
                    act.setResult(RESULT_OK);
                    act.finish();
                }
            });
        });
    }

    // -------------------------------------------------------------------------
    // Adapter
    // -------------------------------------------------------------------------

    private class MergePackAdapter extends RecyclerView.Adapter<MergePackAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_merge_pack, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            StickerPack pack = allPacks.get(position);
            int count = pack.getStickers() != null ? pack.getStickers().size() : 0;

            holder.name.setText(pack.name);
            String typeLabel = pack.animatedStickerPack
                    ? holder.itemView.getContext().getString(R.string.pack_type_animated)
                    : holder.itemView.getContext().getString(R.string.pack_type_static);
            holder.meta.setText(typeLabel + "  ·  " + pack.publisher);
            holder.badge.setText(count + " / 30");
            holder.checkbox.setChecked(selectedMemo != null && selectedMemo[position]);

            holder.card.setOnClickListener(v -> {
                if (selectedMemo == null) return;
                selectedMemo[holder.getBindingAdapterPosition()] =
                        !selectedMemo[holder.getBindingAdapterPosition()];
                holder.checkbox.setChecked(selectedMemo[holder.getBindingAdapterPosition()]);
                updateCounter();
            });
        }

        @Override
        public int getItemCount() { return allPacks.size(); }

        class VH extends RecyclerView.ViewHolder {
            MaterialCardView card;
            CheckBox checkbox;
            TextView name, meta, badge;

            VH(@NonNull View itemView) {
                super(itemView);
                card     = (MaterialCardView) itemView;
                checkbox = itemView.findViewById(R.id.pack_checkbox);
                name     = itemView.findViewById(R.id.pack_name);
                meta     = itemView.findViewById(R.id.pack_meta);
                badge    = itemView.findViewById(R.id.pack_count_badge);
            }
        }
    }
}
