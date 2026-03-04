/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.kawai.mochii;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.color.DynamicColors;

public abstract class BaseActivity extends AppCompatActivity {

    // Track the theme state when this activity instance was created so we can
    // detect changes while the activity was in the back stack.
    private boolean mCreatedWithAmoled;
    private int mCreatedWithThemeMode;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        android.content.SharedPreferences prefs = getSharedPreferences("mochii_prefs", MODE_PRIVATE);
        boolean isAmoled = prefs.getBoolean("theme_amoled", false);
        int themeMode = prefs.getInt("theme_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        // Remember what theme we were created with
        mCreatedWithAmoled = isAmoled;
        mCreatedWithThemeMode = themeMode;

        if (isAmoled) {
            // 1. Set the AMOLED base theme (black surfaces, DayNight parent)
            setTheme(R.style.AppTheme_Amoled);
            // 2. Apply Monet dynamic colors (same as normal mode — no overlay here)
            DynamicColors.applyToActivityIfAvailable(this);
            // 3. Re-apply the AMOLED surface overrides ON TOP of Monet so surfaces stay black
            //    while Monet accent colors (primary, secondary, etc.) remain intact.
            getTheme().applyStyle(R.style.ThemeOverlay_Amoled, true);
        } else {
            // Normal mode: let DynamicColors freely apply all Monet attributes.
            DynamicColors.applyToActivityIfAvailable(this);
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // When returning from the back stack, check if the theme changed.
        // AppCompatDelegate handles Light/Dark propagation automatically, but AMOLED
        // is a custom pref — activities that were paused won't know about it.
        android.content.SharedPreferences prefs = getSharedPreferences("mochii_prefs", MODE_PRIVATE);
        boolean isAmoled = prefs.getBoolean("theme_amoled", false);
        int themeMode = prefs.getInt("theme_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (isAmoled != mCreatedWithAmoled || themeMode != mCreatedWithThemeMode) {
            recreate();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public static final class MessageDialogFragment extends DialogFragment {
        private static final String ARG_TITLE_ID = "title_id";
        private static final String ARG_MESSAGE = "message";

        static DialogFragment newInstance(@StringRes int titleId, String message) {
            DialogFragment fragment = new MessageDialogFragment();
            Bundle arguments = new Bundle();
            arguments.putInt(ARG_TITLE_ID, titleId);
            arguments.putString(ARG_MESSAGE, message);
            fragment.setArguments(arguments);
            return fragment;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            @StringRes final int title = getArguments().getInt(ARG_TITLE_ID);
            String message = getArguments().getString(ARG_MESSAGE);

            AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity())
                    .setMessage(message)
                    .setCancelable(true)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> dismiss());

            if (title != 0) {
                dialogBuilder.setTitle(title);
            }
            return dialogBuilder.create();
        }
    }
}
