package com.laert.qrscanner;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.fragment.app.Fragment;

public class SettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int topInset = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), topInset, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        RadioGroup group = view.findViewById(R.id.rgLanguage);

        LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
        String language = current.isEmpty() || current.get(0) == null
                ? "" : current.get(0).getLanguage();
        if (language.equals("sq")) {
            group.check(R.id.rbAlbanian);
        } else if (language.equals("en")) {
            group.check(R.id.rbEnglish);
        } else {
            group.check(R.id.rbSystem);
        }

        group.setOnCheckedChangeListener((g, checkedId) -> {
            String tags = checkedId == R.id.rbAlbanian ? "sq"
                    : checkedId == R.id.rbEnglish ? "en" : "";
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags));
        });
    }
}
