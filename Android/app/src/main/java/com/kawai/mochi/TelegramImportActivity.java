package com.kawai.mochi;

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.core.widget.NestedScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Telegram import page.
 * Supports foreground/background conversion via {@link TelegramConversionService}.
 */
public class TelegramImportActivity extends AddStickerPackActivity {

    public static final String EXTRA_TASK_ID = "extra_task_id";
    private static final int REQUEST_NOTIFICATION_PERMISSION = 1001;

    // Views
    private TextInputEditText urlInput;
    private TextInputEditText packNameInput;
    private TextInputEditText authorInput;
    private TextInputLayout urlInputLayout;
    private MaterialButton convertButton;
    private LinearProgressIndicator progressBar;
    private TextView progressText;
    private MaterialCardView logCard;
    private NestedScrollView logScroll;
    private TextView logTextView;
    private MaterialButton copyLogButton;
    private MaterialButton toggleAdvancedLogButton;
    private MaterialCardView advancedLogCard;
    private NestedScrollView advancedLogScroll;
    private TextView advancedLogTextView;
    private MaterialButton copyAdvancedLogButton;
    private LinearLayout resultsSection;
    private LinearLayout resultsContainer;
    private MaterialButton doneButton;

    // State
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService metadataExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean converting = new AtomicBoolean(false);

    private Future<?> metadataFetchTask;
    private boolean suppressPackNameWatcher = false;
    private boolean packNameManuallyEdited = false;
    private String lastAutoFilledPackName = "";
    private int metadataRequestId = 0;
    private String activeTaskId;
    private boolean advancedLogsVisible = false;

    private final BroadcastReceiver taskUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!TelegramConversionService.ACTION_TASK_UPDATED.equals(intent.getAction())) return;
            String taskId = intent.getStringExtra(TelegramConversionService.EXTRA_TASK_ID);
            if (taskId == null) return;
            if (activeTaskId == null || !activeTaskId.equals(taskId)) return;
            renderTaskSnapshot();
        }
    };

    private final Runnable fetchPackNameRunnable = () -> {
        if (converting.get()) return;
        String raw = urlInput != null && urlInput.getText() != null
                ? urlInput.getText().toString().trim() : "";
        if (raw.isEmpty()) return;
        if (!BotTokenManager.isBotTokenSet(this)) return;

        final String setName = TelegramConverter.extractSetName(raw);
        if (setName.isEmpty()) return;
        final int requestId = ++metadataRequestId;

        if (metadataFetchTask != null) metadataFetchTask.cancel(true);
        metadataFetchTask = metadataExecutor.submit(() -> {
            try {
                String botToken = BotTokenManager.getBotToken(TelegramImportActivity.this);
                if (botToken.isEmpty()) return;

                TelegramApiClient api = new TelegramApiClient(botToken, TelegramImportActivity.this);
                JSONObject info = api.getStickerSet(setName);
                String fetchedTitle = info.optString("title", setName).trim();
                if (fetchedTitle.isEmpty()) fetchedTitle = setName;

                final String finalFetchedTitle = fetchedTitle;
                mainHandler.post(() -> applyFetchedPackNameIfAllowed(requestId, finalFetchedTitle));
            } catch (CancellationException ignored) {
            } catch (Exception ignored) {
                // Ignore metadata fetch failure; conversion call path still shows actionable errors.
            }
        });
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_telegram_import);

        Toolbar toolbar = findViewById(R.id.tg_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_close_x);
        }

        urlInput = findViewById(R.id.tg_url_input);
        packNameInput = findViewById(R.id.tg_pack_name_input);
        authorInput = findViewById(R.id.tg_author_input);
        urlInputLayout = findViewById(R.id.tg_url_input_layout);
        convertButton = findViewById(R.id.tg_convert_button);
        progressBar = findViewById(R.id.tg_progress_bar);
        progressText = findViewById(R.id.tg_progress_text);
        logCard = findViewById(R.id.tg_log_card);
        logScroll = findViewById(R.id.tg_log_scroll);
        logTextView = findViewById(R.id.tg_log_text);
        toggleAdvancedLogButton = findViewById(R.id.tg_toggle_advanced_log_button);
        advancedLogCard = findViewById(R.id.tg_advanced_log_card);
        advancedLogScroll = findViewById(R.id.tg_advanced_log_scroll);
        advancedLogTextView = findViewById(R.id.tg_advanced_log_text);
        
        logScroll.setOnTouchListener((v, event) -> {
            int action = event.getAction();
            if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_MOVE) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });

        advancedLogScroll.setOnTouchListener((v, event) -> {
            int action = event.getAction();
            if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_MOVE) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        
        copyLogButton = findViewById(R.id.tg_copy_log_button);
        copyAdvancedLogButton = findViewById(R.id.tg_copy_advanced_log_button);
        resultsSection = findViewById(R.id.tg_results_section);
        resultsContainer = findViewById(R.id.tg_results_container);
        doneButton = findViewById(R.id.tg_done_button);

        SharedPreferences prefs = getSharedPreferences("telegram_import", Context.MODE_PRIVATE);
        String lastAuthor = prefs.getString("last_author", null);
        if (lastAuthor != null && authorInput != null) {
            authorInput.setText(lastAuthor);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION_PERMISSION);
            }
        }

        convertButton.setOnClickListener(v -> startConversion());
        copyLogButton.setOnClickListener(v -> copyLogToClipboard());
        toggleAdvancedLogButton.setOnClickListener(v -> toggleAdvancedLogs());
        copyAdvancedLogButton.setOnClickListener(v -> copyAdvancedLogToClipboard());
        doneButton.setOnClickListener(v -> {
            setResult(RESULT_OK);
            finish();
        });

        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                startConversion();
                return true;
            }
            return false;
        });

        urlInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mainHandler.removeCallbacks(fetchPackNameRunnable);
                if (s != null && s.length() > 0) {
                    mainHandler.postDelayed(fetchPackNameRunnable, 450);
                }
                updateConvertButtonState();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        packNameInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppressPackNameWatcher) return;
                packNameManuallyEdited = true;
                updateConvertButtonState();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        String taskId = getIntent().getStringExtra(EXTRA_TASK_ID);
        if (taskId != null && !taskId.trim().isEmpty()) {
            activeTaskId = taskId;
            renderTaskSnapshot();
        }
        
        ConversionTaskManager.getInstance().init(this);
        updateConvertButtonState();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String taskId = intent.getStringExtra(EXTRA_TASK_ID);
        if (taskId != null && !taskId.trim().isEmpty()) {
            activeTaskId = taskId;
            renderTaskSnapshot();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(TelegramConversionService.ACTION_TASK_UPDATED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(taskUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ContextCompat.registerReceiver(this, taskUpdateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        }
        if (activeTaskId != null) renderTaskSnapshot();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(taskUpdateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (metadataFetchTask != null) metadataFetchTask.cancel(true);
        mainHandler.removeCallbacks(fetchPackNameRunnable);
        metadataExecutor.shutdownNow();
    }

    private void startConversion() {
        if (!BotTokenManager.isBotTokenSet(this)) {
            BotTokenInputDialog.show(this, getString(R.string.telegram_bot_token_first_use),
                    new BotTokenInputDialog.OnTokenInputListener() {
                        @Override
                        public void onTokenSaved(String token) {
                            enqueueConversionTask();
                        }

                        @Override
                        public void onTokenInputCancelled() {
                            Toast.makeText(TelegramImportActivity.this,
                                    R.string.telegram_bot_token_required, Toast.LENGTH_SHORT).show();
                        }
                    });
            return;
        }
        enqueueConversionTask();
    }

    private void enqueueConversionTask() {
        if (converting.get()) return;

        String raw = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
        String packName = packNameInput.getText() != null ? packNameInput.getText().toString().trim() : "";
        String author = authorInput.getText() != null ? authorInput.getText().toString().trim() : "";

        if (raw.isEmpty()) {
            urlInputLayout.setError(getString(R.string.telegram_url_hint));
            return;
        }
        urlInputLayout.setError(null);
        hideKeyboard();

        if (!author.isEmpty()) {
            getSharedPreferences("telegram_import", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_author", author)
                    .apply();
        } else {
            author = "Telegram";
        }

        // Reset UI for new task.
        logBufferReset();
        resultsSection.setVisibility(View.GONE);
        resultsContainer.removeAllViews();

        activeTaskId = ConversionTaskManager.getInstance().createTask(raw, author, packName);
        setConvertingUi(true);

        TelegramConversionService.enqueue(this, activeTaskId, raw, author, packName);
        appendLog("⏳ Task queued. Conversion will continue in background if you leave this page.");
        renderTaskSnapshot();
    }

    private void renderTaskSnapshot() {
        if (activeTaskId == null) return;

        ConversionTaskManager.TaskRecord task = ConversionTaskManager.getInstance().getTask(activeTaskId);
        if (task == null) return;

        if (urlInput.getText() == null || urlInput.getText().toString().trim().isEmpty()) {
            urlInput.setText(task.url);
        }
        if (authorInput.getText() == null || authorInput.getText().toString().trim().isEmpty()) {
            authorInput.setText(task.author);
        }
        if (packNameInput.getText() == null || packNameInput.getText().toString().trim().isEmpty()) {
            suppressPackNameWatcher = true;
            packNameInput.setText(task.packName);
            suppressPackNameWatcher = false;
        }

        StringBuilder logsBuilder = new StringBuilder();
        for (String line : task.logs) {
            logsBuilder.append(line).append('\n');
        }
        logCard.setVisibility(View.VISIBLE);
        logTextView.setText(logsBuilder.toString());

        StringBuilder advancedLogsBuilder = new StringBuilder();
        for (String line : task.advancedLogs) {
            advancedLogsBuilder.append(line).append('\n');
        }
        advancedLogTextView.setText(advancedLogsBuilder.toString());
        advancedLogCard.setVisibility(advancedLogsVisible ? View.VISIBLE : View.GONE);
        toggleAdvancedLogButton.setText(advancedLogsVisible ? R.string.hide_advanced_logs : R.string.advanced_logs);

        if (task.status == ConversionTaskManager.Status.RUNNING || task.status == ConversionTaskManager.Status.QUEUED) {
            setConvertingUi(true);
            if (task.total > 0) {
                progressBar.setIndeterminate(false);
                progressBar.setMax(task.total);
                progressBar.setProgress(task.done, true);
                progressText.setText(getString(R.string.tg_progress_label, task.done, task.total));
            } else {
                progressBar.setIndeterminate(true);
                progressText.setText(R.string.tg_fetching_label);
            }
            return;
        }

        setConvertingUi(false);
        if (task.status == ConversionTaskManager.Status.SUCCEEDED) {
            showResultsFromTask(task);
        } else if (task.status == ConversionTaskManager.Status.FAILED) {
            if (task.error != null && !task.error.trim().isEmpty()) {
                progressText.setVisibility(View.VISIBLE);
                progressText.setText(task.error);
            }
        }
    }

    private void showResultsFromTask(ConversionTaskManager.TaskRecord task) {
        resultsContainer.removeAllViews();
        resultsSection.setVisibility(View.VISIBLE);

        for (ConversionTaskManager.TaskPackResult r : task.results) {
            TelegramConverter.ImportedPackResult converted = new TelegramConverter.ImportedPackResult(
                    r.identifier,
                    r.name,
                    r.stickerCount,
                    r.isAnimated
            );
            addResultCard(converted);
        }

        progressText.setVisibility(View.GONE);
        mainHandler.postDelayed(() ->
                ((androidx.core.widget.NestedScrollView) findViewById(R.id.tg_scroll))
                        .smoothScrollTo(0, resultsSection.getTop()), 150);
    }

    private void setConvertingUi(boolean running) {
        converting.set(running);
        urlInput.setEnabled(!running);
        packNameInput.setEnabled(!running);
        authorInput.setEnabled(!running);

        progressBar.setVisibility(running ? View.VISIBLE : View.GONE);
        progressText.setVisibility(running ? View.VISIBLE : View.GONE);
        if (!running) {
            progressBar.setIndeterminate(false);
        }
        updateConvertButtonState();
    }

    private void addResultCard(TelegramConverter.ImportedPackResult result) {
        View card = getLayoutInflater().inflate(R.layout.item_imported_pack_result, resultsContainer, false);

        ImageView icon = card.findViewById(R.id.result_pack_icon);
        TextView name = card.findViewById(R.id.result_pack_name);
        TextView badge = card.findViewById(R.id.result_type_badge);
        TextView count = card.findViewById(R.id.result_sticker_count);
        MaterialButton addBtn = card.findViewById(R.id.result_add_to_whatsapp);
        MaterialButton exportBtn = card.findViewById(R.id.result_export_button);

        icon.setImageResource(result.isAnimated ? R.drawable.animated_pack_indicator : R.drawable.ic_fab_add);
        name.setText(result.name);
        badge.setText(result.isAnimated ? R.string.animated_badge : R.string.static_badge);
        count.setText(getResources().getQuantityString(
                R.plurals.sticker_count_plural, result.stickerCount, result.stickerCount));

        addBtn.setOnClickListener(v -> addStickerPackToWhatsApp(result.identifier, result.name));
        exportBtn.setOnClickListener(v -> exportConvertedPack(result.identifier, result.name));

        resultsContainer.addView(card);
    }

    private void appendLog(String message) {
        logCard.setVisibility(View.VISIBLE);
        String current = logTextView.getText() != null ? logTextView.getText().toString() : "";
        logTextView.setText(current + message + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void logBufferReset() {
        logCard.setVisibility(View.VISIBLE);
        logTextView.setText("");
    }

    private void copyLogToClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Conversion Log", logTextView.getText()));
            Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
        }
    }

    private void copyAdvancedLogToClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Advanced Conversion Log", advancedLogTextView.getText()));
            Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
        }
    }

    private void exportConvertedPack(String identifier, String displayName) {
        try {
            java.io.File exportFile = WastickerParser.exportStickerPackZip(this, identifier);
            if (exportFile == null || !exportFile.exists()) {
                throw new java.io.IOException("Export file could not be created");
            }

            String authority = getPackageName() + ".fileprovider";
            android.net.Uri uri = FileProvider.getUriForFile(this, authority, exportFile);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/zip");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            startActivity(Intent.createChooser(shareIntent, getString(R.string.export_button) + ": " + displayName));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.error_with_message, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void toggleAdvancedLogs() {
        advancedLogsVisible = !advancedLogsVisible;
        renderTaskSnapshot();
        if (advancedLogsVisible && advancedLogScroll != null) {
            advancedLogScroll.post(() -> advancedLogScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && urlInput != null) {
            imm.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
        }
    }

    private void applyFetchedPackNameIfAllowed(int requestId, String fetchedTitle) {
        if (requestId != metadataRequestId) return;
        if (packNameInput == null) return;

        String current = packNameInput.getText() != null ? packNameInput.getText().toString().trim() : "";
        boolean shouldApply = !packNameManuallyEdited || current.isEmpty() || current.equals(lastAutoFilledPackName);
        if (!shouldApply) return;

        suppressPackNameWatcher = true;
        packNameInput.setText(fetchedTitle);
        packNameInput.setSelection(fetchedTitle.length());
        suppressPackNameWatcher = false;
        lastAutoFilledPackName = fetchedTitle;
        updateConvertButtonState();
    }

    private void updateConvertButtonState() {
        if (converting.get() || convertButton == null) {
            if (convertButton != null) convertButton.setEnabled(false);
            return;
        }
        String url = urlInput != null && urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
        String pack = packNameInput != null && packNameInput.getText() != null ? packNameInput.getText().toString().trim() : "";
        convertButton.setEnabled(!url.isEmpty() && !pack.isEmpty());
    }
}
