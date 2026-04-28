package com.kawai.mochi;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** In-memory task store for Telegram conversions. */
public class ConversionTaskManager {

    public enum Status {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public static class TaskPackResult {
        public final String identifier;
        public final String name;
        public final int stickerCount;
        public final boolean isAnimated;

        public TaskPackResult(String identifier, String name, int stickerCount, boolean isAnimated) {
            this.identifier = identifier;
            this.name = name;
            this.stickerCount = stickerCount;
            this.isAnimated = isAnimated;
        }
    }

    public static class TaskRecord {
        public final String taskId;
        public final long createdAt;
        public String url;
        public String author;
        public String packName;
        public Status status;
        public int done;
        public int total;
        public String error;
        public final List<String> logs;
        public final List<String> advancedLogs;
        public final List<TaskPackResult> results;

        JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("taskId", taskId);
            obj.put("createdAt", createdAt);
            obj.put("url", url);
            obj.put("author", author);
            obj.put("packName", packName);
            obj.put("status", status.name());
            obj.put("done", done);
            obj.put("total", total);
            obj.put("error", error != null ? error : JSONObject.NULL);
            JSONArray logsArr = new JSONArray();
            for (String log : logs) logsArr.put(log);
            obj.put("logs", logsArr);
            JSONArray advancedLogsArr = new JSONArray();
            for (String log : advancedLogs) advancedLogsArr.put(log);
            obj.put("advanced_logs", advancedLogsArr);
            JSONArray resultsArr = new JSONArray();
            for (TaskPackResult res : results) {
                JSONObject r = new JSONObject();
                r.put("identifier", res.identifier);
                r.put("name", res.name);
                r.put("stickerCount", res.stickerCount);
                r.put("isAnimated", res.isAnimated);
                resultsArr.put(r);
            }
            obj.put("results", resultsArr);
            return obj;
        }

        static TaskRecord fromJson(JSONObject obj) throws JSONException {
            long created = obj.optLong("createdAt", System.currentTimeMillis());
            TaskRecord t = new TaskRecord(
                    obj.getString("taskId"),
                    obj.optString("url", ""),
                    obj.optString("author", ""),
                    obj.optString("packName", ""),
                    created
            );
            t.status = Status.valueOf(obj.optString("status", Status.QUEUED.name()));
            t.done = obj.optInt("done", 0);
            t.total = obj.optInt("total", 0);
            t.error = obj.isNull("error") ? null : obj.optString("error", null);
            JSONArray logsArr = obj.optJSONArray("logs");
            if (logsArr != null) {
                for (int i = 0; i < logsArr.length(); i++) {
                    t.logs.add(logsArr.getString(i));
                }
            }
            JSONArray advancedLogsArr = obj.optJSONArray("advanced_logs");
            if (advancedLogsArr != null) {
                for (int i = 0; i < advancedLogsArr.length(); i++) {
                    t.advancedLogs.add(advancedLogsArr.getString(i));
                }
            }
            JSONArray resultsArr = obj.optJSONArray("results");
            if (resultsArr != null) {
                for (int i = 0; i < resultsArr.length(); i++) {
                    JSONObject r = resultsArr.getJSONObject(i);
                    t.results.add(new TaskPackResult(
                            r.getString("identifier"),
                            r.getString("name"),
                            r.getInt("stickerCount"),
                            r.getBoolean("isAnimated")
                    ));
                }
            }
            return t;
        }

        TaskRecord(String taskId, String url, String author, String packName, long createdAt) {
            this.taskId = taskId;
            this.createdAt = createdAt;
            this.url = url;
            this.author = author;
            this.packName = packName;
            this.status = Status.QUEUED;
            this.logs = new ArrayList<>();
            this.advancedLogs = new ArrayList<>();
            this.results = new ArrayList<>();
        }

        TaskRecord(String taskId, String url, String author, String packName) {
            this(taskId, url, author, packName, System.currentTimeMillis());
        }

        TaskRecord(TaskRecord other) {
            this.taskId = other.taskId;
            this.createdAt = other.createdAt;
            this.url = other.url;
            this.author = other.author;
            this.packName = other.packName;
            this.status = other.status;
            this.done = other.done;
            this.total = other.total;
            this.error = other.error;
            this.logs = new ArrayList<>(other.logs);
            this.advancedLogs = new ArrayList<>(other.advancedLogs);
            this.results = new ArrayList<>(other.results);
        }
    }

    private static final ConversionTaskManager INSTANCE = new ConversionTaskManager();
    private final List<TaskRecord> tasks = new ArrayList<>();
    private Context appContext;

    public static ConversionTaskManager getInstance() {
        return INSTANCE;
    }

    public synchronized void init(Context context) {
        if (appContext == null && context != null) {
            appContext = context.getApplicationContext();
            loadFromPrefs();
        }
    }

    private void loadFromPrefs() {
        if (appContext == null) return;
        SharedPreferences prefs = appContext.getSharedPreferences("telegram_conversion_tasks", Context.MODE_PRIVATE);
        String json = prefs.getString("tasks_json", "[]");
        try {
            JSONArray arr = new JSONArray(json);
            tasks.clear();
            for (int i = 0; i < arr.length(); i++) {
                tasks.add(TaskRecord.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void saveToPrefs() {
        if (appContext == null) return;
        JSONArray arr = new JSONArray();
        for (TaskRecord t : tasks) {
            try {
                arr.put(t.toJson());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        SharedPreferences prefs = appContext.getSharedPreferences("telegram_conversion_tasks", Context.MODE_PRIVATE);
        prefs.edit().putString("tasks_json", arr.toString()).apply();
    }

    private ConversionTaskManager() {
    }

    public synchronized String createTask(String url, String author, String packName) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        TaskRecord record = new TaskRecord(id, url, author, packName);
        tasks.add(record);
        saveToPrefs();
        return id;
    }

    public synchronized void markRunning(String taskId) {
        TaskRecord t = find(taskId);
        if (t == null) return;
        t.status = Status.RUNNING;
        saveToPrefs();
    }

    public synchronized void updateProgress(String taskId, int done, int total) {
        TaskRecord t = find(taskId);
        if (t == null) return;
        t.done = done;
        t.total = total;
        // Don't save to prefs on every progress tick to avoid I/O overhead.
    }

    public synchronized void appendLog(String taskId, String message) {
        TaskRecord t = find(taskId);
        if (t == null) return;
        t.logs.add(message);
        if (t.logs.size() > 400) {
            t.logs.remove(0);
        }
        // Don't save to prefs on every log to avoid I/O overhead.
    }

    public synchronized void appendAdvancedLog(String taskId, String message) {
        TaskRecord t = find(taskId);
        if (t == null) return;
        t.advancedLogs.add(message);
        if (t.advancedLogs.size() > 1200) {
            t.advancedLogs.remove(0);
        }
        saveToPrefs();
    }

    public synchronized void updateLastLog(String taskId, String message) {
        TaskRecord t = find(taskId);
        if (t == null || t.logs.isEmpty()) return;
        t.logs.set(t.logs.size() - 1, message);
        // Don't save to prefs on every log to avoid I/O overhead.
    }

    public synchronized void updateLastAdvancedLog(String taskId, String message) {
        TaskRecord t = find(taskId);
        if (t == null || t.advancedLogs.isEmpty()) return;
        t.advancedLogs.set(t.advancedLogs.size() - 1, message);
        saveToPrefs();
    }

    public synchronized void markSucceeded(String taskId, List<TaskPackResult> results) {
        TaskRecord t = find(taskId);
        if (t == null) return;
        t.status = Status.SUCCEEDED;
        t.error = null;
        t.results.clear();
        if (results != null) t.results.addAll(results);
        saveToPrefs();
    }

    public synchronized void markFailed(String taskId, String error) {
        TaskRecord t = find(taskId);
        if (t == null) return;
        t.status = Status.FAILED;
        t.error = error;
        saveToPrefs();
    }

    public synchronized TaskRecord getTask(String taskId) {
        TaskRecord t = find(taskId);
        return t == null ? null : new TaskRecord(t);
    }

    public synchronized List<TaskRecord> listTasks() {
        List<TaskRecord> copy = new ArrayList<>();
        for (TaskRecord t : tasks) copy.add(new TaskRecord(t));
        Collections.sort(copy, Comparator.comparingLong(a -> -a.createdAt));
        return copy;
    }

    private TaskRecord find(String taskId) {
        for (TaskRecord t : tasks) {
            if (t.taskId.equals(taskId)) return t;
        }
        return null;
    }
}
