package com.laert.qrscanner;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class GenerateFragment extends Fragment {

    private EditText etInput;
    private ImageView ivQRCode;
    private Spinner spinnerType;
    private LinearLayout layoutWifi;
    private EditText etSsid, etPassword;
    private Spinner spinnerSecurity;
    private Button btnGenerate, btnShare, btnSave;
    private Bitmap generatedBitmap;

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
        spinnerType = view.findViewById(R.id.spinnerType);
        layoutWifi = view.findViewById(R.id.layoutWifi);
        etSsid = view.findViewById(R.id.etSsid);
        etPassword = view.findViewById(R.id.etPassword);
        spinnerSecurity = view.findViewById(R.id.spinnerSecurity);
        btnGenerate = view.findViewById(R.id.btnGenerate);
        btnShare = view.findViewById(R.id.btnShare);
        btnSave = view.findViewById(R.id.btnSave);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{"Text", "URL", "WiFi", "Phone", "Email", "SMS"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerType.setAdapter(adapter);

        ArrayAdapter<String> secAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{"WPA", "WEP", "None"});
        secAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSecurity.setAdapter(secAdapter);

        spinnerType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 2) {
                    layoutWifi.setVisibility(View.VISIBLE);
                    etInput.setVisibility(View.GONE);
                } else {
                    layoutWifi.setVisibility(View.GONE);
                    etInput.setVisibility(View.VISIBLE);
                    updateHint(position);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnGenerate.setOnClickListener(v -> generateQR());
        btnShare.setOnClickListener(v -> shareQR());
        btnSave.setOnClickListener(v -> saveQR());

        btnShare.setVisibility(View.GONE);
        btnSave.setVisibility(View.GONE);
    }

    private void updateHint(int position) {
        switch (position) {
            case 0: etInput.setHint("Enter text"); break;
            case 1: etInput.setHint("https://example.com"); break;
            case 3: etInput.setHint("+1234567890"); break;
            case 4: etInput.setHint("email@example.com"); break;
            case 5: etInput.setHint("+1234567890"); break;
        }
    }

    private void generateQR() {
        String content = buildContent();
        if (content.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter content", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            generatedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    generatedBitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            ivQRCode.setImageBitmap(generatedBitmap);
            ivQRCode.setVisibility(View.VISIBLE);
            btnShare.setVisibility(View.VISIBLE);
            btnSave.setVisibility(View.VISIBLE);
        } catch (WriterException e) {
            Toast.makeText(requireContext(), "Error generating QR code", Toast.LENGTH_SHORT).show();
        }
    }

    private String buildContent() {
        int type = spinnerType.getSelectedItemPosition();
        switch (type) {
            case 0: return etInput.getText().toString().trim();
            case 1: {
                String url = etInput.getText().toString().trim();
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://" + url;
                }
                return url;
            }
            case 2: {
                String ssid = etSsid.getText().toString().trim();
                String password = etPassword.getText().toString().trim();
                String security = spinnerSecurity.getSelectedItem().toString();
                return "WIFI:T:" + security + ";S:" + ssid + ";P:" + password + ";;";
            }
            case 3: return "tel:" + etInput.getText().toString().trim();
            case 4: return "mailto:" + etInput.getText().toString().trim();
            case 5: return "smsto:" + etInput.getText().toString().trim();
            default: return "";
        }
    }

    private void shareQR() {
        if (generatedBitmap == null) return;
        try {
            String path = MediaStore.Images.Media.insertImage(
                    requireActivity().getContentResolver(), generatedBitmap, "QR Code", null);
            Uri uri = Uri.parse(path);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/jpeg");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            startActivity(Intent.createChooser(shareIntent, "Share QR Code"));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error sharing", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveQR() {
        if (generatedBitmap == null) return;
        try {
            String savedPath = MediaStore.Images.Media.insertImage(
                    requireActivity().getContentResolver(),
                    generatedBitmap, "QR_" + System.currentTimeMillis(), null);
            if (savedPath != null) {
                Toast.makeText(requireContext(), "Saved to gallery!", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error saving", Toast.LENGTH_SHORT).show();
        }
    }
}