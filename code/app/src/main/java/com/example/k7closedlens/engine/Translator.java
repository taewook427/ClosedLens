package com.example.k7closedlens.engine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Universal on-device NLLB-200 translation and script detection engine.
 * Thread-safe singleton keeps models warm in RAM to avoid expensive reloads on every capture.
 */
public class Translator {

    private static final String TAG = "Translator";
    private static final int EOS = 2;
    private static final int UNK = 0;

    private static volatile Translator instance;

    public static Translator get() {
        if (instance == null) {
            synchronized (Translator.class) {
                if (instance == null) instance = new Translator();
            }
        }
        return instance;
    }

    private OrtEnvironment env;
    private OrtSession encoder;
    private OrtSession decoder;
    private Map<String, Integer> tokenMap;
    private String[] vocab;
    private boolean hasCacheBranch = false;
    private final List<String> pastKeys = new ArrayList<>();
    private boolean ready = false;

    // --- Model Storage & Management ---

    public static File getModelDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "models/nllb");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static boolean isInstalled(Context ctx) {
        File dir = getModelDir(ctx);
        boolean enc = new File(dir, "encoder_model_quantized.onnx").exists() || new File(dir, "encoder_model.onnx").exists();
        boolean dec = new File(dir, "decoder_model_merged_quantized.onnx").exists() || new File(dir, "decoder_model_merged.onnx").exists();
        boolean tok = new File(dir, "tokenizer.json").exists();
        return enc && dec && tok;
    }

    public static void importZip(Context ctx, Uri uri) throws Exception {
        File targetDir = getModelDir(ctx);
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                String name = new File(entry.getName()).getName();
                if (name.isEmpty() || entry.isDirectory()) continue;
                File out = new File(targetDir, name);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int len;
                    while ((len = zis.read(buf)) > 0) {
                        fos.write(buf, 0, len);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    // --- Model Loading ---

    public synchronized void load(Context ctx) throws Exception {
        if (ready) return;
        File dir = getModelDir(ctx);

        env = OrtEnvironment.getEnvironment();
        try { env.setTelemetry(false); } catch (Exception ignored) {}
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(4);

        File enc = new File(dir, "encoder_model_quantized.onnx");
        if (!enc.exists()) enc = new File(dir, "encoder_model.onnx");

        File dec = new File(dir, "decoder_model_merged_quantized.onnx");
        if (!dec.exists()) dec = new File(dir, "decoder_model_merged.onnx");

        encoder = env.createSession(enc.getAbsolutePath(), opts);
        decoder = env.createSession(dec.getAbsolutePath(), opts);

        pastKeys.clear();
        hasCacheBranch = false;
        for (String name : decoder.getInputNames()) {
            if ("use_cache_branch".equals(name)) hasCacheBranch = true;
            else if (name.startsWith("past_key_values")) pastKeys.add(name);
        }

        File tokFile = new File(dir, "tokenizer.json");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(tokFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }

        JSONObject model = new JSONObject(sb.toString()).getJSONObject("model");
        tokenMap = new HashMap<>();

        if (model.has("vocab")) {
            Object vObj = model.get("vocab");
            if (vObj instanceof JSONArray) {
                JSONArray arr = (JSONArray) vObj;
                vocab = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    String t = arr.getJSONArray(i).getString(0);
                    tokenMap.put(t, i);
                    vocab[i] = t;
                }
            } else if (vObj instanceof JSONObject) {
                JSONObject obj = (JSONObject) vObj;
                vocab = new String[obj.length()];
                Iterator<String> it = obj.keys();
                while (it.hasNext()) {
                    String t = it.next();
                    int id = obj.getInt(t);
                    tokenMap.put(t, id);
                    if (id < vocab.length) vocab[id] = t;
                }
            }
        }

        ready = true;
        Log.i(TAG, "Translator loaded successfully");
    }

    public boolean isReady() {
        return ready;
    }

    // --- Translation Inference ---

    public String translate(String text, String srcLang, String tgtLang) {
        if (!ready || text == null || text.trim().isEmpty()) return text;
        try {
            long[] inputIds = tokenize(text, srcLang);
            long[] mask = new long[inputIds.length];
            Arrays.fill(mask, 1L);

            // 1. Encoder
            Map<String, OnnxTensor> encIn = new HashMap<>();
            OnnxTensor tIds = OnnxTensor.createTensor(env, new long[][]{inputIds});
            OnnxTensor tMask = OnnxTensor.createTensor(env, new long[][]{mask});
            encIn.put("input_ids", tIds);
            encIn.put("attention_mask", tMask);

            OrtSession.Result encOut = encoder.run(encIn);
            float[][][] hidden = (float[][][]) encOut.get(0).getValue();
            tIds.close();
            tMask.close();
            encOut.close();

            // 2. Decoder Greedy Loop
            List<Long> decTokens = new ArrayList<>(Arrays.asList((long) EOS, (long) getLangToken(tgtLang)));

            for (int step = 0; step < 256; step++) {
                long[][] curIn = new long[1][decTokens.size()];
                for (int i = 0; i < decTokens.size(); i++) curIn[0][i] = decTokens.get(i);

                Map<String, OnnxTensor> decIn = new HashMap<>();
                List<OnnxTensor> toClose = new ArrayList<>();

                OnnxTensor dIds = OnnxTensor.createTensor(env, curIn);
                OnnxTensor dHid = OnnxTensor.createTensor(env, hidden);
                OnnxTensor dMask = OnnxTensor.createTensor(env, new long[][]{mask});
                decIn.put("input_ids", dIds);
                decIn.put("encoder_hidden_states", dHid);
                decIn.put("encoder_attention_mask", dMask);
                toClose.add(dIds);
                toClose.add(dHid);
                toClose.add(dMask);

                if (hasCacheBranch) {
                    OnnxTensor ucb = OnnxTensor.createTensor(env, new boolean[]{false});
                    decIn.put("use_cache_branch", ucb);
                    toClose.add(ucb);
                }

                for (String pKey : pastKeys) {
                    OnnxTensor dummy = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), new long[]{1, 16, 0, 64});
                    decIn.put(pKey, dummy);
                    toClose.add(dummy);
                }

                OrtSession.Result decOut = null;
                int nextToken = 0;
                try {
                    decOut = decoder.run(decIn);
                    float[][][] logits = (float[][][]) decOut.get(0).getValue();
                    float[] last = logits[0][decTokens.size() - 1];

                    float max = Float.NEGATIVE_INFINITY;
                    for (int i = 0; i < last.length; i++) {
                        if (last[i] > max) {
                            max = last[i];
                            nextToken = i;
                        }
                    }
                } finally {
                    if (decOut != null) decOut.close();
                    for (OnnxTensor t : toClose) t.close();
                }

                if (nextToken == EOS) break;
                decTokens.add((long) nextToken);
            }

            // 3. Detokenize
            StringBuilder res = new StringBuilder();
            for (int i = 2; i < decTokens.size(); i++) {
                int id = decTokens.get(i).intValue();
                if (vocab != null && id < vocab.length && vocab[id] != null) {
                    res.append(vocab[id]);
                }
            }
            return res.toString().replace("\u2581", " ").trim();

        } catch (Exception e) {
            Log.e(TAG, "Translate error", e);
            return text;
        }
    }

    private long[] tokenize(String text, String lang) {
        List<Long> ids = new ArrayList<>();
        ids.add((long) getLangToken(lang));

        String s = "\u2581" + text.trim().replace(" ", "\u2581");
        int len = s.length();
        int i = 0;

        while (i < len) {
            boolean found = false;
            int max = Math.min(len, i + 20);
            for (int j = max; j > i; j--) {
                String sub = s.substring(i, j);
                if (tokenMap.containsKey(sub)) {
                    ids.add((long) tokenMap.get(sub));
                    i = j;
                    found = true;
                    break;
                }
            }
            if (!found) {
                String single = s.substring(i, i + 1);
                ids.add((long) tokenMap.getOrDefault(single, UNK));
                i++;
            }
        }

        ids.add((long) EOS);
        long[] arr = new long[ids.size()];
        for (int k = 0; k < ids.size(); k++) arr[k] = ids.get(k);
        return arr;
    }

    private int getLangToken(String lang) {
        if (tokenMap == null || lang == null) return UNK;
        if (tokenMap.containsKey(lang)) return tokenMap.get(lang);
        String wrapped = "__" + lang + "__";
        return tokenMap.getOrDefault(wrapped, UNK);
    }

    // --- Universal Script & Language Detection ---

    public static String detectLang(String text) {
        if (text == null || text.trim().isEmpty()) return "eng_Latn";

        int kr = 0, jp = 0, cn = 0, ar = 0, ru = 0, th = 0, lat = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x1100 && c <= 0x11FF)) kr++;
            else if ((c >= 0x3040 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF)) jp++;
            else if (c >= 0x4E00 && c <= 0x9FFF) cn++;
            else if (c >= 0x0600 && c <= 0x06FF) ar++;
            else if (c >= 0x0400 && c <= 0x04FF) ru++;
            else if (c >= 0x0E00 && c <= 0x0E7F) th++;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) lat++;
        }

        // Japanese: Kana alongside Kanji
        if (jp >= 2 || (jp > 0 && jp + cn > lat)) return "jpn_Jpan";

        // Korean: Genuine Hangul syllables
        if (kr >= 2 && kr >= lat * 0.25f) return "kor_Hang";
        if (kr > lat) return "kor_Hang";

        // Chinese: Hanzi dominant
        if (cn >= 2 && cn > lat) return "zho_Hans";

        // Other non-Latin scripts
        if (ar > lat && ar > 0) return "ara_Arab";
        if (ru > lat && ru > 0) return "rus_Cyrl";
        if (th > lat && th > 0) return "tha_Thai";

        // Latin script language features
        String lower = text.toLowerCase();
        if (lower.contains("¿") || lower.contains("¡") || lower.contains("ñ")) return "spa_Latn";
        if (lower.contains("ß") || lower.contains("ä") || lower.contains("ö") || lower.contains("ü")) return "deu_Latn";
        if (lower.contains("ç") || lower.contains("é") || lower.contains("è") || lower.contains("ê")) return "fra_Latn";
        if (lower.contains("ã") || lower.contains("õ")) return "por_Latn";
        if (lower.contains("ơ") || lower.contains("ư") || lower.contains("đ")) return "vie_Latn";

        return "eng_Latn";
    }

    public synchronized void release() {
        try { if (encoder != null) encoder.close(); } catch (Exception ignored) {}
        try { if (decoder != null) decoder.close(); } catch (Exception ignored) {}
        try { if (env != null) env.close(); } catch (Exception ignored) {}
        ready = false;
        instance = null;
    }
}
