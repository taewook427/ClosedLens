package com.example.k7closedlens;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_OVERLAY_PERMISSION = 1001;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 1002;

    private ProgressBar progressBar;
    private TextView statusText;
    private Button startButton;

    private ActivityResultLauncher<Intent> mediaProjectionLauncher;

    private boolean modelsReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        startButton = findViewById(R.id.startButton);

        startButton.setEnabled(false);

        startButton.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission();
                return;
            }
            requestScreenCapture();
        });

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATION_PERMISSION);
            }
        }

        mediaProjectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        // Start the screen capture service FIRST — it must call
                        // startForeground(MEDIA_PROJECTION) before anything else.
                        // Pass resultCode and resultData via Intent extras.
                        Intent captureIntent = new Intent(this, ScreenCaptureService.class);
                        captureIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.getResultCode());
                        captureIntent.putExtra(ScreenCaptureService.EXTRA_DATA, result.getData());
                        ContextCompat.startForegroundService(this, captureIntent);

                        // Start the floating overlay service
                        Intent floatingIntent = new Intent(this, FloatingService.class);
                        ContextCompat.startForegroundService(this, floatingIntent);

                        Toast.makeText(this, "ClosedLens 활성화됨", Toast.LENGTH_SHORT).show();
                        moveTaskToBack(true);
                    } else {
                        Toast.makeText(this, "화면 캡처 권한이 필요합니다", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        // Request overlay permission if not granted
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
        }

        // Download ML Kit models
        downloadModels();
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "오버레이 권한 허용됨", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "오버레이 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void requestScreenCapture() {
        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent());
    }

    private void downloadModels() {
        statusText.setText("ML Kit 모델 확인 중...");
        progressBar.setVisibility(ProgressBar.VISIBLE);
        progressBar.setIndeterminate(true);

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build();
        Translator translator = Translation.getClient(options);

        // Check if models are already downloaded
        RemoteModelManager modelManager = RemoteModelManager.getInstance();
        TranslateRemoteModel koModel = new TranslateRemoteModel.Builder(TranslateLanguage.KOREAN).build();
        TranslateRemoteModel enModel = new TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build();

        modelManager.isModelDownloaded(koModel).addOnSuccessListener(koDownloaded -> {
            modelManager.isModelDownloaded(enModel).addOnSuccessListener(enDownloaded -> {
                if (koDownloaded && enDownloaded) {
                    statusText.setText("모델 준비 완료 ✓");
                    progressBar.setVisibility(ProgressBar.GONE);
                    modelsReady = true;
                    startButton.setEnabled(true);
                    translator.close();
                } else {
                    statusText.setText("번역 모델 다운로드 중...");
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);

                    DownloadConditions conditions = new DownloadConditions.Builder().build();

                    // Download step 1: English model
                    statusText.setText("영어 모델 다운로드 중...");
                    progressBar.setProgress(10);

                    translator.downloadModelIfNeeded(conditions)
                            .addOnSuccessListener(unused -> {
                                progressBar.setProgress(100);
                                statusText.setText("모델 다운로드 완료 ✓");
                                modelsReady = true;
                                startButton.setEnabled(true);
                                translator.close();
                            })
                            .addOnFailureListener(e -> {
                                statusText.setText("모델 다운로드 실패: " + e.getMessage());
                                progressBar.setVisibility(ProgressBar.GONE);
                                translator.close();
                            });
                }
            });
        }).addOnFailureListener(e -> {
            statusText.setText("모델 상태 확인 실패: " + e.getMessage());
            progressBar.setVisibility(ProgressBar.GONE);
            translator.close();
        });
    }
}
