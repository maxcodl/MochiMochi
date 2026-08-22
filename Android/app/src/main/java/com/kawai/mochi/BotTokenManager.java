package com.kawai.mochi;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Manages Telegram Bot Token storage and retrieval securely via EncryptedSharedPreferences.
 */
public class BotTokenManager {
    private static final String TAG = "BotTokenManager";
    private static final String OLD_PREFS_NAME = "mochi_telegram_prefs";
    private static final String ENCRYPTED_PREFS_NAME = "mochi_telegram_prefs_encrypted";
    private static final String KEY_BOT_TOKEN = "telegram_bot_token";
    private static final String KEY_TOKEN_SAVED_TIME = "telegram_token_saved_time";

    private static SharedPreferences getPrefs(Context context) {
        if (context == null) return null;
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            SharedPreferences encryptedPrefs = EncryptedSharedPreferences.create(
                    context,
                    ENCRYPTED_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            migratePlaintextPrefsIfNeeded(context, encryptedPrefs);
            return encryptedPrefs;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences, falling back to plain SharedPreferences", e);
            return context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    private static void migratePlaintextPrefsIfNeeded(Context context, SharedPreferences encryptedPrefs) {
        try {
            SharedPreferences oldPrefs = context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE);
            if (oldPrefs != null && oldPrefs.contains(KEY_BOT_TOKEN)) {
                String oldToken = oldPrefs.getString(KEY_BOT_TOKEN, "");
                long oldTime = oldPrefs.getLong(KEY_TOKEN_SAVED_TIME, 0);
                if (oldToken != null && !oldToken.trim().isEmpty()) {
                    encryptedPrefs.edit()
                            .putString(KEY_BOT_TOKEN, oldToken.trim())
                            .putLong(KEY_TOKEN_SAVED_TIME, oldTime)
                            .apply();
                }
                oldPrefs.edit().clear().apply();
                Log.i(TAG, "Successfully migrated bot token to EncryptedSharedPreferences.");
            }
        } catch (Exception e) {
            Log.w(TAG, "Migration from legacy plaintext SharedPreferences failed", e);
        }
    }

    /**
     * Returns the stored bot token, or empty string if not set.
     */
    public static String getBotToken(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs == null) return "";
        return prefs.getString(KEY_BOT_TOKEN, "").trim();
    }

    /**
     * Saves the bot token to SharedPreferences.
     * @return true if save was successful and token is valid, false otherwise
     */
    public static boolean saveBotToken(Context context, String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = token.trim();
        
        if (!isValidTokenFormat(trimmed)) {
            return false;
        }
        
        SharedPreferences prefs = getPrefs(context);
        if (prefs == null) return false;

        prefs.edit()
            .putString(KEY_BOT_TOKEN, trimmed)
            .putLong(KEY_TOKEN_SAVED_TIME, System.currentTimeMillis())
            .apply();
        return true;
    }

    /**
     * Clears the stored bot token.
     */
    public static void clearBotToken(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs != null) {
            prefs.edit().remove(KEY_BOT_TOKEN).remove(KEY_TOKEN_SAVED_TIME).apply();
        }
        try {
            SharedPreferences oldPrefs = context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE);
            if (oldPrefs != null) {
                oldPrefs.edit().remove(KEY_BOT_TOKEN).remove(KEY_TOKEN_SAVED_TIME).apply();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Checks if a bot token is saved.
     */
    public static boolean isBotTokenSet(Context context) {
        return !getBotToken(context).isEmpty();
    }

    /**
     * Basic validation of bot token format.
     * Expected format: "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11"
     */
    public static boolean isValidTokenFormat(String token) {
        if (token == null) return false;
        if (!token.contains(":")) {
            return false;
        }
        
        String[] parts = token.split(":", 2);
        if (parts.length != 2) {
            return false;
        }
        
        try {
            Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return false;
        }
        
        return parts[1].length() >= 10;
    }

    /**
     * Returns a masked version of the token for display (shows only last 8 chars).
     */
    public static String getMaskedToken(Context context) {
        String token = getBotToken(context);
        if (token.isEmpty()) return "";
        
        if (token.length() <= 8) {
            return "****" + token;
        }
        return "..." + token.substring(token.length() - 8);
    }
}
