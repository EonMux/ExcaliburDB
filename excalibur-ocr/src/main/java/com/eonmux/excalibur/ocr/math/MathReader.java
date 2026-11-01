// Ported from Indra OCR 1.1.2, math.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.math;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.array.NumpyFloat32;
import com.eonmux.excalibur.ocr.array.NumpyOps;
import com.eonmux.excalibur.ocr.image.GrayImage;
import com.eonmux.excalibur.ocr.image.InkMap;
import com.eonmux.excalibur.ocr.json.Json;
import com.eonmux.excalibur.ocr.recognize.LineBatches;
import com.eonmux.excalibur.ocr.recognize.LineCrops;
import com.eonmux.excalibur.ocr.runtime.OnnxModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lines that hold math, read as text with inline LaTeX: Indra's {@code MathReader}.
 *
 * <p>A classifier finds the lines; MathRec reads a taller crop of each with an attention
 * decoder, one token per step, its keys and values cached between steps. The CTC recognizer
 * reads one symbol per column, so it cannot write a fraction or a sum with limits.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe: the sessions may be run from many threads at once.</p>
 */
public final class MathReader implements AutoCloseable {

    /** Classifier probability above which a line is read as math. */
    public static final double MATH_MIN = 0.5;

    /**
     * A reading replaces a line only at this mean token probability or more: on print the model
     * has not seen it writes confident-looking LaTeX that is wrong, at low probability.
     */
    public static final double MATH_MIN_CONF = 0.9;

    /** A line this far under {@link #MATH_MIN_CONF} after eight tokens stops decoding. */
    static final double EARLY_STOP_MARGIN = 0.1;

    static final int BOS = 1;
    static final int EOS = 2;
    static final int N_SPECIAL = 4;

    /** Lines of one batch at most. */
    static final int BATCH = 64;

    /** The model files, besides {@code math_meta.json}. */
    public static final List<String> FILES = List.of("mathcls.onnx", "math_enc.onnx",
        "math_dec_init.onnx", "math_dec_step.onnx");

    private final OnnxModel cls;
    private final OnnxModel enc;
    private final OnnxModel init;
    private final OnnxModel step;
    private final int height;
    private final int maxTokens;
    private final int layers;
    private final int heads;
    private final int headDim;
    private final List<String> itos;

    private MathReader(OnnxModel[] models, Map<String, Object> meta) {
        this.cls = models[0];
        this.enc = models[1];
        this.init = models[2];
        this.step = models[3];
        Map<String, Object> cfg = castMap(meta.get("config"));
        this.height = intValue(cfg, "height");
        this.maxTokens = intValue(cfg, "max_tokens");
        this.layers = intValue(cfg, "dec_layers");
        this.heads = intValue(cfg, "dec_heads");
        this.headDim = intValue(cfg, "channels") / heads;
        List<String> tokens = new ArrayList<>(List.of("", "", "", ""));
        for (Object t : (List<?>) meta.get("tokens")) {
            tokens.add((String) t);
        }
        this.itos = List.copyOf(tokens);
    }

    /**
     * Loads the math models from a directory.
     *
     * @param dir The model directory, holding {@link #FILES} and {@code math_meta.json}
     * @return The reader
     * @throws IOException if a file is missing or cannot be loaded
     */
    public static MathReader load(Path dir) throws IOException {
        Map<String, Object> meta;
        try {
            meta = Json.parseObject(Files.readString(dir.resolve("math_meta.json")));
        } catch (IllegalArgumentException e) {
            throw new IOException("math_meta.json cannot be read: " + e.getMessage(), e);
        }
        OnnxModel[] models = new OnnxModel[FILES.size()];
        try {
            for (int i = 0; i < models.length; i++) {
                models[i] = OnnxModel.load(dir.resolve(FILES.get(i)), 1);
            }
            return new MathReader(models, meta);
        } catch (IOException | RuntimeException e) {
            for (OnnxModel model : models) {
                if (model != null) {
                    model.close();
                }
            }
            if (e instanceof IOException io) {
                throw io;
            }
            throw new IOException("The math models cannot be loaded: " + e.getMessage(), e);
        }
    }

    /**
     * Per line crop - the recognizer's crop - whether it holds math.
     *
     * @param crops The crops
     * @return Per crop, whether to read it as math
     */
    public boolean[] flags(List<InkMap> crops) {
        boolean[] out = new boolean[crops.size()];
        int[] widths = crops.stream().mapToInt(InkMap::width).toArray();
        for (int[] idx : LineBatches.widthBatches(widths, BATCH)) {
            LineBatches.Batch batch = LineBatches.pad(pick(crops, idx));
            float[] p = cls.run(Map.of("x", batch.x(), "widths", batch.widths())).getFirst()
                .floats();
            for (int k = 0; k < idx.length; k++) {
                out[idx[k]] = p[k] > MATH_MIN;
            }
        }
        return out;
    }

    /**
     * Reads lines as text with inline LaTeX.
     *
     * @param gray  The page
     * @param boxes The lines' boxes
     * @return Per line, its text and mean token probability
     */
    public List<Reading> read(GrayImage gray, float[][] boxes) {
        List<InkMap> crops = LineCrops.cropLines(gray, boxes, height, 1, null);
        Reading[] out = new Reading[crops.size()];
        Arrays.fill(out, new Reading("", 0.0));
        int[] widths = crops.stream().mapToInt(InkMap::width).toArray();
        // Few, wide batches: each is one decoding loop of up to max_tokens steps, and a step
        // costs about the same for 8 lines as for 40.
        for (int[] idx : LineBatches.widthBatches(widths, BATCH, 4.0, LineBatches.MIN_WIDTH,
                16L * 64 * 640)) {
            Reading[] read = decode(LineBatches.pad(pick(crops, idx)));
            for (int k = 0; k < idx.length; k++) {
                out[idx[k]] = read[k];
            }
        }
        return List.of(out);
    }

    private Reading[] decode(LineBatches.Batch batch) {
        Map<String, NdArray> encoded = enc.run(
            Map.of("x", batch.x(), "widths", batch.widths()), List.of("memory", "mask"));
        Map<String, NdArray> cross = init.run(only(init, Map.of(
            "memory", encoded.get("memory"), "mask", encoded.get("mask"))),
            List.of("cross_k", "cross_v"));
        int b = batch.widths().shape()[0];
        NdArray selfK = new NdArray("f4", new int[] {layers, b, heads, 0, headDim},
            new float[0]);
        NdArray selfV = selfK;
        long[] tok = new long[b];
        Arrays.fill(tok, BOS);
        List<List<Integer>> seqs = new ArrayList<>();
        List<List<Double>> probs = new ArrayList<>();
        for (int k = 0; k < b; k++) {
            seqs.add(new ArrayList<>());
            probs.add(new ArrayList<>());
        }
        boolean[] done = new boolean[b];
        for (int t = 0; t < maxTokens - 1; t++) {
            Map<String, NdArray> feed = new HashMap<>();
            feed.put("token", new NdArray("i8", new int[] {b}, tok.clone()));
            feed.put("pos", new NdArray("i8", new int[] {}, new long[] {t}));
            feed.put("self_k", selfK);
            feed.put("self_v", selfV);
            feed.put("cross_k", cross.get("cross_k"));
            feed.put("cross_v", cross.get("cross_v"));
            feed.put("mask", encoded.get("mask"));
            List<NdArray> res = step.run(only(step, feed));
            float[] logits = res.get(0).floats();
            selfK = res.get(1);
            selfV = res.get(2);
            int classes = logits.length / b;
            boolean all = true;
            for (int k = 0; k < b; k++) {
                int arg = argmax(logits, k * classes, classes);
                tok[k] = arg;
                if (!done[k]) {
                    probs.get(k).add((double) topProbability(logits, k * classes, classes,
                        logits[k * classes + arg]));
                    if (arg == EOS) {
                        done[k] = true;
                    } else if (probs.get(k).size() >= 8
                            && mean(probs.get(k)) < MATH_MIN_CONF - EARLY_STOP_MARGIN) {
                        done[k] = true;
                    } else if (arg >= N_SPECIAL) {
                        seqs.get(k).add(arg);
                    }
                }
                all &= done[k];
            }
            if (all) {
                break;
            }
        }
        Reading[] out = new Reading[b];
        for (int k = 0; k < b; k++) {
            List<String> tokens = seqs.get(k).stream().map(itos::get).toList();
            out[k] = new Reading(MathText.detokenize(tokens),
                probs.get(k).isEmpty() ? 0.0 : mean(probs.get(k)));
        }
        return out;
    }

    /** numpy's {@code argmax}: the first of the largest. */
    private static int argmax(float[] row, int from, int n) {
        int best = 0;
        for (int i = 1; i < n; i++) {
            if (row[from + i] > row[from + best]) {
                best = i;
            }
        }
        return best;
    }

    /** The softmax probability of the largest logit, in float32 as numpy computes it. */
    private static float topProbability(float[] row, int from, int n, float max) {
        float[] e = new float[n];
        for (int i = 0; i < n; i++) {
            e[i] = NumpyFloat32.exp(row[from + i] - max);
        }
        return 1f / NumpyOps.sum(e, 0, n);
    }

    private static double mean(List<Double> values) {
        return NumpyOps.mean(values.stream().mapToDouble(Double::doubleValue).toArray());
    }

    /** Only the inputs a session declares: exports drop inputs they do not read. */
    private static Map<String, NdArray> only(OnnxModel model, Map<String, NdArray> feed) {
        Map<String, NdArray> out = new HashMap<>();
        feed.forEach((name, value) -> {
            if (model.inputNames().contains(name)) {
                out.put(name, value);
            }
        });
        return out;
    }

    private static List<InkMap> pick(List<InkMap> crops, int[] idx) {
        List<InkMap> out = new ArrayList<>(idx.length);
        for (int i : idx) {
            out.add(crops.get(i));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("math_meta.json has no config");
        }
        return (Map<String, Object>) value;
    }

    private static int intValue(Map<String, Object> cfg, String key) {
        if (!(cfg.get(key) instanceof Number n)) {
            throw new IllegalArgumentException("math_meta.json's config has no " + key);
        }
        return n.intValue();
    }

    @Override
    public void close() {
        cls.close();
        enc.close();
        init.close();
        step.close();
    }

    /**
     * A line read as math.
     *
     * @param text Its text with inline LaTeX
     * @param conf The mean probability of its tokens
     */
    public record Reading(String text, double conf) {
    }
}
