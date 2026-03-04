package com.kawai.mochii;

import java.util.ArrayDeque;
import java.util.Deque;

public class AppLogger {
    private static final int MAX_LOG_ENTRIES = 500;
    private static final Deque<String> buffer = new ArrayDeque<>();

    public static synchronized void log(String tag, String msg) {
        String entry = tag + ": " + msg;
        buffer.addLast(entry);
        if (buffer.size() > MAX_LOG_ENTRIES) {
            buffer.removeFirst();
        }
        android.util.Log.d(tag, msg);
    }

    public static synchronized String getLogs() {
        StringBuilder sb = new StringBuilder();
        for (String entry : buffer) {
            sb.append(entry).append("\n");
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        buffer.clear();
    }
}
