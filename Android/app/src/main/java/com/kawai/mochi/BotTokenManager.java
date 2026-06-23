package com.kawai.mochi;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Manages Telegram Bot Token storage and retrieval from SharedPreferences.
 * Token is stored securely in SharedPreferences (can be upgraded to EncryptedSharedPreferences later).
 */
public class BotTokenManager {
    private static final String PREFS_NAME = "mochi_telegram_prefs";
    private static final String KEY_BOT_TOKEN = "telegram_bot_token";
    private static final String KEY_TOKEN_SAVED_TIME = "telegram_token_saved_time";

    /**
     * Returns the stored bot token, or empty string if not set.
     */
    public static String getBotToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
        
        // Basic validation: bot token format is "123456789:ABCdef..."
        if (!isValidTokenFormat(trimmed)) {
            return false;
        }
        
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_BOT_TOKEN).remove(KEY_TOKEN_SAVED_TIME).apply();
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
    private static boolean isValidTokenFormat(String token) {
        // Token must contain a colon separating ID and secret
        if (!token.contains(":")) {
            return false;
        }
        
        String[] parts = token.split(":", 2);
        if (parts.length != 2) {
            return false;
        }
        
        // ID part should be numeric
        try {
            Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return false;
        }
        
        // Secret should be at least 10 characters
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
