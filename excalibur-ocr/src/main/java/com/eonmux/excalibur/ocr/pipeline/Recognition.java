// Ported from Indra OCR 1.1.2, pipeline.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pipeline;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.array.NumpyOps;
import com.eonmux.excalibur.ocr.image.GrayImage;
import com.eonmux.excalibur.ocr.image.InkMap;
import com.eonmux.excalibur.ocr.lm.BeamSearch;
import com.eonmux.excalibur.ocr.math.MathReader;
import com.eonmux.excalibur.ocr.recognize.LineBatches;
import com.eonmux.excalibur.ocr.recognize.LineCrops;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Reads detected lines: Indra's {@code OrtOCR.recognize}, {@code line_groups} and
 * {@code box_groups}.
 *
 * <p>Each line goes to the recognizer of its script group; a line the classifier is not sure of
 * is read by the base recognizer as well, and the more confident reading wins. A recognizer's
 * unsure lines are read again by beam search with its language model. With the math models, a
 * line that holds math is read as text with inline LaTeX.</p>
 */
final class Recognition {

    /** A line under this confidence gets no text: the recognizer reads rules and boxes as marks. */
    static final double MIN_CONF = 0.5;

    /** A line goes to one recognizer when the classifier is surer than this. */
    static final double ROUTE_SURE = 0.95;

    /** Lines of one batch at most. */
    static final int BATCH = 64;

    private final OcrModels models;
    private final MathReader math;

    /**
     * @param models The OCR models
     * @param math   The math reader, or null to read math as text
     */
    Recognition(OcrModels models, MathReader math) {
        this.models = models;
        this.math = math;
    }

    /**
     * What each line says.
     *
     * @param gray     The page, upright
     * @param boxes    The lines' boxes
     * @param vertical Whether the page is a vertical one turned a quarter turn: every line to the
     *                 CJK recognizer
     * @return Per line, its text, confidence and tokens; empty under {@link #MIN_CONF}
     */
    List<Reading> recognize(GrayImage gray, float[][] boxes, boolean vertical) {
        int n = boxes.length;
        int window = models.window;
        boolean[] dark = window > 1 ? LineCrops.lineDarkness(gray, boxes) : null;
        List<InkMap> crops = LineCrops.cropLines(gray, boxes, models.height, 1, dark);
        List<InkMap> tall = window > 1
            ? LineCrops.cropLines(gray, boxes, models.height, window, dark) : crops;
        Reading[] out = new Reading[n];
        Arrays.fill(out, Reading.EMPTY);
        List<Route> routes = routes(crops, vertical);
        double[] bestConf = new double[n];
        Arrays.fill(bestConf, -1.0);
        for (Route route : routes) {
            int[] widths = Arrays.stream(route.lines()).map(j -> crops.get(j).width()).toArray();
            for (int[] part : LineBatches.widthBatches(widths, BATCH)) {
                int[] idx = Arrays.stream(part).map(i -> route.lines()[i]).toArray();
                read(route, route.recognizer().isBase() ? tall : crops, crops, boxes, idx,
                    bestConf, out);
            }
        }
        if (math != null && n > 0) {
            readMath(gray, boxes, crops, out);
        }
        return List.of(out);
    }

    /** Which recognizer reads which lines, base first. */
    private List<Route> routes(List<InkMap> crops, boolean vertical) {
        int[] all = new int[crops.size()];
        Arrays.setAll(all, i -> i);
        OcrModels.Recognizer cjk = models.group("cjk");
        if (vertical && cjk != null) {
            // A vertical page's lines are CJK columns, characters on their side.
            return List.of(new Route(cjk, all, true));
        }
        if (models.script == null || crops.isEmpty()) {
            return List.of(new Route(models.base, all, false));
        }
        Groups groups = lineGroups(crops);
        List<Route> routes = new ArrayList<>();
        routes.add(new Route(models.base, select(all, j -> groups.names()[j].equals("base")
            || !groups.sure()[j]), false));
        for (Map.Entry<String, OcrModels.Recognizer> group : models.groups.entrySet()) {
            routes.add(new Route(group.getValue(),
                select(all, j -> groups.names()[j].equals(group.getKey())), true));
        }
        return routes;
    }

    private void read(Route route, List<InkMap> src, List<InkMap> crops, float[][] boxes,
                      int[] idx, double[] bestConf, Reading[] out) {
        OcrModels.Recognizer rec = route.recognizer();
        LineBatches.Batch batch = LineBatches.pad(pick(src, idx));
        boolean useLm = rec.lm() != null;
        List<String> outputs = new ArrayList<>(OcrModels.OUT_GREEDY);
        if (useLm) {
            outputs.addAll(OcrModels.OUT_LM);
        }
        Map<String, NdArray> res = rec.model().run(
            Map.of("x", batch.x(), "widths", batch.widths()), outputs);
        NdArray ids = res.get("ids");
        long[] best = ids.longs();
        float[] pmax = res.get("conf").floats();
        long[] lengths = res.get("lengths").longs();
        int steps = ids.shape()[1];
        int[] xShape = batch.x().shape();
        double stride = (double) xShape[xShape.length - 1] / Math.max(steps, 1);
        for (int k = 0; k < idx.length; k++) {
            int j = idx[k];
            int len = (int) lengths[k];
            int base = k * steps;
            List<Integer> kept = new ArrayList<>();
            for (int t = 0; t < len; t++) {
                long id = best[base + t];
                if (id != 0 && (t == 0 || id != best[base + t - 1])) {
                    kept.add(t);
                }
            }
            double x0 = boxes[j][0];
            double y0 = boxes[j][1];
            double x1 = boxes[j][2];
            double y1 = boxes[j][3];
            double h = Math.max(1.0, y1 - y0);
            double ix0 = Math.floor(x0 - LineCrops.PAD_X * h);
            double cw = Math.ceil(x1 + LineCrops.PAD_X * h) - ix0;
            double f = cw / crops.get(j).width();
            int[] tokenIds = kept.stream().mapToInt(t -> (int) best[base + t]).toArray();
            int[] at = kept.stream().mapToInt(Integer::intValue).toArray();
            double conf = 0.0;
            if (!kept.isEmpty()) {
                float[] pm = new float[kept.size()];
                for (int i = 0; i < pm.length; i++) {
                    pm[i] = pmax[base + kept.get(i)];
                }
                conf = NumpyOps.mean(pm);
            }
            if (useLm && MIN_CONF <= conf && conf < rec.gate()) {
                BeamSearch.Reading beam = BeamSearch.search(row(res.get("blank"), k, len),
                    rows(res.get("topv"), k, len), longRows(res.get("topi"), k, len), rec.lm(),
                    rec.beam());
                tokenIds = beam.ids();
                at = beam.steps();
            }
            List<Token> tokens = new ArrayList<>(tokenIds.length);
            for (int i = 0; i < tokenIds.length; i++) {
                double x = ix0 + (at[i] + 0.5) * stride * f;
                tokens.add(route.asChars()
                    ? Token.ofText(rec.vocab().chars().get(tokenIds[i] - 1), x)
                    : Token.ofId(tokenIds[i], x));
            }
            if (conf <= bestConf[j]) {
                continue;
            }
            bestConf[j] = conf;
            out[j] = conf >= MIN_CONF ? new Reading(rec.vocab().decode(tokenIds), conf, tokens)
                : new Reading("", conf, List.of());
        }
    }

    /** Lines with math, read by the math reader when it is sure of a reading with math. */
    private void readMath(GrayImage gray, float[][] boxes, List<InkMap> crops, Reading[] out) {
        boolean[] flags = math.flags(crops);
        int[] lines = select(java.util.stream.IntStream.range(0, flags.length).toArray(),
            j -> flags[j]);
        if (lines.length == 0) {
            return;
        }
        float[][] picked = Arrays.stream(lines).mapToObj(j -> boxes[j]).toArray(float[][]::new);
        List<MathReader.Reading> reads = math.read(gray, picked);
        for (int i = 0; i < lines.length; i++) {
            MathReader.Reading r = reads.get(i);
            // One token holds the whole line: it is never split.
            if (r.text().contains("$") && r.conf() >= MathReader.MATH_MIN_CONF) {
                int j = lines[i];
                double xc = (double) (boxes[j][0] + boxes[j][2]) / 2;
                out[j] = new Reading(r.text(), 1.0, List.of(Token.ofText(r.text(), xc)));
            }
        }
    }

    /**
     * The script group of each line on a page, from its box: every line is {@code base} without
     * a script classifier.
     *
     * @param gray  The page
     * @param boxes The lines' boxes
     * @return The groups, and whether the classifier is sure of each
     */
    Groups boxGroups(GrayImage gray, float[][] boxes) {
        if (models.script == null || boxes.length == 0) {
            String[] names = new String[boxes.length];
            Arrays.fill(names, "base");
            return new Groups(names, new boolean[boxes.length]);
        }
        boolean[] dark = models.window > 1 ? LineCrops.lineDarkness(gray, boxes) : null;
        return lineGroups(LineCrops.cropLines(gray, boxes, models.height, 1, dark));
    }

    /**
     * Per line crop, the group whose recognizer reads it, and whether the classifier is surer of
     * it than {@link #ROUTE_SURE}. A group without its recognizer goes to {@code base}.
     *
     * @param crops The lines' crops
     * @return The groups
     */
    Groups lineGroups(List<InkMap> crops) {
        String[] names = new String[crops.size()];
        Arrays.fill(names, "base");
        boolean[] sure = new boolean[crops.size()];
        Arrays.fill(sure, true);
        int[] widths = crops.stream().mapToInt(InkMap::width).toArray();
        for (int[] idx : LineBatches.widthBatches(widths, BATCH)) {
            LineBatches.Batch batch = LineBatches.pad(pick(crops, idx));
            NdArray p = models.script.run(Map.of("x", batch.x(), "widths", batch.widths()))
                .getFirst();
            float[] v = p.floats();
            for (int k = 0; k < idx.length; k++) {
                String g;
                double pk;
                if (p.shape().length == 1) {
                    g = v[k] > 0.5f ? "cjk" : "base";
                    pk = Math.max(v[k], 1.0 - v[k]);
                } else {
                    int classes = p.shape()[1];
                    int arg = 0;
                    for (int c = 1; c < classes; c++) {
                        if (v[k * classes + c] > v[k * classes + arg]) {
                            arg = c;
                        }
                    }
                    g = models.scriptGroups.get(arg);
                    pk = v[k * classes + arg];
                }
                names[idx[k]] = models.groups.containsKey(g) ? g : "base";
                sure[idx[k]] = pk > ROUTE_SURE;
            }
        }
        return new Groups(names, sure);
    }

    private static float[] row(NdArray a, int k, int len) {
        int steps = a.shape()[1];
        return Arrays.copyOfRange(a.floats(), k * steps, k * steps + len);
    }

    private static float[][] rows(NdArray a, int k, int len) {
        int steps = a.shape()[1];
        int width = a.shape()[2];
        float[] v = a.floats();
        float[][] out = new float[len][];
        for (int t = 0; t < len; t++) {
            int from = (k * steps + t) * width;
            out[t] = Arrays.copyOfRange(v, from, from + width);
        }
        return out;
    }

    private static long[][] longRows(NdArray a, int k, int len) {
        int steps = a.shape()[1];
        int width = a.shape()[2];
        long[] v = a.longs();
        long[][] out = new long[len][];
        for (int t = 0; t < len; t++) {
            int from = (k * steps + t) * width;
            out[t] = Arrays.copyOfRange(v, from, from + width);
        }
        return out;
    }

    private static int[] select(int[] all, java.util.function.IntPredicate keep) {
        return Arrays.stream(all).filter(keep).toArray();
    }

    private static List<InkMap> pick(List<InkMap> crops, int[] idx) {
        List<InkMap> out = new ArrayList<>(idx.length);
        for (int i : idx) {
            out.add(crops.get(i));
        }
        return out;
    }

    /**
     * Lines' script groups.
     *
     * @param names Per line, the group whose recognizer reads it
     * @param sure  Per line, whether the classifier is sure of it
     */
    record Groups(String[] names, boolean[] sure) {
    }

    /** One recognizer and the lines it reads; {@code asChars}: its tokens are characters. */
    private record Route(OcrModels.Recognizer recognizer, int[] lines, boolean asChars) {
    }
}
