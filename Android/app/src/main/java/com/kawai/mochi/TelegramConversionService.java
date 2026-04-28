package com.kawai.mochi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class TelegramConversionService extends Service {

    public static final String ACTION_START = "com.kawai.mochi.action.START_TELEGRAM_CONVERSION";
    public static final String ACTION_TASK_UPDATED = "com.kawai.mochi.action.TELEGRAM_TASK_UPDATED";

    public static final String EXTRA_TASK_ID = "extra_task_id";
    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_AUTHOR = "extra_author";
    public static final String EXTRA_PACK_NAME = "extra_pack_name";

    private static final String CHANNEL_ID = "telegram_conversion_tasks";
    private static final int NOTIFICATION_ID = 4021;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicInteger PENDING_TASKS = new AtomicInteger(0);

    public static void enqueue(Context context, String taskId, String url, String author, String packName) {
        Intent intent = new Intent(context, TelegramConversionService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        intent.putExtra(EXTRA_URL, url);
        intent.putExtra(EXTRA_AUTHOR, author);
        intent.putExtra(EXTRA_PACK_NAME, packName);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        final String taskId = intent.getStringExtra(EXTRA_TASK_ID);
        final String url = intent.getStringExtra(EXTRA_URL);
        final String author = intent.getStringExtra(EXTRA_AUTHOR);
        final String packName = intent.getStringExtra(EXTRA_PACK_NAME);

        if (taskId == null || url == null) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        ensureChannel();
        Notification notification = buildNotification(getString(R.string.telegram_conversion_notif_title), getString(R.string.telegram_conversion_notif_queued), true, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        PENDING_TASKS.incrementAndGet();

        EXECUTOR.submit(() -> {
            ConversionTaskManager manager = ConversionTaskManager.getInstance();
            manager.init(getApplicationContext());
            manager.markRunning(taskId);
            manager.appendLog(taskId, "⏳ Task queued in background...");
            manager.appendAdvancedLog(taskId, "⏳ Task queued in background...");
            broadcastTaskUpdated(taskId);
            updateNotification(getString(R.string.telegram_conversion_notif_title), getString(R.string.telegram_conversion_notif_running), true, 0, 0);

            try {
                List<TelegramConverter.ImportedPackResult> imported = TelegramConverter.importFromUrl(
                        getApplicationContext(),
                        url,
                        author,
                        packName,
                        new TelegramConverter.ConversionCallback() {
                            @Override
                            public void onLog(String message) {
                                manager.appendAdvancedLog(taskId, message);
                                if (shouldShowInNormalLog(message)) {
                                    manager.appendLog(taskId, message);
                                }
                                broadcastTaskUpdated(taskId);
                            }

                            @Override
                            public void onLogReplace(String message) {
                                manager.appendAdvancedLog(taskId, message);
                                manager.updateLastLog(taskId, message);
                                manager.updateLastAdvancedLog(taskId, message);
                                broadcastTaskUpdated(taskId);
                            }

                            @Override
                            public void onProgress(int done, int total) {
                                manager.updateProgress(taskId, done, total);
                                updateNotification(getString(R.string.telegram_conversion_notif_title),
                                        getString(R.string.telegram_conversion_notif_progress, done, total),
                                        true,
                                        done,
                                        total);
                                broadcastTaskUpdated(taskId);
                            }
                        }
                );

                List<ConversionTaskManager.TaskPackResult> results = new ArrayList<>();
                for (TelegramConverter.ImportedPackResult r : imported) {
                    results.add(new ConversionTaskManager.TaskPackResult(r.identifier, r.name, r.stickerCount, r.isAnimated));
                }

                manager.markSucceeded(taskId, results);
                manager.appendLog(taskId, "✅ Background conversion completed.");
                manager.appendAdvancedLog(taskId, "✅ Background conversion completed.");
                updateNotification(getString(R.string.telegram_conversion_notif_done_title),
                        getString(R.string.telegram_conversion_notif_done_text, results.size()),
                        false,
                        0,
                        0);
                broadcastTaskUpdated(taskId);
            } catch (Exception e) {
                String err = e.getMessage() != null ? e.getMessage() : "Unknown error";
                manager.markFailed(taskId, err);
                manager.appendLog(taskId, "❌ Error: " + err);
                manager.appendAdvancedLog(taskId, "❌ Error: " + err);
                manager.appendAdvancedLog(taskId, Log.getStackTraceString(e));
                updateNotification(getString(R.string.telegram_conversion_notif_failed_title), err, false, 0, 0);
                broadcastTaskUpdated(taskId);
            } finally {
                int pending = PENDING_TASKS.decrementAndGet();
                if (pending <= 0) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_DETACH);
                    } else {
                        stopForeground(false);
                    }
                    stopSelf();
                }
            }
        });

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.telegram_conversion_notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.telegram_conversion_notif_channel_desc));
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String title, String text, boolean ongoing, int done, int total) {
        Intent tasksIntent = new Intent(this, ConversionTasksActivity.class);
        tasksIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                tasksIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_telegram)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(!ongoing);

        if (ongoing && total > 0) {
            builder.setProgress(total, Math.max(0, done), false);
        } else if (ongoing) {
            builder.setProgress(0, 0, true);
        }

        return builder.build();
    }

    private void updateNotification(String title, String text, boolean ongoing, int done, int total) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(title, text, ongoing, done, total));
        }
    }

    private void broadcastTaskUpdated(String taskId) {
        Intent intent = new Intent(ACTION_TASK_UPDATED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_TASK_ID, taskId);
        sendBroadcast(intent);
    }

    private boolean shouldShowInNormalLog(String message) {
        if (message == null || message.trim().isEmpty()) return false;
        return message.startsWith("✅")
                || message.startsWith("⚠")
                || message.startsWith("❌")
                || message.startsWith("💾")
                || message.startsWith("⏳")
                || message.contains("Downloaded")
                || message.contains("Converted")
                || message.contains("Saved pack")
                || message.contains("Task queued")
                || message.contains("Running")
                || message.contains("progress")
                || message.contains("Converting")
                || message.contains("Fetching");
    }
}
