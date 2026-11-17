package com.example.k7closedlens;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.k7closedlens.engine.Translator;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * Minimal, modern controller for ClosedLens settings and service lifecycle.
 */
public class MainActivity extends AppCompatActivity {

    public static final String MODEL_URL = "https://huggingface.co/Xenova/nllb-200-distilled-600M/tree/main";

    public static final String[] LANG_CODES = {
            "eng_Latn", "kor_Hang", "jpn_Jpan", "zho_Hans", "zho_Hant",
            "spa_Latn", "fra_Latn", "deu_Latn", "rus_Cyrl", "ara_Arab",
            "por_Latn", "ita_Latn", "vie_Latn", "ind_Latn", "tha_Thai"
    };

    public static final String[] LANG_NAMES = {
            "English", "한국어", "日本語", "简体中文", "繁體中文",
            "Español", "Français", "Deutsch", "Русский", "العربية",
            "Português", "Italiano", "Tiếng Việt", "Bahasa Indonesia", "ไทย"
    };

    private View statusDot;
    private TextView statusText, modelDesc;
    private ProgressBar progressBar;
    private MaterialButton importBtn;

    private ActivityResultLauncher<Intent> mediaLauncher;
    private ActivityResultLauncher<Intent> importLauncher;
    private ActivityResultLauncher<Intent> overlayLauncher;

    public static int getDefaultLangIndex() {
        String sys = Locale.getDefault().getLanguage();
        for (int i = 0; i < LANG_CODES.length; i++) {
            if (LANG_CODES[i].startsWith(sys)) return i;
        }
        return 1; // default to Korean if not matched
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        modelDesc = findViewById(R.id.modelDesc);
        progressBar = findViewById(R.id.progressBar);
        importBtn = findViewById(R.id.importBtn);

        setupLaunchers();
        setupLanguageSpinner();
        setupActions();
        checkPermissions();
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void setupLaunchers() {
        mediaLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                res -> {
                    if (res.getResultCode() == RESULT_OK && res.getData() != null) {
                        Intent capIntent = new Intent(this, ScreenCaptureService.class);
                        capIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, res.getResultCode());
                        capIntent.putExtra(ScreenCaptureService.EXTRA_DATA, res.getData());
                        ContextCompat.startForegroundService(this, capIntent);

                        startService(new Intent(this, FloatingService.class));
                        Toast.makeText(this, R.string.service_started, Toast.LENGTH_SHORT).show();
                        moveTaskToBack(true);
                    } else {
                        Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_SHORT).show();
                    }
                }
        );

        importLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                res -> {
                    if (res.getResultCode() == RESULT_OK && res.getData() != null) {
                        Uri uri = res.getData().getData();
                        if (uri != null) importZip(uri);
                    }
                }
        );

        overlayLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                res -> {
                    if (Settings.canDrawOverlays(this)) startLens();
                }
        );
    }

    private void setupLanguageSpinner() {
        Spinner spinner = findViewById(R.id.langSpinner);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item, LANG_NAMES
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(adapter);

        SharedPreferences prefs = getSharedPreferences("closedlens_prefs", MODE_PRIVATE);
        int savedIdx = prefs.getInt("target_lang_idx", getDefaultLangIndex());
        spinner.setSelection(savedIdx);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt("target_lang_idx", pos).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void setupActions() {
        MaterialButton copyBtn = findViewById(R.id.copyUrlBtn);
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Model URL", MODEL_URL));
                Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
            }
        });

        importBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            importLauncher.launch(intent);
        });

        MaterialButton startBtn = findViewById(R.id.startBtn);
        startBtn.setOnClickListener(v -> startLens());

        MaterialButton stopBtn = findViewById(R.id.stopBtn);
        stopBtn.setOnClickListener(v -> stopLens());
    }

    private void startLens() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            overlayLauncher.launch(intent);
            return;
        }

        // Pre-warm translation engine in background so first capture is instant
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (Translator.isInstalled(this)) Translator.get().load(this);
            } catch (Exception ignored) {}
        });

        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr != null) {
            mediaLauncher.launch(mgr.createScreenCaptureIntent());
        }
    }

    private void stopLens() {
        stopService(new Intent(this, FloatingService.class));
        stopService(new Intent(this, ScreenCaptureService.class));
        Executors.newSingleThreadExecutor().execute(() -> Translator.get().release());
        Toast.makeText(this, R.string.service_stopped, Toast.LENGTH_SHORT).show();
    }

    private void updateStatus() {
        boolean ready = Translator.isInstalled(this);
        if (ready) {
            statusDot.setBackgroundResource(R.drawable.status_dot_ready);
            statusText.setText(R.string.status_ready);
            modelDesc.setText(R.string.status_model_ready);
            modelDesc.setTextColor(0xFFE4E4E7);
        } else {
            statusDot.setBackgroundResource(R.drawable.status_dot_warning);
            statusText.setText(R.string.status_no_model);
            modelDesc.setText(R.string.status_model_missing);
            modelDesc.setTextColor(0xFFF59E0B);
        }
    }

    private void importZip(Uri uri) {
        progressBar.setVisibility(View.VISIBLE);
        modelDesc.setText(R.string.status_installing);
        importBtn.setEnabled(false);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Translator.importZip(this, uri);
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    importBtn.setEnabled(true);
                    updateStatus();
                    Toast.makeText(this, R.string.status_install_done, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    importBtn.setEnabled(true);
                    updateStatus();
                    Toast.makeText(this, getString(R.string.status_install_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }
}
