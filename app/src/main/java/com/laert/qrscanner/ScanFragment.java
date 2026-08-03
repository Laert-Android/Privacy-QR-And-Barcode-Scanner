package com.laert.qrscanner;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.app.Activity;
import android.graphics.Bitmap;
import android.provider.MediaStore;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import android.graphics.Matrix;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;

public class ScanFragment extends Fragment {

    private static final int PICK_IMAGE_REQUEST = 200;
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private PreviewView previewView;
    private TextView tvResult;
    private LinearLayout bottomSheet;
    private Button btnCopy, btnShare, btnScanAgain, btnOpen;
    private View scanLine;
    private ExecutorService cameraExecutor;
    private boolean isScanned = false;
    private String lastResult = "";
    private MultiFormatReader reader;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_scan, container, false);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_IMAGE_REQUEST
                && resultCode == Activity.RESULT_OK
                && data != null
                && data.getData() != null) {

            try {
                Uri imageUri = data.getData();

                Bitmap bitmap = MediaStore.Images.Media.getBitmap(
                        requireActivity().getContentResolver(),
                        imageUri);

                int width = bitmap.getWidth();
                int height = bitmap.getHeight();

                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

                RGBLuminanceSource source =
                        new RGBLuminanceSource(width, height, pixels);

                Result result = null;

                BinaryBitmap[] bitmaps = new BinaryBitmap[]{
                        new BinaryBitmap(new HybridBinarizer(source)),
                        new BinaryBitmap(new GlobalHistogramBinarizer(source))
                };

                for (BinaryBitmap bitmapTry : bitmaps) {
                    try {
                        reader.reset();
                        result = reader.decodeWithState(bitmapTry);

                        if (result != null) {
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }

                reader.reset();

                if (result != null) {
                    lastResult = result.getText();

                    String format = result.getBarcodeFormat().toString();

                    HistoryManager.save(
                            requireContext(),
                            lastResult,
                            format
                    );

                    requireActivity().runOnUiThread(() ->
                            handleResult(lastResult));
                } else {
                    Toast.makeText(
                            requireContext(),
                            "No QR code found in image",
                            Toast.LENGTH_SHORT
                    ).show();
                }

            } catch (Exception e) {
                Toast.makeText(
                        requireContext(),
                        "Error reading image",
                        Toast.LENGTH_SHORT
                ).show();

                Log.e("QRScanner", "Gallery scan error", e);
            }
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Button btnGallery = view.findViewById(R.id.btnGallery);
        btnGallery.setOnClickListener(v -> scanFromGallery());

        previewView = view.findViewById(R.id.previewView);
        tvResult = view.findViewById(R.id.tvResult);
        bottomSheet = view.findViewById(R.id.bottomSheet);
        btnCopy = view.findViewById(R.id.btnCopy);
        btnShare = view.findViewById(R.id.btnShare);
        btnScanAgain = view.findViewById(R.id.btnScanAgain);
        btnOpen = view.findViewById(R.id.btnOpen);
        scanLine = view.findViewById(R.id.scanLine);
        cameraExecutor = Executors.newSingleThreadExecutor();

        setupZXing();
        startScanAnimation();

        btnCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) requireActivity()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("QR Result", lastResult);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(requireContext(), "Copied!", Toast.LENGTH_SHORT).show();
        });

        btnShare.setOnClickListener(v -> {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, lastResult);
            startActivity(Intent.createChooser(shareIntent, "Share via"));
        });

        btnScanAgain.setOnClickListener(v -> {
            isScanned = false;
            btnOpen.setVisibility(View.GONE);
            bottomSheet.setVisibility(View.GONE);
        });

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(requireActivity(),
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        }
    }

    private void setupZXing() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(
                BarcodeFormat.QR_CODE,
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A,
                BarcodeFormat.UPC_E,
                BarcodeFormat.CODE_39,
                BarcodeFormat.CODE_93,
                BarcodeFormat.CODE_128,
                BarcodeFormat.ITF,
                BarcodeFormat.PDF_417,
                BarcodeFormat.AZTEC,
                BarcodeFormat.DATA_MATRIX,
                BarcodeFormat.CODABAR,
                BarcodeFormat.MAXICODE
        ));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader = new MultiFormatReader();
        reader.setHints(hints);
    }

    private void scanFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    private void startScanAnimation() {
        android.animation.ObjectAnimator animator = android.animation.ObjectAnimator.ofFloat(
                scanLine, "translationY", -125f, 125f);
        animator.setDuration(1500);
        animator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        animator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.start();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(requireContext());

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, (ImageProxy imageProxy) -> {
                    if (isScanned) {
                        imageProxy.close();
                        return;
                    }

                    @SuppressWarnings("UnsafeOptInUsageError")
                    android.media.Image mediaImage = imageProxy.getImage();

                    if (mediaImage != null) {
                        try {
                            android.media.Image.Plane[] planes = mediaImage.getPlanes();
                            ByteBuffer yBuffer = planes[0].getBuffer();
                            ByteBuffer uBuffer = planes[1].getBuffer();
                            ByteBuffer vBuffer = planes[2].getBuffer();

                            int ySize = yBuffer.remaining();
                            int uSize = uBuffer.remaining();
                            int vSize = vBuffer.remaining();

                            byte[] nv21 = new byte[ySize + uSize + vSize];
                            yBuffer.get(nv21, 0, ySize);
                            vBuffer.get(nv21, ySize, vSize);
                            uBuffer.get(nv21, ySize + vSize, uSize);

                            int width = mediaImage.getWidth();
                            int height = mediaImage.getHeight();
                            int rotation = imageProxy.getImageInfo().getRotationDegrees();

                            Result result = null;
                            int[] rotations = {rotation, 0, 90, 180, 270};
                            for (int rot : rotations) {
                                try {
                                    PlanarYUVLuminanceSource source;
                                    if (rot == 90 || rot == 270) {
                                        byte[] rotatedData = new byte[nv21.length];
                                        for (int y = 0; y < height; y++) {
                                            for (int x = 0; x < width; x++) {
                                                if (rot == 90) {
                                                    rotatedData[x * height + height - y - 1] = nv21[y * width + x];
                                                } else {
                                                    rotatedData[(width - x - 1) * height + y] = nv21[y * width + x];
                                                }
                                            }
                                        }
                                        source = new PlanarYUVLuminanceSource(
                                                rotatedData, height, width, 0, 0, height, width, false);
                                    } else {
                                        source = new PlanarYUVLuminanceSource(
                                                nv21, width, height, 0, 0, width, height, false);
                                    }
                                    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                                    result = reader.decode(bitmap);
                                    if (result != null) break;
                                } catch (NotFoundException e) {
                                    // Try next rotation
                                } finally {
                                    reader.reset();
                                }
                            }

                            if (result != null && !isScanned) {
                                isScanned = true;
                                lastResult = result.getText();
                                String format = result.getBarcodeFormat().toString();
                                HistoryManager.save(requireContext(), lastResult, format);
                                requireActivity().runOnUiThread(() -> handleResult(lastResult));
                            }
                        } catch (Exception e) {
                            Log.e("QRScanner", "Error: " + e.getMessage());
                        }
                    }
                    imageProxy.close();
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector,
                        preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private Result decodeBitmap(Bitmap bitmap) {

        float[] scales = {1.0f, 1.5f, 2.0f, 3.0f};

        for (float scale : scales) {

            Bitmap scaled = scaleBitmap(bitmap, scale);

            Bitmap[] images = {
                    scaled,
                    enhanceContrast(scaled)
            };

            for (Bitmap image : images) {

                if (image == null)
                    continue;

                Bitmap[] rotations = {
                        image,
                        rotateBitmap(image, 90),
                        rotateBitmap(image, 180),
                        rotateBitmap(image, 270)
                };

                for (Bitmap rotated : rotations) {

                    if (rotated == null)
                        continue;

                    int width = rotated.getWidth();
                    int height = rotated.getHeight();

                    int[] pixels = new int[width * height];
                    rotated.getPixels(pixels, 0, width, 0, 0, width, height);

                    RGBLuminanceSource source =
                            new RGBLuminanceSource(width, height, pixels);

                    BinaryBitmap[] attempts = {
                            new BinaryBitmap(new HybridBinarizer(source)),
                            new BinaryBitmap(new GlobalHistogramBinarizer(source))
                    };

                    for (BinaryBitmap binaryBitmap : attempts) {

                        try {

                            reader.reset();

                            Result result =
                                    reader.decodeWithState(binaryBitmap);

                            if (result != null) {
                                return result;
                            }

                        } catch (Exception ignored) {

                        } finally {

                            reader.reset();

                        }
                    }
                }
            }
        }

        return null;
    }

    private Bitmap rotateBitmap(Bitmap bitmap, float angle) {

        if (bitmap == null) {
            return null;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(angle);

        try {
            return Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    matrix,
                    true
            );
        } catch (OutOfMemoryError e) {
            Log.e("QRScanner", "Failed to rotate bitmap", e);
            return bitmap;
        }
    }

    private Bitmap scaleBitmap(Bitmap bitmap, float scale) {

        if (bitmap == null) {
            return null;
        }

        if (scale == 1.0f) {
            return bitmap;
        }

        int newWidth = Math.max(1, Math.round(bitmap.getWidth() * scale));
        int newHeight = Math.max(1, Math.round(bitmap.getHeight() * scale));

        try {
            return Bitmap.createScaledBitmap(
                    bitmap,
                    newWidth,
                    newHeight,
                    true
            );
        } catch (OutOfMemoryError e) {
            Log.e("QRScanner", "Failed to scale bitmap", e);
            return bitmap;
        }
    }

    private Bitmap enhanceContrast(Bitmap bitmap) {

        if (bitmap == null) {
            return null;
        }

        Bitmap output = Bitmap.createBitmap(
                bitmap.getWidth(),
                bitmap.getHeight(),
                Bitmap.Config.ARGB_8888
        );

        Canvas canvas = new Canvas(output);

        Paint paint = new Paint();

        ColorMatrix colorMatrix = new ColorMatrix(new float[]{

                1.3f, 0,    0,    0,    15,
                0,    1.3f, 0,    0,    15,
                0,    0,    1.3f, 0,    15,
                0,    0,    0,    1,    0

        });

        paint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));

        canvas.drawBitmap(bitmap, 0, 0, paint);

        return output;
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private void handleResult(String result) {
        tvResult.setText(result);
        bottomSheet.setVisibility(View.VISIBLE);
        vibrate();

        if (result.startsWith("WIFI:")) {
            btnOpen.setVisibility(View.VISIBLE);
            btnOpen.setText("Connect WiFi");
            btnOpen.setOnClickListener(v -> {
                Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                startActivity(intent);
            });
        } else if (result.startsWith("tel:")) {
            btnOpen.setVisibility(View.VISIBLE);
            btnOpen.setText("Call");
            btnOpen.setOnClickListener(v -> startActivity(
                    new Intent(Intent.ACTION_DIAL, Uri.parse(result))));
        } else if (result.startsWith("smsto:") || result.startsWith("sms:")
                || result.startsWith("SMSTO:") || result.startsWith("SMS:")) {
            btnOpen.setVisibility(View.VISIBLE);
            btnOpen.setText("Send SMS");
            btnOpen.setOnClickListener(v -> {
                String sms = result.replaceFirst("(?i)smsto:|sms:", "");
                String[] parts = sms.split(":");
                String number = parts[0];
                String message = parts.length > 1 ? parts[1] : "";
                Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number));
                intent.putExtra("sms_body", message);
                startActivity(intent);
            });
        } else if (result.startsWith("mailto:") || result.startsWith("MAILTO:")
                || result.contains("@")) {
            btnOpen.setVisibility(View.VISIBLE);
            btnOpen.setText("Send Email");
            btnOpen.setOnClickListener(v -> {
                String mailto = result.startsWith("mailto:") ? result : "mailto:" + result;
                startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse(mailto)));
            });
        } else if (result.startsWith("geo:")) {
            btnOpen.setVisibility(View.VISIBLE);
            btnOpen.setText("Open Map");
            btnOpen.setOnClickListener(v -> startActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse(result))));
        } else if (result.startsWith("MECARD:") || result.startsWith("BEGIN:VCARD")) {
            btnOpen.setVisibility(View.VISIBLE);
            btnOpen.setText("Add Contact");
            btnOpen.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_INSERT);
                intent.setType(ContactsContract.Contacts.CONTENT_TYPE);
                startActivity(intent);
            });
        } else if (result.startsWith("BEGIN:VEVENT")) {
            btnOpen.setVisibility(View.VISIBLE);
            btnOpen.setText("Add Event");
            btnOpen.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_INSERT);
                intent.setData(CalendarContract.Events.CONTENT_URI);
                startActivity(intent);
            });
        } else if (result.startsWith("http://") || result.startsWith("https://")
                || result.contains(".")) {
            btnOpen.setVisibility(View.VISIBLE);
            btnOpen.setText("Open");
            btnOpen.setOnClickListener(v -> {
                try {
                    String url = result;
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://" + url;
                    }
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Cannot open this link",
                            Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            btnOpen.setVisibility(View.GONE);
        }
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private void vibrate() {
        Vibrator vibrator = (Vibrator) requireActivity().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(100,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(100);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
