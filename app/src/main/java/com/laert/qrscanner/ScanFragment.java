package com.laert.qrscanner;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipDescription;
import android.os.PersistableBundle;
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

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
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
    static final String ARG_SHARED_IMAGE = "shared_image_uri";

    static ScanFragment newInstanceForImage(Uri imageUri) {
        ScanFragment fragment = new ScanFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SHARED_IMAGE, imageUri.toString());
        fragment.setArguments(args);
        return fragment;
    }

    private final ActivityResultLauncher<String> cameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted && isAdded() && getView() != null && this.camera == null) startCamera();
            });
    private PreviewView previewView;
    private TextView tvResult;
    private TextView tvWarning;
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
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            scanImageUri(data.getData());
        }
    }

    private boolean scanImageUri(Uri imageUri) {
        try {
            Bitmap bitmap = decodeGalleryBitmap(imageUri);

            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));

            Result result = new MultiFormatReader().decode(binaryBitmap);
            lastResult = result.getText();
            HistoryManager.save(requireContext(), lastResult, result.getBarcodeFormat().toString());
            // Keep the camera from immediately replacing this result with its own scan.
            isScanned = true;
            handleResult(lastResult);
            return true;
        } catch (NotFoundException e) {
            Toast.makeText(requireContext(), R.string.no_qr_found, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.error_reading_image, Toast.LENGTH_SHORT).show();
        }
        return false;
    }

    private void startCameraOrRequestPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ImageButton btnGallery = view.findViewById(R.id.btnGallery);
        btnGallery.setOnClickListener(v -> scanFromGallery());
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int topInset = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            int baseMargin = (int) (16 * v.getResources().getDisplayMetrics().density);
            ImageButton gallery = v.findViewById(R.id.btnGallery);
            ViewGroup.MarginLayoutParams galleryParams =
                    (ViewGroup.MarginLayoutParams) gallery.getLayoutParams();
            galleryParams.topMargin = baseMargin + topInset;
            gallery.setLayoutParams(galleryParams);
            ImageButton flash = v.findViewById(R.id.btnFlash);
            ViewGroup.MarginLayoutParams flashParams =
                    (ViewGroup.MarginLayoutParams) flash.getLayoutParams();
            flashParams.topMargin = baseMargin + topInset;
            flash.setLayoutParams(flashParams);
            return insets;
        });

        previewView = view.findViewById(R.id.previewView);
        tvResult = view.findViewById(R.id.tvResult);
        tvWarning = view.findViewById(R.id.tvWarning);
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
            Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show();
        });

        btnShare.setOnClickListener(v -> {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, lastResult);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_via)));
        });

        btnScanAgain.setOnClickListener(v -> {
            isScanned = false;
            btnOpen.setVisibility(View.GONE);
            tvWarning.setVisibility(View.GONE);
            bottomSheet.setVisibility(View.GONE);
            if (camera == null) startCameraOrRequestPermission();
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

        Bundle args = getArguments();
        String sharedImage = args == null ? null : args.getString(ARG_SHARED_IMAGE);
        if (savedInstanceState == null && sharedImage != null) {
            if (!scanImageUri(Uri.parse(sharedImage))) startCameraOrRequestPermission();
        } else {
            startCameraOrRequestPermission();
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

    private static final int MAX_GALLERY_DIMENSION = 4096;

    private Bitmap decodeGalleryBitmap(Uri imageUri) throws java.io.IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.graphics.ImageDecoder.Source src = android.graphics.ImageDecoder.createSource(
                    requireActivity().getContentResolver(), imageUri);
            return android.graphics.ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
                decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth();
                int h = info.getSize().getHeight();
                int longest = Math.max(w, h);
                if (longest > MAX_GALLERY_DIMENSION) {
                    float scale = MAX_GALLERY_DIMENSION / (float) longest;
                    decoder.setTargetSize(Math.round(w * scale), Math.round(h * scale));
                }
            });
        }
        return MediaStore.Images.Media.getBitmap(requireActivity().getContentResolver(), imageUri);
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
                ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(
                                new android.util.Size(1920, 1440),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(resolutionSelector)
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
                            int yRowStride = planes[0].getRowStride();
                            int yPixelStride = planes[0].getPixelStride();

                            int width = mediaImage.getWidth();
                            int height = mediaImage.getHeight();
                            byte[] yData = new byte[width * height];
                            if (yRowStride == width && yPixelStride == 1) {
                                yBuffer.get(yData, 0, width * height);
                            } else {
                                byte[] rowBuffer = new byte[yRowStride];
                                for (int row = 0; row < height; row++) {
                                    yBuffer.position(row * yRowStride);
                                    int rowLength = Math.min(yRowStride, yBuffer.remaining());
                                    yBuffer.get(rowBuffer, 0, rowLength);
                                    for (int col = 0; col < width; col++) {
                                        yData[row * width + col] = rowBuffer[col * yPixelStride];
                                    }
                                }
                            }

                            int rotation = imageProxy.getImageInfo().getRotationDegrees();

                            Result result = null;
                            int[] rotations = {rotation, 0, 90, 180, 270};
                            for (int rot : rotations) {
                                try {
                                    PlanarYUVLuminanceSource source;
                                    if (rot == 90 || rot == 270) {
                                        byte[] rotatedData = new byte[width * height];
                                        for (int y = 0; y < height; y++) {
                                            for (int x = 0; x < width; x++) {
                                                if (rot == 90) {
                                                    rotatedData[x * height + height - y - 1] = yData[y * width + x];
                                                } else {
                                                    rotatedData[(width - x - 1) * height + y] = yData[y * width + x];
                                                }
                                            }
                                        }
                                        source = new PlanarYUVLuminanceSource(
                                                rotatedData, height, width, 0, 0, height, width, false);
                                    } else {
                                        source = new PlanarYUVLuminanceSource(
                                                yData, width, height, 0, 0, width, height, false);
                                    }
                                    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                                    result = reader.decode(bitmap);
                                    if (result != null) break;
                                } catch (NotFoundException e) {
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

                    if (scaleGestureDetector != null) {
                        scaleGestureDetector.onTouchEvent(event);
                    }

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
        final ResultParser.Parsed parsed = ResultParser.parse(result);

        String summary = describe(parsed);
        tvResult.setText(summary != null ? summary : result);
        if (parsed.warnings.isEmpty()) {
            tvWarning.setVisibility(View.GONE);
        } else {
            StringBuilder warnings = new StringBuilder();
            for (ResultParser.Warning w : parsed.warnings) {
                if (warnings.length() > 0) warnings.append('\n');
                warnings.append("\u26A0 ").append(warningText(w, parsed));
            }
            tvWarning.setText(warnings);
            tvWarning.setVisibility(View.VISIBLE);
        }
        bottomSheet.setVisibility(View.VISIBLE);
        vibrate();

        btnOpen.setVisibility(View.GONE);
        switch (parsed.type) {
            case WIFI:
                setPrimaryAction(getString(R.string.action_connect_wifi), () -> connectWifi(parsed));
                break;
            case PHONE:
                setPrimaryAction(getString(R.string.action_call), () -> startSafely(new Intent(Intent.ACTION_DIAL,
                        Uri.fromParts("tel", parsed.get("number"), null))));
                break;
            case SMS:
                setPrimaryAction(getString(R.string.action_send_sms), () -> {
                    Intent intent = new Intent(Intent.ACTION_SENDTO,
                            Uri.fromParts("smsto", parsed.get("number"), null));
                    if (parsed.has("body")) intent.putExtra("sms_body", parsed.get("body"));
                    startSafely(intent);
                });
                break;
            case EMAIL:
                setPrimaryAction(getString(R.string.action_send_email), () -> {
                    Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
                    intent.putExtra(Intent.EXTRA_EMAIL, new String[]{parsed.get("to")});
                    if (parsed.has("subject")) intent.putExtra(Intent.EXTRA_SUBJECT, parsed.get("subject"));
                    if (parsed.has("body")) intent.putExtra(Intent.EXTRA_TEXT, parsed.get("body"));
                    startSafely(intent);
                });
                break;
            case GEO:
                setPrimaryAction(getString(R.string.action_open_map), () -> startSafely(
                        new Intent(Intent.ACTION_VIEW, Uri.parse(result.trim()))));
                break;
            case CONTACT:
                setPrimaryAction(getString(R.string.action_add_contact), () -> addContact(parsed));
                break;
            case EVENT:
                setPrimaryAction(getString(R.string.action_add_event), () -> addEvent(parsed));
                break;
            case URL:
                setPrimaryAction(getString(parsed.warnings.isEmpty() ? R.string.open : R.string.action_open_anyway),
                        () -> startSafely(new Intent(Intent.ACTION_VIEW, Uri.parse(parsed.get("url")))));
                break;
            case TEXT:
            default:
                break;
        }
    }

    @Nullable
    private String describe(ResultParser.Parsed p) {
        StringBuilder sb = new StringBuilder();
        switch (p.type) {
            case WIFI: {
                sb.append(getString(R.string.sum_wifi));
                addLine(sb, R.string.sum_name, p.get("ssid"));
                String security = p.get("security");
                String password = p.get("password");
                if (security.equalsIgnoreCase("nopass") || (security.isEmpty() && password.isEmpty())) {
                    addLine(sb, R.string.sum_security, getString(R.string.sum_open_network));
                } else {
                    addLine(sb, R.string.sum_security, security);
                    addLine(sb, R.string.sum_password, password);
                }
                if (p.get("hidden").equals("true")) sb.append('\n').append(getString(R.string.sum_hidden));
                return sb.toString();
            }
            case SMS:
                sb.append(getString(R.string.sum_sms));
                addLine(sb, R.string.sum_to, p.get("number"));
                addLine(sb, R.string.sum_message, p.get("body"));
                return sb.toString();
            case EMAIL:
                if (!p.has("subject") && !p.has("body")) return null;
                sb.append(getString(R.string.sum_email));
                addLine(sb, R.string.sum_to, p.get("to"));
                addLine(sb, R.string.sum_subject, p.get("subject"));
                addLine(sb, R.string.sum_message, p.get("body"));
                return sb.toString();
            case CONTACT:
                sb.append(getString(R.string.sum_contact));
                addLine(sb, R.string.sum_name, p.get("name"));
                addLine(sb, R.string.sum_phone, p.get("phone"));
                addLine(sb, R.string.sum_phone2, p.get("phone2"));
                addLine(sb, R.string.sum_email, p.get("email"));
                addLine(sb, R.string.sum_company, p.get("org"));
                addLine(sb, R.string.sum_job_title, p.get("title"));
                addLine(sb, R.string.sum_address, p.get("address"));
                addLine(sb, R.string.sum_website, p.get("url"));
                return sb.toString();
            case EVENT: {
                sb.append(getString(R.string.sum_event));
                addLine(sb, R.string.sum_event_title, p.get("title"));
                if (p.beginMillis >= 0) {
                    Locale locale = getResources().getConfiguration().getLocales().get(0);
                    DateFormat df = p.allDay
                            ? DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
                            : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale);
                    if (p.allDay) df.setTimeZone(TimeZone.getTimeZone("UTC"));
                    addLine(sb, p.allDay ? R.string.sum_date : R.string.sum_starts,
                            df.format(new Date(p.beginMillis)));
                    if (!p.allDay) addLine(sb, R.string.sum_ends, df.format(new Date(p.endMillis)));
                }
                addLine(sb, R.string.sum_location, p.get("location"));
                return sb.toString();
            }
            default:
                return null;
        }
    }

    private void addLine(StringBuilder sb, int labelRes, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append('\n').append(getString(labelRes)).append(": ").append(value);
        }
    }

    private String warningText(ResultParser.Warning w, ResultParser.Parsed p) {
        switch (w) {
            case HTTP: return getString(R.string.warn_http);
            case USERINFO: return getString(R.string.warn_userinfo, p.get("host"));
            case SHORTENER: return getString(R.string.warn_shortener);
            case IP_ADDRESS: return getString(R.string.warn_ip);
            default: return getString(R.string.warn_lookalike);
        }
    }

    private void setPrimaryAction(String label, Runnable action) {
        btnOpen.setText(label);
        btnOpen.setOnClickListener(v -> action.run());
        btnOpen.setVisibility(View.VISIBLE);
    }

    private void startSafely(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(requireContext(), R.string.no_app_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void copyToClipboard(String label, String text, boolean sensitive) {
        ClipboardManager clipboard = (ClipboardManager) requireActivity()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Keeps the value out of the on-screen clipboard preview.
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
    }

    private void connectWifi(ResultParser.Parsed parsed) {
        String password = parsed.get("password");
        if (!password.isEmpty()) {
            copyToClipboard("Wi-Fi password", password, true);
            Toast.makeText(requireContext(),
                    getString(R.string.wifi_password_copied, parsed.get("ssid")),
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(requireContext(),
                    getString(R.string.wifi_choose_network, parsed.get("ssid")),
                    Toast.LENGTH_LONG).show();
        }
        startSafely(new Intent(Settings.ACTION_WIFI_SETTINGS));
    }

    private void addContact(ResultParser.Parsed parsed) {
        Intent intent = new Intent(ContactsContract.Intents.Insert.ACTION);
        intent.setType(ContactsContract.RawContacts.CONTENT_TYPE);
        if (parsed.has("name")) intent.putExtra(ContactsContract.Intents.Insert.NAME, parsed.get("name"));
        if (parsed.has("phone")) intent.putExtra(ContactsContract.Intents.Insert.PHONE, parsed.get("phone"));
        if (parsed.has("phone2")) intent.putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, parsed.get("phone2"));
        if (parsed.has("email")) intent.putExtra(ContactsContract.Intents.Insert.EMAIL, parsed.get("email"));
        if (parsed.has("email2")) intent.putExtra(ContactsContract.Intents.Insert.SECONDARY_EMAIL, parsed.get("email2"));
        if (parsed.has("org")) intent.putExtra(ContactsContract.Intents.Insert.COMPANY, parsed.get("org"));
        if (parsed.has("title")) intent.putExtra(ContactsContract.Intents.Insert.JOB_TITLE, parsed.get("title"));
        if (parsed.has("address")) intent.putExtra(ContactsContract.Intents.Insert.POSTAL, parsed.get("address"));
        String notes = parsed.get("note");
        if (parsed.has("url")) notes = notes.isEmpty() ? parsed.get("url") : notes + "\n" + parsed.get("url");
        if (!notes.isEmpty()) intent.putExtra(ContactsContract.Intents.Insert.NOTES, notes);
        startSafely(intent);
    }

    private void addEvent(ResultParser.Parsed parsed) {
        Intent intent = new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI);
        if (parsed.has("title")) intent.putExtra(CalendarContract.Events.TITLE, parsed.get("title"));
        if (parsed.has("location")) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, parsed.get("location"));
        if (parsed.has("description")) intent.putExtra(CalendarContract.Events.DESCRIPTION, parsed.get("description"));
        if (parsed.beginMillis >= 0) {
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, parsed.beginMillis);
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, parsed.endMillis);
            intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, parsed.allDay);
        }
        startSafely(intent);
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