package com.example.k7closedlens;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.example.k7closedlens.engine.OcrEngine;
import com.example.k7closedlens.engine.Translator;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Capture result viewer with interactive drag-to-translate.
 */
public class CaptureResultActivity extends AppCompatActivity {

    private ImageView captureView;
    private DragOverlayView overlayView;
    private ProgressBar progressBar;
    private TextView statusText;

    private Bitmap sourceBmp;
    private OcrEngine ocr;
    private Translator translator;
    private ExecutorService executor;
    private String targetLang;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture_result);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat insets = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (insets != null) {
            insets.hide(WindowInsetsCompat.Type.statusBars());
            insets.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        FloatingService.setVisible(false);

        captureView = findViewById(R.id.captureImageView);
        overlayView = findViewById(R.id.dragOverlayView);
        progressBar = findViewById(R.id.translateProgress);
        statusText = findViewById(R.id.translateStatusText);

        MaterialButton clearBtn = findViewById(R.id.clearButton);
        MaterialButton closeBtn = findViewById(R.id.closeButton);

        SharedPreferences prefs = getSharedPreferences("closedlens_prefs", MODE_PRIVATE);
        int langIdx = prefs.getInt("target_lang_idx", MainActivity.getDefaultLangIndex());
        targetLang = (langIdx >= 0 && langIdx < MainActivity.LANG_CODES.length)
                ? MainActivity.LANG_CODES[langIdx] : "kor_Hang";

        String path = getIntent().getStringExtra("image_path");
        if (path != null) {
            sourceBmp = BitmapFactory.decodeFile(path);
            if (sourceBmp != null) captureView.setImageBitmap(sourceBmp);
            File f = new File(path);
            if (f.exists()) f.delete();
        }

        ocr = new OcrEngine();
        translator = Translator.get();
        executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            try {
                if (Translator.isInstalled(this)) translator.load(this);
            } catch (Exception ignored) {}
        });

        overlayView.setListener(this::processArea);
        clearBtn.setOnClickListener(v -> {
            overlayView.clear();
            statusText.setText(R.string.drag_hint);
        });
        closeBtn.setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        FloatingService.setVisible(false);
    }

    private void processArea(Rect rect) {
        if (sourceBmp == null) return;

        int w = sourceBmp.getWidth();
        int h = sourceBmp.getHeight();

        float density = getResources().getDisplayMetrics().density;
        int padX = (int) (14 * density);
        int padY = (int) (10 * density);

        int cLeft = Math.max(0, rect.left - padX);
        int cTop = Math.max(0, rect.top - padY);
        int cRight = Math.min(w, rect.right + padX);
        int cBottom = Math.min(h, rect.bottom + padY);

        int cW = cRight - cLeft;
        int cH = cBottom - cTop;
        if (cW < 15 || cH < 15) return;

        progressBar.setVisibility(View.VISIBLE);
        statusText.setText(R.string.translating);

        executor.execute(() -> {
            try {
                Bitmap crop = Bitmap.createBitmap(sourceBmp, cLeft, cTop, cW, cH);
                List<OcrEngine.Result> ocrResults = ocr.recognize(crop);
                crop.recycle();

                if (ocrResults.isEmpty()) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        statusText.setText(R.string.no_text);
                    });
                    return;
                }

                StringBuilder sb = new StringBuilder();
                for (OcrEngine.Result r : ocrResults) {
                    if (r.text.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(r.text);
                }
                String text = sb.toString().trim();

                if (!translator.isReady()) {
                    if (!Translator.isInstalled(this)) {
                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            statusText.setText(R.string.model_not_ready);
                        });
                        return;
                    }
                    translator.load(this);
                }

                String srcLang = Translator.detectLang(text);
                String translated = srcLang.equals(targetLang) ? text : translator.translate(text, srcLang, targetLang);

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    overlayView.addBox(rect, translated);
                    statusText.setText(R.string.translate_done);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setText(R.string.translate_failed);
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        FloatingService.setVisible(true);
        if (ocr != null) ocr.release();
        if (executor != null) executor.shutdown();
    }
}
