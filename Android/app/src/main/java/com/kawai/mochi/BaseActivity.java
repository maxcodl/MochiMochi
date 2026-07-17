/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.kawai.mochi;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.color.DynamicColors;

public abstract class BaseActivity extends AppCompatActivity {

    protected boolean mCreatedWithAmoled;
    protected int mCreatedWithThemeMode;
    protected boolean mCreatedWithPerfMode;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        android.content.SharedPreferences prefs = getSharedPreferences("mochi_prefs", MODE_PRIVATE);
        boolean isAmoled = prefs.getBoolean("theme_amoled", false);
        int themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        boolean perfMode = prefs.getBoolean("enable_animations", false);

        mCreatedWithAmoled = isAmoled;
        mCreatedWithThemeMode = themeMode;
        mCreatedWithPerfMode = perfMode;

        if (isAmoled) {
            setTheme(R.style.AppTheme_Amoled);
            DynamicColors.applyToActivityIfAvailable(this);
            getTheme().applyStyle(R.style.ThemeOverlay_Amoled, true);
        } else {
            DynamicColors.applyToActivityIfAvailable(this);
        }
        super.onCreate(savedInstanceState);

        // Enable edge-to-edge drawing globally. 
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.content.SharedPreferences prefs = getSharedPreferences("mochi_prefs", MODE_PRIVATE);
        boolean isAmoled = prefs.getBoolean("theme_amoled", false);
        int themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        boolean perfMode = prefs.getBoolean("enable_animations", false);
        
        // Only recreate if we're not mid-recreation and values actually differ
        if (isAmoled != mCreatedWithAmoled || themeMode != mCreatedWithThemeMode || perfMode != mCreatedWithPerfMode) {
            mCreatedWithAmoled = isAmoled;
            mCreatedWithThemeMode = themeMode;
            mCreatedWithPerfMode = perfMode;
            recreate();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
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
        public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
            final Bundle arguments = requireArguments();
            @StringRes final int title = arguments.getInt(ARG_TITLE_ID);
            final String message = arguments.getString(ARG_MESSAGE);

            AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(requireActivity());
            dialogBuilder.setMessage(message);
            dialogBuilder.setCancelable(true);
            dialogBuilder.setPositiveButton(android.R.string.ok, (dialog, which) -> dismiss());

            if (title != 0) {
                dialogBuilder.setTitle(title);
            }
            return dialogBuilder.create();
        }
    }
}
