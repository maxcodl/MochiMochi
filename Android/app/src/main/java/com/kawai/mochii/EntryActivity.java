package com.kawai.mochii;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class EntryActivity extends BaseActivity {
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private View progressBar;
    private final AtomicBoolean taskCancelled = new AtomicBoolean(false);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_entry);
        overridePendingTransition(0, 0);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        progressBar = findViewById(R.id.entry_activity_progress);

        // Handle incoming .wasticker share intent
        Intent intent = getIntent();
        Uri incomingUri = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            incomingUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            // Fallback: newer Android / Telegram may use ClipData instead of EXTRA_STREAM
            if (incomingUri == null && intent.getClipData() != null
                    && intent.getClipData().getItemCount() > 0) {
                incomingUri = intent.getClipData().getItemAt(0).getUri();
            }
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            incomingUri = intent.getData();
        }

        if (incomingUri != null) {
            loadWasticker(incomingUri);
        } else {
            loadList();
        }
    }

    private void showStickerPack(ArrayList<StickerPack> stickerPackList) {
        progressBar.setVisibility(View.GONE);
        // Always go to list screen
        final Intent intent = new Intent(this, StickerPackListActivity.class);
        intent.putParcelableArrayListExtra(StickerPackListActivity.EXTRA_STICKER_PACK_LIST_DATA, stickerPackList);
        startActivity(intent);
        finish();
        overridePendingTransition(0, 0);
    }

    private void showErrorMessage(String errorMessage) {
        progressBar.setVisibility(View.GONE);
        Log.e("EntryActivity", "error fetching sticker packs, " + errorMessage);
        // When no packs found, go to list activity with empty list for empty state
        final Intent intent = new Intent(this, StickerPackListActivity.class);
        intent.putParcelableArrayListExtra(StickerPackListActivity.EXTRA_STICKER_PACK_LIST_DATA, new ArrayList<>());
        startActivity(intent);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        taskCancelled.set(true);
    }

    private void loadList() {
        taskCancelled.set(false);
        WeakReference<EntryActivity> ref = new WeakReference<>(this);
        executor.execute(() -> {
            Pair<String, ArrayList<StickerPack>> result;
            try {
                EntryActivity activity = ref.get();
                if (activity == null || taskCancelled.get()) return;
                ArrayList<StickerPack> stickerPackList = StickerPackLoader.fetchStickerPacks(activity);
                if (stickerPackList.size() == 0) {
                    result = new Pair<>(null, new ArrayList<>());
                } else {
                    for (StickerPack stickerPack : stickerPackList) {
                        try {
                            StickerPackValidator.verifyStickerPackValidity(activity, stickerPack);
                        } catch (Exception e) {
                            Log.w("EntryActivity", "Validation warning for " + stickerPack.name + ": " + e.getMessage());
                        }
                    }
                    result = new Pair<>(null, stickerPackList);
                }
            } catch (Exception e) {
                Log.e("EntryActivity", "error fetching sticker packs", e);
                result = new Pair<>(e.getMessage(), null);
            }
            final Pair<String, ArrayList<StickerPack>> finalResult = result;
            mainHandler.post(() -> {
                EntryActivity activity = ref.get();
                if (activity == null || taskCancelled.get()) return;
                if (finalResult.second != null) {
                    activity.showStickerPack(finalResult.second);
                } else {
                    activity.showErrorMessage(finalResult.first);
                }
            });
        });
    }

    private void loadWasticker(Uri uri) {
        WeakReference<EntryActivity> ref = new WeakReference<>(this);
        executor.execute(() -> {
            EntryActivity activity = ref.get();
            if (activity == null) return;
            Pair<String, ArrayList<StickerPack>> result;
            try {
                WastickerParser.importStickerPack(activity, uri);
                ArrayList<StickerPack> packs = StickerPackLoader.fetchStickerPacks(activity);
                result = new Pair<>(null, packs);
            } catch (Exception e) {
                Log.e("EntryActivity", "error importing wasticker", e);
                result = new Pair<>(e.getMessage(), null);
            }
            final Pair<String, ArrayList<StickerPack>> finalResult = result;
            mainHandler.post(() -> {
                EntryActivity act = ref.get();
                if (act == null) return;
                if (finalResult.first != null) {
                    Toast.makeText(act, "Import error: " + finalResult.first, Toast.LENGTH_LONG).show();
                    act.showErrorMessage(finalResult.first);
                } else {
                    Toast.makeText(act, "Sticker pack imported!", Toast.LENGTH_SHORT).show();
                    act.showStickerPack(finalResult.second != null ? finalResult.second : new ArrayList<>());
                }
            });
        });
    }
}
