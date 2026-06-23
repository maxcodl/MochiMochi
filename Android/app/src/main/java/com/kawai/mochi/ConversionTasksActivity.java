package com.kawai.mochi;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import java.util.List;

public class ConversionTasksActivity extends BaseActivity {

    private LinearLayout tasksContainer;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conversion_tasks);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.tasks_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.telegram_conversion_tasks_title);
        }

        tasksContainer = findViewById(R.id.tasks_container);
        emptyView = findViewById(R.id.tasks_empty);
        
        ConversionTaskManager.getInstance().init(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderTasks();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void renderTasks() {
        List<ConversionTaskManager.TaskRecord> tasks = ConversionTaskManager.getInstance().listTasks();
        tasksContainer.removeAllViews();

        if (tasks.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        emptyView.setVisibility(View.GONE);

        for (ConversionTaskManager.TaskRecord task : tasks) {
            View row = getLayoutInflater().inflate(R.layout.item_conversion_task, tasksContainer, false);
            TextView title = row.findViewById(R.id.task_title);
            TextView subtitle = row.findViewById(R.id.task_subtitle);
            TextView status = row.findViewById(R.id.task_status);
            MaterialButton openButton = row.findViewById(R.id.task_open_button);

            String name = task.packName != null && !task.packName.trim().isEmpty()
                    ? task.packName
                    : TelegramConverter.extractSetName(task.url);

            title.setText(name);
            subtitle.setText(task.url);

            String statusText;
            switch (task.status) {
                case RUNNING:
                    statusText = getString(R.string.telegram_conversion_task_running, task.done, Math.max(task.total, task.done));
                    break;
                case SUCCEEDED:
                    statusText = getString(R.string.telegram_conversion_task_done, task.results.size());
                    break;
                case FAILED:
                    statusText = getString(R.string.telegram_conversion_task_failed, task.error != null ? task.error : "-");
                    break;
                default:
                    statusText = getString(R.string.telegram_conversion_task_queued);
                    break;
            }
            status.setText(statusText);

            openButton.setOnClickListener(v -> {
                Intent intent = new Intent(ConversionTasksActivity.this, TelegramImportActivity.class);
                intent.putExtra(TelegramImportActivity.EXTRA_TASK_ID, task.taskId);
                startActivity(intent);
            });

            row.setOnClickListener(v -> {
                Intent intent = new Intent(ConversionTasksActivity.this, TelegramImportActivity.class);
                intent.putExtra(TelegramImportActivity.EXTRA_TASK_ID, task.taskId);
                startActivity(intent);
            });

            tasksContainer.addView(row);
        }
    }
}
