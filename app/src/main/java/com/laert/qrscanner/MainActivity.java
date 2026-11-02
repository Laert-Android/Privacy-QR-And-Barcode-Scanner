package com.laert.qrscanner;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import android.widget.FrameLayout;
import android.view.ViewGroup;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

public class MainActivity extends AppCompatActivity {

    @Override
    public void onBackPressed() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.exit_title)
                .setMessage(R.string.exit_message)
                .setPositiveButton(R.string.share, (dialog, which) -> {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_app_text,
                            "https://github.com/Laert-Android/Privacy-QR-And-Barcode-Scanner"));
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.share_via)));
                })
                .setNegativeButton(R.string.exit, (dialog, which) -> {
                    super.onBackPressed();
                })
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Handle edge-to-edge display for Samsung and other devices
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(android.R.id.content), (v, insets) -> {
                    int systemBarsBottom = insets.getInsets(
                            androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom;
                    int imeBottom = insets.getInsets(
                            androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;

                    BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
                    bottomNav.setPadding(0, 0, 0, systemBarsBottom);

                    FrameLayout container = findViewById(R.id.fragmentContainer);
                    container.setPadding(0, 0, 0, 0);
                    ViewGroup.MarginLayoutParams params =
                            (ViewGroup.MarginLayoutParams) container.getLayoutParams();
                    bottomNav.post(() -> {
                        int navHeight = bottomNav.getHeight();
                        int bottomSpace = Math.max(imeBottom, navHeight);
                        if (params.bottomMargin != bottomSpace) {
                            params.bottomMargin = bottomSpace;
                            container.setLayoutParams(params);
                        }
                    });
                    return insets;
                });

        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);

        if (savedInstanceState == null) {
            Uri sharedImage = sharedImageFrom(getIntent());
            loadFragment(sharedImage != null
                    ? ScanFragment.newInstanceForImage(sharedImage) : new ScanFragment());
        }

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_scan) {
                loadFragment(new ScanFragment());
                return true;
            } else if (id == R.id.nav_generate) {
                loadFragment(new GenerateFragment());
                return true;
            } else if (id == R.id.nav_history) {
                loadFragment(new HistoryFragment());
                return true;
            } else if (id == R.id.nav_settings) {
                loadFragment(new SettingsFragment());
                return true;
            }
            return false;
        });
    }

    @Nullable
    private Uri sharedImageFrom(@Nullable Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return null;
        String type = intent.getType();
        if (type == null || !type.startsWith("image/")) return null;
        try {
            Uri uri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : legacyStreamExtra(intent);
            return uri != null && "content".equals(uri.getScheme()) ? uri : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static Uri legacyStreamExtra(Intent intent) {
        return intent.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }
}