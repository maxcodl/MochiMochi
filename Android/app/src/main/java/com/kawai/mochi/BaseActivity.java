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
import com.kawai.mochi.R;

public abstract class BaseActivity extends AppCompatActivity {

    protected boolean mCreatedWithAmoled;
    protected int mCreatedWithThemeMode;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        android.content.SharedPreferences prefs = getSharedPreferences("mochii_prefs", MODE_PRIVATE);
        boolean isAmoled = prefs.getBoolean("theme_amoled", false);
        int themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        mCreatedWithAmoled = isAmoled;
        mCreatedWithThemeMode = themeMode;

        if (isAmoled) {
            setTheme(R.style.AppTheme_Amoled);
            DynamicColors.applyToActivityIfAvailable(this);
            getTheme().applyStyle(R.style.ThemeOverlay_Amoled, true);
        } else {
            DynamicColors.applyToActivityIfAvailable(this);
        }
        super.onCreate(savedInstanceState);

        // Enable edge-to-edge drawing globally. 
        // Specific layouts must handle padding via android:fitsSystemWindows="true" 
        // or WindowInsets listeners.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.content.SharedPreferences prefs = getSharedPreferences("mochii_prefs", MODE_PRIVATE);
        boolean isAmoled = prefs.getBoolean("theme_amoled", false);
        int themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        
        // Only recreate if we're not mid-recreation and values actually differ
        if (isAmoled != mCreatedWithAmoled || themeMode != mCreatedWithThemeMode) {
            mCreatedWithAmoled = isAmoled;
            mCreatedWithThemeMode = themeMode;
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
        public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
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
