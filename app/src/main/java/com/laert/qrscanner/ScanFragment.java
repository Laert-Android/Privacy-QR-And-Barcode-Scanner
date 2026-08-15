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
import androidx.camera.core.Camera;
import android.widget.ImageButton;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.MeteringPoint;
import androidx.camera.view.PreviewView;
import android.view.MotionEvent;
import java.util.concurrent.TimeUnit;
import android.view.ScaleGestureDetector;
import android.widget.ImageButton;

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
    private Camera camera;
    private ScaleGestureDetector scaleGestureDetector;
    private float currentZoomRatio = 1f;
    private ImageButton btnFlash;
    private boolean torchEnabled = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_scan, container, false);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            try {
                Uri imageUri = data.getData();
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(
                        requireActivity().getContentResolver(), imageUri);

                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

                RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
                BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));

                Result result = reader.decode(binaryBitmap);
                if (result != null) {
                    lastResult = result.getText();
                    String format = result.getBarcodeFormat().toString();
                    HistoryManager.save(requireContext(), lastResult, format);
                    requireActivity().runOnUiThread(() -> handleResult(lastResult));
                }
            } catch (NotFoundException e) {
                Toast.makeText(requireContext(), "No QR code found in image",
                        Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Error reading image",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ImageButton btnGallery = view.findViewById(R.id.btnGallery);
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

        btnFlash = view.findViewById(R.id.btnFlash);

        if (!requireContext().getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {

            btnFlash.setVisibility(View.GONE);

        } else {

            btnFlash.setOnClickListener(v -> {

                if (camera == null)
                    return;

                torchEnabled = !torchEnabled;

                camera.getCameraControl().enableTorch(torchEnabled);

                btnFlash.setImageResource(
                        torchEnabled ?
                                R.drawable.ic_flash_on :
                                R.drawable.ic_flash_off
                );
            });
        }

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
                camera = cameraProvider.bindToLifecycle(
                        getViewLifecycleOwner(),
                        cameraSelector,
                        preview,
                        imageAnalysis
                );

                scaleGestureDetector = new ScaleGestureDetector(
                        requireContext(),
                        new ScaleGestureDetector.SimpleOnScaleGestureListener() {

                            @Override
                            public boolean onScale(ScaleGestureDetector detector) {

                                if (camera == null)
                                    return true;

                                float scale = detector.getScaleFactor();

                                currentZoomRatio *= scale;

                                float maxZoom = camera.getCameraInfo()
                                        .getZoomState()
                                        .getValue()
                                        .getMaxZoomRatio();

                                currentZoomRatio = Math.max(
                                        1f,
                                        Math.min(currentZoomRatio, maxZoom)
                                );

                                camera.getCameraControl()
                                        .setZoomRatio(currentZoomRatio);

                                return true;
                            }
                        }
                );

                previewView.setOnTouchListener((view, event) -> {

                    // Zoom me dy gishta
                    if (scaleGestureDetector != null) {
                        scaleGestureDetector.onTouchEvent(event);
                    }

                    // Tap to Focus vetëm me një prekje
                    if (event.getPointerCount() == 1 &&
                            event.getAction() == MotionEvent.ACTION_DOWN) {

                        if (camera != null) {

                            MeteringPoint point =
                                    previewView.getMeteringPointFactory()
                                            .createPoint(
                                                    event.getX(),
                                                    event.getY()
                                            );

                            FocusMeteringAction action =
                                    new FocusMeteringAction.Builder(point)
                                            .setAutoCancelDuration(
                                                    3,
                                                    TimeUnit.SECONDS
                                            )
                                            .build();

                            camera.getCameraControl()
                                    .startFocusAndMetering(action);
                        }
                    }

                    return true;
                });

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(requireContext()));
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
            if (camera != null) {
                camera.getCameraControl().enableTorch(false);
            }
            cameraExecutor.shutdown();
        }
    }
}