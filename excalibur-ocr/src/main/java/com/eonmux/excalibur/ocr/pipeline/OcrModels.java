// Ported from Indra OCR 1.1.2, pipeline.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pipeline;

import com.eonmux.excalibur.ocr.json.Json;
import com.eonmux.excalibur.ocr.lm.BeamSearch;
import com.eonmux.excalibur.ocr.lm.NgramLM;
import com.eonmux.excalibur.ocr.model.IndraModels;
import com.eonmux.excalibur.ocr.model.ModelFile;
import com.eonmux.excalibur.ocr.runtime.OnnxModel;
import com.eonmux.excalibur.ocr.text.Vocab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The OCR models of one model directory, loaded: what {@code OrtOCR.__init__} loads.
 *
 * <p>Only the pinned files are read (see {@link IndraModels}): the base recognizer
 * {@code rec.onnx}, never a quantized {@code rec.int8.onnx}, and no {@code mask.onnx}, which the
 * pinned revision does not have. Any other file in the directory is ignored, even one named as
 * a model would be, so what a page reads as never depends on more than the pinned revision. Every session runs on one thread: pages, not operators, are
 * what this port reads in parallel, and an ONNX Runtime session may be run from many threads at
 * once.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable once loaded; the sessions are safe to run concurrently.</p>
 */
final class OcrModels implements AutoCloseable {

    /** Default beam-search weights, for a recognizer {@code meta.json} sets none for. */
    static final double LM_ALPHA = 0.5;
    static final double LM_BETA = 1.5;
    static final double LM_GATE = 0.9;
    static final int LM_BEAM = 8;
    static final double LM_BLANK_SKIP = 0.999;
    static final double LM_CAND_FLOOR = 3e-3;

    /** Recognizer outputs: the greedy reading, and what beam search reads. */
    static final List<String> OUT_GREEDY = List.of("ids", "conf", "lengths");
    static final List<String> OUT_LM = List.of("blank", "topv", "topi");

    /** Recognizer groups, in the script classifier's class order, unless meta.json names them. */
    static final List<String> GROUPS = List.of("base", "cjk", "rtl", "other");

    final OnnxModel det;
    final OnnxModel order;
    final OnnxModel orient;
    final OnnxModel script;
    final Recognizer base;
    final Map<String, Recognizer> groups;
    final List<String> scriptGroups;
    final int longSide;
    final int height;
    final int window;

    private final List<AutoCloseable> owned;

    private OcrModels(Loader l, Map<String, Object> meta, Recognizer base,
                      Map<String, Recognizer> groups, OnnxModel script) {
        this.det = l.det;
        this.order = l.order;
        this.orient = l.orient;
        this.script = script;
        this.base = base;
        this.groups = Collections.unmodifiableMap(groups);
        this.scriptGroups = stringList(meta.getOrDefault("script_groups", GROUPS));
        this.longSide = intValue(meta, "det_long_side");
        this.height = intValue(meta, "rec_height");
        this.window = meta.containsKey("rec_window") ? intValue(meta, "rec_window") : 1;
        this.owned = List.copyOf(l.opened);
    }

    /**
     * Loads every OCR model in a directory.
     *
     * @param dir A directory holding the pinned files, verified
     * @return The models
     * @throws IOException if a model or {@code meta.json} cannot be read
     */
    static OcrModels load(Path dir) throws IOException {
        Loader l = new Loader(dir);
        try {
            Map<String, Object> meta = Json.parseObject(
                Files.readString(need(dir, "meta.json")));
            Map<String, Object> params = meta.get("lm_params") instanceof Map<?, ?> m
                ? castMap(m) : Map.of();
            l.det = l.session("det.onnx", true);
            l.order = l.session("order.onnx", false);
            l.orient = l.session("orient.onnx", false);
            OnnxModel rec = l.session("rec.onnx", true);
            Recognizer base = l.recognizer("base", rec, vocab(meta, "vocab"), "lm.npz",
                params);
            Map<String, Recognizer> groups = new LinkedHashMap<>();
            for (String key : meta.keySet()) {
                if (!key.startsWith("vocab_")) {
                    continue;
                }
                String group = key.substring("vocab_".length());
                OnnxModel model = l.session("rec_" + group + ".onnx", false);
                if (model != null) {
                    groups.put(group, l.recognizer(group, model, vocab(meta, key),
                        "lm_" + group + ".npz", params));
                }
            }
            OnnxModel script = groups.isEmpty() ? null : l.session("script.onnx", false);
            return new OcrModels(l, meta, base, groups, script);
        } catch (IOException | RuntimeException e) {
            l.closeAll();
            if (e instanceof IOException io) {
                throw io;
            }
            throw new IOException("Could not load the OCR models in " + dir + ": "
                + e.getMessage(), e);
        }
    }

    /**
     * The recognizer of a group, or null when the directory has none for it.
     *
     * @param name The group
     * @return Its recognizer
     */
    Recognizer group(String name) {
        return groups.get(name);
    }

    @Override
    public void close() {
        for (AutoCloseable model : owned) {
            try {
                model.close();
            } catch (Exception e) {
                // Native memory released as far as it could be; nothing else depends on it.
            }
        }
    }

    private static Vocab vocab(Map<String, Object> meta, String key) {
        if (!(meta.get(key) instanceof String json)) {
            throw new IllegalArgumentException("meta.json has no vocabulary " + key);
        }
        return Vocab.fromJson(json);
    }

    private static int intValue(Map<String, Object> meta, String key) {
        if (!(meta.get(key) instanceof Number n)) {
            throw new IllegalArgumentException("meta.json has no number " + key);
        }
        return n.intValue();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("meta.json's script_groups is not a list");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            out.add((String) o);
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Path need(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Missing model file " + file);
        }
        return file;
    }

    /**
     * A line recognizer, its vocabulary and, when it has one, its language model.
     *
     * @param name  Its group: {@code base}, or a script group
     * @param model The CTC recognizer
     * @param vocab What its ids mean
     * @param lm    The language model beam search reads its unsure lines with, or null
     * @param beam  The beam search's weights, or null without a language model
     * @param gate  Lines under this confidence are read with the language model
     */
    record Recognizer(String name, OnnxModel model, Vocab vocab, NgramLM lm,
                      BeamSearch.Options beam, double gate) {

        Recognizer {
            Objects.requireNonNull(name);
            Objects.requireNonNull(model);
            Objects.requireNonNull(vocab);
        }

        /** Whether this is the base recognizer, which reads the tall window. */
        boolean isBase() {
            return name.equals("base");
        }
    }

    /** Opens sessions, remembering them so a failure part way closes them all. */
    private static final class Loader {

        private final Path dir;
        /** Every file of the pinned revision; no other is read. */
        private final Set<String> pinned = IndraModels.files(true).stream()
            .map(ModelFile::name).collect(Collectors.toUnmodifiableSet());
        private final List<AutoCloseable> opened = new ArrayList<>();
        private OnnxModel det;
        private OnnxModel order;
        private OnnxModel orient;

        Loader(Path dir) {
            this.dir = dir;
        }

        OnnxModel session(String name, boolean required) throws IOException {
            Path file = dir.resolve(name);
            if (!pinned.contains(name) || !Files.isRegularFile(file)) {
                if (required) {
                    throw new IOException("Missing model file " + file);
                }
                return null;
            }
            OnnxModel model = OnnxModel.load(file, 1);
            opened.add(model);
            return model;
        }

        Recognizer recognizer(String name, OnnxModel model, Vocab vocab, String lmFile,
                              Map<String, Object> params) throws IOException {
            Path file = dir.resolve(lmFile);
            NgramLM lm = null;
            BeamSearch.Options beam = null;
            double gate = LM_GATE;
            if (pinned.contains(lmFile) && Files.isRegularFile(file)
                    && model.outputNames().containsAll(OUT_LM)) {
                Map<String, Object> p = params.get(name) instanceof Map<?, ?> m ? castMap(m)
                    : Map.of();
                lm = NgramLM.load(file);
                beam = new BeamSearch.Options(number(p, "alpha", LM_ALPHA),
                    number(p, "beta", LM_BETA), LM_BEAM, LM_BLANK_SKIP, LM_CAND_FLOOR);
                gate = number(p, "gate", LM_GATE);
            }
            return new Recognizer(name, model, vocab, lm, beam, gate);
        }

        void closeAll() {
            for (AutoCloseable model : opened) {
                try {
                    model.close();
                } catch (Exception e) {
                    // Closing what was opened before the failure; the failure is what matters.
                }
            }
        }

        private static double number(Map<String, Object> p, String key, double fallback) {
            return p.get(key) instanceof Number n ? n.doubleValue() : fallback;
        }
    }
}
