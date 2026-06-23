package com.kawai.mochi;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;

/**
 * Thin HTTP wrapper for the Telegram Bot API.
 * Uses only {@link HttpURLConnection} — no extra dependencies.
 *
 * All methods are synchronous and must be called from a background thread.
 */
public class TelegramApiClient {
    private static final String TAG = "TelegramApiClient";
    private static final String BASE_URL = "https://api.telegram.org/bot";
    private static final String FILE_BASE_URL = "https://api.telegram.org/file/bot";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS    = 60_000;
    private static final int MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024; // 20 MB
    private static final int MAX_RETRIES        = 3;
    private static final int[] RETRY_DELAYS_MS  = {400, 1000, 2000};

    private final String botToken;
    private final Context context;

    public TelegramApiClient(String botToken, Context context) {
        this.botToken = botToken;
        this.context = context;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns the raw JSON result object from getStickerSet.
     * @param setName  The sticker set short name (e.g. "HotCherry").
     */
    public JSONObject getStickerSet(String setName) throws IOException, JSONException {
        String endpoint = BASE_URL + botToken + "/getStickerSet?name=" + encode(setName);
        JSONObject response = getJsonWithRetry(endpoint);
        if (!response.optBoolean("ok", false)) {
            throw new IOException("Telegram API error: " + response.optString("description", "unknown error"));
        }
        return response.getJSONObject("result");
    }

    /**
     * Downloads a file by its Telegram file_id.
     * @return Raw bytes of the downloaded file.
     */
    public byte[] downloadFile(String fileId) throws IOException, JSONException {
        // Step 1: resolve file_path from file_id
        String infoEndpoint = BASE_URL + botToken + "/getFile?file_id=" + encode(fileId);
        JSONObject info = getJsonWithRetry(infoEndpoint);
        if (!info.optBoolean("ok", false)) {
            throw new IOException("getFile failed: " + info.optString("description", "unknown"));
        }
        String filePath = info.getJSONObject("result").getString("file_path");

        // Step 2: download the actual bytes
        String downloadUrl = FILE_BASE_URL + botToken + "/" + filePath;
        return getBytesWithRetry(downloadUrl);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private JSONObject getJson(String urlStr) throws IOException, JSONException {
        HttpURLConnection conn = openConnection(urlStr);
        try {
            conn.setRequestMethod("GET");
            conn.connect();
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readString(stream);
            Log.d(TAG, "GET " + urlStr + " → " + code);
            if (code == 429 || (code >= 500 && code < 600)) {
                throw new IOException("Transient HTTP " + code + " for " + urlStr);
            }
            return new JSONObject(body);
        } finally {
            conn.disconnect();
        }
    }

    private JSONObject getJsonWithRetry(String urlStr) throws IOException, JSONException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return getJson(urlStr);
            } catch (IOException e) {
                last = e;
                if (!isTransient(e) || attempt == MAX_RETRIES) break;
                sleepBackoff(attempt, e.getMessage());
            }
        }
        throw last != null ? last : new IOException("Request failed: " + urlStr);
    }

    private byte[] getBytes(String urlStr) throws IOException {
        HttpURLConnection conn = openConnection(urlStr);
        try {
            conn.setRequestMethod("GET");
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                if (code == 429 || (code >= 500 && code < 600)) {
                    throw new IOException("Transient HTTP " + code + " downloading file");
                }
                throw new IOException("HTTP " + code + " downloading file");
            }
            long contentLength = conn.getContentLengthLong();
            if (contentLength > MAX_DOWNLOAD_BYTES) {
                throw new IOException("File too large (" + (contentLength / 1024) + " KB)");
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(
                        contentLength > 0 ? (int) contentLength : 64 * 1024);
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("File exceeded 20 MB limit during download");
                    }
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    private byte[] getBytesWithRetry(String urlStr) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return getBytes(urlStr);
            } catch (IOException e) {
                last = e;
                if (!isTransient(e) || attempt == MAX_RETRIES) break;
                sleepBackoff(attempt, e.getMessage());
            }
        }
        throw last != null ? last : new IOException("Download failed: " + urlStr);
    }

    private HttpURLConnection openConnection(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setUseCaches(false);
        return conn;
    }

    private String readString(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }

    private String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private boolean isTransient(IOException e) {
        if (e instanceof UnknownHostException
                || e instanceof SocketTimeoutException
                || e instanceof ConnectException
                || e instanceof InterruptedIOException) {
            return true;
        }
        String msg = e.getMessage();
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("transient http")
                || m.contains("timeout")
                || m.contains("tempor")
                || m.contains("connection reset")
                || m.contains("unable to resolve host")
                || m.contains("failed to connect");
    }

    private void sleepBackoff(int attempt, String reason) {
        int idx = Math.max(0, Math.min(RETRY_DELAYS_MS.length - 1, attempt - 1));
        int delay = RETRY_DELAYS_MS[idx];
        Log.w(TAG, "Transient Telegram API failure (attempt " + attempt + "): " + reason
                + ". Retrying in " + delay + "ms");
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
