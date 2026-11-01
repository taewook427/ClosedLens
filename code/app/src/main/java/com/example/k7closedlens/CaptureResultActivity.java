package com.example.k7closedlens;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class CaptureResultActivity extends AppCompatActivity {

    private static final String TAG = "CaptureResult";
    // Korean Unicode ranges: Hangul Syllables, Jamo, Compat Jamo
    private static final Pattern KOREAN_PATTERN =
            Pattern.compile("[\\uAC00-\\uD7AF\\u1100-\\u11FF\\u3130-\\u318F]");

    private ImageView captureImageView;
    private ImageView overlayImageView;
    private MaterialButton translateButton;
    private MaterialButton closeButton;
    private ProgressBar translateProgress;
    private TextView translateStatusText;
    private FrameLayout imageContainer;

    private Bitmap capturedBitmap;
    private Translator translator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Make fullscreen
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        setContentView(R.layout.activity_capture_result);

        captureImageView = findViewById(R.id.captureImageView);
        overlayImageView = findViewById(R.id.overlayImageView);
        translateButton = findViewById(R.id.translateButton);
        closeButton = findViewById(R.id.closeButton);
        translateProgress = findViewById(R.id.translateProgress);
        translateStatusText = findViewById(R.id.translateStatusText);
        imageContainer = findViewById(R.id.imageContainer);

        String imagePath = getIntent().getStringExtra("image_path");
        if (imagePath != null) {
            capturedBitmap = BitmapFactory.decodeFile(imagePath);
            captureImageView.setImageBitmap(capturedBitmap);

            // Clean up temp file
            new File(imagePath).delete();
        }

        // Set up translator
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build();
        translator = Translation.getClient(options);

        translateButton.setOnClickListener(v -> performOcrAndTranslate());

        closeButton.setOnClickListener(v -> finish());
    }

    private void performOcrAndTranslate() {
        if (capturedBitmap == null) {
            Toast.makeText(this, "캡처된 이미지가 없습니다", Toast.LENGTH_SHORT).show();
            return;
        }

        translateButton.setEnabled(false);
        translateProgress.setVisibility(View.VISIBLE);
        translateStatusText.setVisibility(View.VISIBLE);
        translateStatusText.setText("텍스트 인식 중...");

        InputImage image = InputImage.fromBitmap(capturedBitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        recognizer.process(image)
                .addOnSuccessListener(text -> {
                    if (text.getTextBlocks().isEmpty()) {
                        translateStatusText.setText("텍스트를 찾을 수 없습니다");
                        translateProgress.setVisibility(View.GONE);
                        translateButton.setEnabled(true);
                        return;
                    }

                    translateStatusText.setText("번역 중...");
                    translateBlocks(text);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "OCR failed", e);
                    translateStatusText.setText("텍스트 인식 실패: " + e.getMessage());
                    translateProgress.setVisibility(View.GONE);
                    translateButton.setEnabled(true);
                });
    }

    private void translateBlocks(Text text) {
        List<Text.TextBlock> blocks = text.getTextBlocks();
        List<String> originals = new ArrayList<>();
        List<Rect> boundingBoxes = new ArrayList<>();

        for (Text.TextBlock block : blocks) {
            originals.add(block.getText());
            boundingBoxes.add(block.getBoundingBox());
        }

        List<String> translations = new ArrayList<>();
        AtomicInteger completed = new AtomicInteger(0);
        int total = originals.size();

        DownloadConditions conditions = new DownloadConditions.Builder().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    for (int i = 0; i < total; i++) {
                        final int index = i;
                        translator.translate(originals.get(i))
                                .addOnSuccessListener(translated -> {
                                    synchronized (translations) {
                                        while (translations.size() <= index) {
                                            translations.add(null);
                                        }
                                        translations.set(index, translated);
                                    }

                                    int done = completed.incrementAndGet();
                                    translateStatusText.setText("번역 중... (" + done + "/" + total + ")");

                                    if (done == total) {
                                        drawTranslationOverlay(translations, boundingBoxes);
                                    }
                                })
                                .addOnFailureListener(e -> {
                                    Log.e(TAG, "Translation failed for block " + index, e);
                                    synchronized (translations) {
                                        while (translations.size() <= index) {
                                            translations.add(null);
                                        }
                                        translations.set(index, originals.get(index));
                                    }
                                    int done = completed.incrementAndGet();
                                    if (done == total) {
                                        drawTranslationOverlay(translations, boundingBoxes);
                                    }
                                });
                    }
                })
                .addOnFailureListener(e -> {
                    translateStatusText.setText("모델 다운로드 실패: " + e.getMessage());
                    translateProgress.setVisibility(View.GONE);
                    translateButton.setEnabled(true);
                });
    }

    private void drawTranslationOverlay(List<String> translations, List<Rect> boundingBoxes) {
        if (capturedBitmap == null) return;

        Bitmap overlay = Bitmap.createBitmap(
                capturedBitmap.getWidth(),
                capturedBitmap.getHeight(),
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(overlay);

        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.argb(180, 60, 60, 60));
        bgPaint.setStyle(Paint.Style.FILL);

        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setAntiAlias(true);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        for (int i = 0; i < translations.size(); i++) {
            String translated = translations.get(i);
            Rect box = boundingBoxes.get(i);

            if (translated == null || box == null) continue;

            // Calculate text size to fit the bounding box
            float textSize = calculateTextSize(translated, box, textPaint);
            textPaint.setTextSize(textSize);

            // Measure text
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float lineHeight = fm.descent - fm.ascent;

            // Word wrap
            List<String> lines = wrapText(translated, textPaint, box.width() - 16);

            float totalTextHeight = lines.size() * lineHeight;
            float bgTop = box.top;
            float bgBottom = Math.max(box.bottom, box.top + totalTextHeight + 16);

            // Draw background with padding
            int padding = 8;
            RectF bgRect = new RectF(
                    box.left - padding,
                    bgTop - padding,
                    box.right + padding,
                    bgBottom + padding
            );
            canvas.drawRoundRect(bgRect, 8, 8, bgPaint);

            // Draw text lines
            float y = bgTop - fm.ascent + 4;
            for (String line : lines) {
                canvas.drawText(line, box.left + 4, y, textPaint);
                y += lineHeight;
            }
        }

        overlayImageView.setImageBitmap(overlay);
        overlayImageView.setVisibility(View.VISIBLE);

        translateProgress.setVisibility(View.GONE);
        translateStatusText.setText("번역 완료 ✓");
        translateButton.setEnabled(true);
        translateButton.setText("다시 번역");
    }

    private float calculateTextSize(String text, Rect box, Paint paint) {
        float size = box.height() * 0.8f;
        size = Math.max(size, 14);
        size = Math.min(size, 48);

        paint.setTextSize(size);
        while (paint.measureText(text) > box.width() * 3 && size > 10) {
            size -= 1;
            paint.setTextSize(size);
        }
        return size;
    }

    private List<String> wrapText(String text, Paint paint, float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (maxWidth <= 0) {
            lines.add(text);
            return lines;
        }

        String[] words = text.split("(?<=\\s)|(?=\\s)");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            String testLine = currentLine.toString() + word;
            if (paint.measureText(testLine) > maxWidth && currentLine.length() > 0) {
                lines.add(currentLine.toString().trim());
                currentLine = new StringBuilder(word);
            } else {
                currentLine.append(word);
            }
        }
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString().trim());
        }

        if (lines.isEmpty()) {
            lines.add(text);
        }
        return lines;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (translator != null) {
            translator.close();
        }
    }
}
