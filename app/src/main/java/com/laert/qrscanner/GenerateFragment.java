package com.laert.qrscanner;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.google.zxing.common.BitMatrix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class GenerateFragment extends Fragment {

    private EditText etInput;
    private ImageView ivQRCode;
    private Spinner spinnerFormat;
    private Spinner spinnerType;
    private LinearLayout layoutWifi;
    private EditText etSsid, etPassword;
    private Spinner spinnerSecurity;
    private Button btnGenerate, btnShare, btnSave;

    private Bitmap generatedBitmap;
    private BarcodeGenerator.Format currentFormat = BarcodeGenerator.Format.QR;
    private BarcodeGenerator.Format generatedFormat = BarcodeGenerator.Format.QR;

    private final ActivityResultLauncher<String> savePngLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("image/png"), this::writePngTo);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_generate, container, false);
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

        etInput = view.findViewById(R.id.etInput);
        ivQRCode = view.findViewById(R.id.ivQRCode);
        spinnerFormat = view.findViewById(R.id.spinnerFormat);
        spinnerType = view.findViewById(R.id.spinnerType);
        layoutWifi = view.findViewById(R.id.layoutWifi);
        etSsid = view.findViewById(R.id.etSsid);
        etPassword = view.findViewById(R.id.etPassword);
        spinnerSecurity = view.findViewById(R.id.spinnerSecurity);
        btnGenerate = view.findViewById(R.id.btnGenerate);
        btnShare = view.findViewById(R.id.btnShare);
        btnSave = view.findViewById(R.id.btnSave);

        ArrayAdapter<BarcodeGenerator.Format> formatAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, BarcodeGenerator.Format.values());
        formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFormat.setAdapter(formatAdapter);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.type_text), getString(R.string.type_url),
                        getString(R.string.type_wifi), getString(R.string.type_phone),
                        getString(R.string.type_email), getString(R.string.type_sms)});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerType.setAdapter(adapter);

        ArrayAdapter<String> secAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{"WPA", "WEP", getString(R.string.security_none)});
        secAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSecurity.setAdapter(secAdapter);

        spinnerFormat.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                currentFormat = BarcodeGenerator.Format.values()[position];
                updateInputUi();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                updateInputUi();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnGenerate.setOnClickListener(v -> generate());
        btnShare.setOnClickListener(v -> sharePng());
        btnSave.setOnClickListener(v -> savePng());

        showResult(false);
    }

    private void updateInputUi() {
        boolean flexible = currentFormat.supportsContentTypes;
        spinnerType.setVisibility(flexible ? View.VISIBLE : View.GONE);
        int typePos = flexible ? spinnerType.getSelectedItemPosition() : 0;
        boolean wifi = flexible && typePos == 2;

        layoutWifi.setVisibility(wifi ? View.VISIBLE : View.GONE);
        etInput.setVisibility(wifi ? View.GONE : View.VISIBLE);
        if (!wifi) {
            etInput.setHint(flexible ? hintForType(typePos) : hintForFormat(currentFormat));
            int wanted = currentFormat.numericOnly
                    ? InputType.TYPE_CLASS_NUMBER
                    : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            if (etInput.getInputType() != wanted) etInput.setInputType(wanted);
        }
    }

    private String hintForType(int position) {
        switch (position) {
            case 1: return "https://example.com";
            case 3: return "+1234567890";
            case 4: return "email@example.com";
            case 5: return "+1234567890";
            default: return hintForFormat(currentFormat);
        }
    }

    private String hintForFormat(BarcodeGenerator.Format f) {
        switch (f) {
            case CODE_128: return getString(R.string.hint_code128);
            case CODE_39: return getString(R.string.hint_code39);
            case EAN_13: return getString(R.string.hint_ean13);
            case EAN_8: return getString(R.string.hint_ean8);
            case UPC_A: return getString(R.string.hint_upca);
            case UPC_E: return getString(R.string.hint_upce);
            case ITF: return getString(R.string.hint_itf);
            case CODABAR: return getString(R.string.hint_codabar);
            default: return getString(R.string.hint_enter_text);
        }
    }

    private String problemText(BarcodeGenerator.CodeException e) {
        int res;
        switch (e.problem) {
            case EMPTY: res = R.string.please_enter_content; break;
            case DIGITS_ONLY: res = R.string.err_digits_only; break;
            case DIGIT_COUNT: res = R.string.err_digit_count; break;
            case UPCE_START: res = R.string.err_upce_start; break;
            case ITF_EVEN: res = R.string.err_itf_even; break;
            case TOO_LONG: res = R.string.err_too_long; break;
            case ASCII_ONLY: res = R.string.err_ascii_only; break;
            case CHECK_DIGIT: res = R.string.err_check_digit; break;
            case UNSUPPORTED_CHARS: res = R.string.err_unsupported_chars; break;
            case TOO_MUCH: res = R.string.err_too_much; break;
            default: res = R.string.err_cannot_create; break;
        }
        return getString(res, e.args);
    }

    private void showResult(boolean visible) {
        ivQRCode.setVisibility(visible ? View.VISIBLE : View.GONE);
        btnShare.setVisibility(visible ? View.VISIBLE : View.GONE);
        btnSave.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void generate() {
        String content = buildContent();
        if (content.isEmpty()) {
            Toast.makeText(requireContext(), R.string.please_enter_content, Toast.LENGTH_SHORT).show();
            return;
        }

        BarcodeGenerator.Format format = currentFormat;
        try {
            BitMatrix matrix = BarcodeGenerator.encode(format, content);
            generatedBitmap = toBitmap(matrix);
            generatedFormat = format;
            ivQRCode.setImageBitmap(generatedBitmap);
            showResult(true);
        } catch (BarcodeGenerator.CodeException e) {
            generatedBitmap = null;
            showResult(false);
            Toast.makeText(requireContext(), problemText(e), Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException e) {
            generatedBitmap = null;
            showResult(false);
            Toast.makeText(requireContext(), R.string.err_too_much, Toast.LENGTH_LONG).show();
        }
    }

    private static Bitmap toBitmap(BitMatrix matrix) {
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int offset = y * w;
            for (int x = 0; x < w; x++) {
                pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }

    private String buildContent() {
        int type = currentFormat.supportsContentTypes ? spinnerType.getSelectedItemPosition() : 0;
        String input = etInput.getText().toString().trim();
        switch (type) {
            case 0:
                return input;
            case 1: {
                if (input.isEmpty()) return "";
                String lower = input.toLowerCase(Locale.ROOT);
                return (lower.startsWith("http://") || lower.startsWith("https://"))
                        ? input : "https://" + input;
            }
            case 2: {
                String ssid = etSsid.getText().toString().trim();
                if (ssid.isEmpty()) return "";
                String password = etPassword.getText().toString().trim();
                int security = spinnerSecurity.getSelectedItemPosition();
                if (security == 2 || password.isEmpty()) {
                    return "WIFI:T:nopass;S:" + escapeWifi(ssid) + ";;";
                }
                return "WIFI:T:" + (security == 1 ? "WEP" : "WPA") + ";S:" + escapeWifi(ssid)
                        + ";P:" + escapeWifi(password) + ";;";
            }
            case 3: return input.isEmpty() ? "" : "tel:" + input;
            case 4: return input.isEmpty() ? "" : "mailto:" + input;
            case 5: return input.isEmpty() ? "" : "smsto:" + input;
            default: return "";
        }
    }

    private static String escapeWifi(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace(":", "\\:").replace("\"", "\\\"");
    }

    private String fileName() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        return generatedFormat.name().toLowerCase(Locale.ROOT) + "_" + stamp + ".png";
    }

    private void savePng() {
        if (generatedBitmap == null) return;
        savePngLauncher.launch(fileName());
    }

    private void writePngTo(@Nullable Uri uri) {
        if (uri == null || generatedBitmap == null || !isAdded()) return;
        try (OutputStream out = requireContext().getContentResolver().openOutputStream(uri)) {
            if (out == null || !generatedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("Could not write PNG");
            }
            Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_SHORT).show();
        } catch (IOException | RuntimeException e) {
            Toast.makeText(requireContext(), R.string.could_not_save, Toast.LENGTH_SHORT).show();
        }
    }

    private void sharePng() {
        if (generatedBitmap == null) return;
        try {
            File dir = new File(requireContext().getCacheDir(), "shared");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("No cache directory");
            File[] old = dir.listFiles();
            if (old != null) {
                for (File f : old) {
                    f.delete();
                }
            }
            File file = new File(dir, fileName());
            try (FileOutputStream out = new FileOutputStream(file)) {
                if (!generatedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IOException("Could not write PNG");
                }
            }
            Uri uri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider", file);

            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/png");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newRawUri("", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.share_code)));
        } catch (IOException | RuntimeException e) {
            Toast.makeText(requireContext(), R.string.error_sharing, Toast.LENGTH_SHORT).show();
        }
    }
}
