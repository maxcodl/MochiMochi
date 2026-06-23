package com.kawai.mochi;

import android.content.Context;
import android.content.DialogInterface;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Dialog for requesting Telegram Bot Token from the user.
 * Can be used on first conversion or from settings.
 */
public class BotTokenInputDialog {

    public interface OnTokenInputListener {
        void onTokenSaved(String token);
        void onTokenInputCancelled();
    }

    /**
     * Shows the bot token input dialog.
     */
    public static void show(Context context, OnTokenInputListener listener) {
        show(context, null, listener);
    }

    /**
     * Shows the bot token input dialog with optional subtitle message.
     */
    public static void show(Context context, String subtitleMessage, OnTokenInputListener listener) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 0);

        // Title explanation
        TextView titleView = new TextView(context);
        titleView.setText(R.string.telegram_bot_token_title);
        titleView.setTextSize(16);
        titleView.setTextColor(context.getResources().getColor(android.R.color.black, context.getTheme()));
        layout.addView(titleView);

        // Subtitle
        if (subtitleMessage != null && !subtitleMessage.isEmpty()) {
            TextView subtitleView = new TextView(context);
            subtitleView.setText(subtitleMessage);
            subtitleView.setTextSize(12);
            subtitleView.setTextColor(context.getResources().getColor(android.R.color.darker_gray, context.getTheme()));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.topMargin = 8;
            subtitleView.setLayoutParams(params);
            layout.addView(subtitleView);
        }

        // Description text
        TextView descriptionView = new TextView(context);
        descriptionView.setText(R.string.telegram_bot_token_description);
        descriptionView.setTextSize(12);
        descriptionView.setTextColor(context.getResources().getColor(android.R.color.darker_gray, context.getTheme()));
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        descParams.topMargin = 16;
        descriptionView.setLayoutParams(descParams);
        layout.addView(descriptionView);

        // Input field
        EditText tokenInput = new EditText(context);
        tokenInput.setHint(R.string.telegram_bot_token_hint);
        tokenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        inputParams.topMargin = 24;
        tokenInput.setLayoutParams(inputParams);
        layout.addView(tokenInput);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setView(layout)
                .setPositiveButton(R.string.save_button, (dialog, which) -> {
                    String token = tokenInput.getText().toString().trim();
                    if (BotTokenManager.saveBotToken(context, token)) {
                        if (listener != null) listener.onTokenSaved(token);
                    } else {
                        // Show error and retry
                        showInvalidTokenError(context, () -> show(context, subtitleMessage, listener));
                    }
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    if (listener != null) listener.onTokenInputCancelled();
                    dialog.dismiss();
                });

        AlertDialog dialog = builder.show();
        
        // Auto-focus the input field
        tokenInput.requestFocus();
    }

    /**
     * Shows an error dialog for invalid token format.
     */
    private static void showInvalidTokenError(Context context, Runnable onRetry) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.telegram_bot_token_invalid_title)
                .setMessage(R.string.telegram_bot_token_invalid_message)
                .setPositiveButton(R.string.retry, (dialog, which) -> {
                    if (onRetry != null) onRetry.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
