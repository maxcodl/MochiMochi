package com.kawai.mochi;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Bottom sheet that offers two import paths:
 *   1. Import from file (.wasticker) — existing behaviour
 *   2. Import from Telegram URL — new feature
 */
public class ImportChoiceBottomSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onImportFromFile();
        void onImportFromTelegram();
    }

    private Listener listener;

    public static ImportChoiceBottomSheet newInstance() {
        return new ImportChoiceBottomSheet();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_import_choice, container, false);

        view.findViewById(R.id.option_import_file).setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onImportFromFile();
        });

        view.findViewById(R.id.option_import_telegram).setOnClickListener(v -> {
            dismiss();
            if (listener != null) listener.onImportFromTelegram();
        });

        return view;
    }
}
