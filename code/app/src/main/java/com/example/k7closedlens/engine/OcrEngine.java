package com.example.k7closedlens.engine;

import android.graphics.Bitmap;
import android.graphics.Rect;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-script OCR engine with robust script scoring to eliminate phantom CJK glyph hallucinations.
 */
public class OcrEngine {

    public static class Result {
        public String text;
        public Rect box;

        public Result(String text, Rect box) {
            this.text = text != null ? text : "";
            this.box = box != null ? box : new Rect();
        }
    }

    private final TextRecognizer latRec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final TextRecognizer krRec = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
    private final TextRecognizer jpRec = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
    private final TextRecognizer cnRec = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

    public List<Result> recognize(Bitmap bitmap) {
        List<Result> list = new ArrayList<>();
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return list;

        InputImage img = InputImage.fromBitmap(bitmap, 0);
        try {
            Text lat = Tasks.await(latRec.process(img));
            Text kr = Tasks.await(krRec.process(img));
            Text jp = Tasks.await(jpRec.process(img));
            Text cn = Tasks.await(cnRec.process(img));

            // Latin first as primary baseline for punctuation & western text
            collect(list, lat, "lat");
            collect(list, kr, "kr");
            collect(list, jp, "jp");
            collect(list, cn, "cn");
        } catch (Exception ignored) {
        }

        list.sort((a, b) -> {
            if (Math.abs(a.box.top - b.box.top) > 15) return Integer.compare(a.box.top, b.box.top);
            return Integer.compare(a.box.left, b.box.left);
        });
        return list;
    }

    private void collect(List<Result> list, Text res, String script) {
        if (res == null) return;
        for (Text.TextBlock block : res.getTextBlocks()) {
            String raw = block.getText();
            Rect b = block.getBoundingBox();
            if (raw == null || raw.trim().isEmpty() || b == null) continue;

            String norm = normalize(raw);
            if (norm.isEmpty()) continue;

            int newScore = score(norm, script);
            boolean handled = false;

            for (int i = 0; i < list.size(); i++) {
                Result ex = list.get(i);
                if (overlaps(b, ex.box)) {
                    int exScore = score(ex.text, "existing");
                    if (newScore > exScore) {
                        list.set(i, new Result(norm, b));
                    }
                    handled = true;
                    break;
                }
            }
            if (!handled) list.add(new Result(norm, b));
        }
    }

    private String normalize(String s) {
        String[] lines = s.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (sb.length() == 0) {
                sb.append(t);
            } else {
                char last = sb.charAt(sb.length() - 1);
                char first = t.charAt(0);
                if (last == '-') {
                    sb.deleteCharAt(sb.length() - 1);
                    sb.append(t);
                } else if (isCjk(last) || isCjk(first)) {
                    sb.append(t);
                } else {
                    sb.append(" ").append(t);
                }
            }
        }
        return sb.toString().trim();
    }

    private boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3040 && c <= 0x30FF) || (c >= 0xAC00 && c <= 0xD7AF);
    }

    private int score(String s, String script) {
        int kr = 0, cn = 0, jp = 0, lat = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x1100 && c <= 0x11FF)) kr++;
            else if (c >= 0x4E00 && c <= 0x9FFF) cn++;
            else if ((c >= 0x3040 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF)) jp++;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) lat++;
        }

        // If overwhelmingly Latin (> 4 chars), penalize single stray CJK hallucinations (e.g. em-dash misclassified as '한')
        if (lat >= 4 && (kr + cn + jp) <= 1) {
            return "lat".equals(script) ? (lat * 10) : 0;
        }

        if ("kr".equals(script)) {
            // Legitimate Korean text has genuine Hangul characters
            return (kr >= 2 || kr > lat) ? (kr * 6 + lat) : 0;
        }
        if ("jp".equals(script)) {
            // Japanese text has Kana and Kanji
            return (jp >= 2 || jp + cn > lat) ? (jp * 6 + cn * 4 + lat) : 0;
        }
        if ("cn".equals(script)) {
            return (cn >= 2 || cn > lat) ? (cn * 5 + lat) : 0;
        }
        return lat * 4;
    }

    private boolean overlaps(Rect a, Rect b) {
        int l = Math.max(a.left, b.left);
        int t = Math.max(a.top, b.top);
        int r = Math.min(a.right, b.right);
        int bot = Math.min(a.bottom, b.bottom);
        if (l >= r || t >= bot) return false;
        int inter = (r - l) * (bot - t);
        return ((float) inter / Math.min(a.width() * a.height(), b.width() * b.height())) > 0.35f;
    }

    public void release() {
        try { latRec.close(); } catch (Exception ignored) {}
        try { krRec.close(); } catch (Exception ignored) {}
        try { jpRec.close(); } catch (Exception ignored) {}
        try { cnRec.close(); } catch (Exception ignored) {}
    }
}
