// Ported from Indra OCR 1.1.2, pipeline.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pipeline;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.layout.BlockClasses;
import com.eonmux.excalibur.ocr.layout.OrderFeatures;
import com.eonmux.excalibur.ocr.layout.PageMarkdown;
import com.eonmux.excalibur.ocr.layout.ReadingOrder;
import com.eonmux.excalibur.ocr.text.PythonText;
import com.eonmux.excalibur.ocr.text.Rtl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A page's read lines as markdown: Indra's {@code OrtOCR.assemble} and {@code reading_order}.
 *
 * <p>Lines are cut where the detector joined columns or cells, freed of lone list markers and
 * fill-in rules, put in reading order by the order model, grouped into blocks and classified by
 * its layout heads, and written as markdown. A page that reads right to left is laid out
 * mirrored, so its first column is the right one.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe: it holds no state of its own between pages.</p>
 */
final class Assembly {

    /** A class logit low enough that the class is never chosen. */
    private static final float NEVER = -1e4f;

    private final OcrModels models;
    private final boolean keepFurniture;

    /**
     * @param models        The models: the order model and the base vocabulary
     * @param keepFurniture Whether page headers and footers stay in the markdown
     */
    Assembly(OcrModels models, boolean keepFurniture) {
        this.models = models;
        this.keepFurniture = keepFurniture;
    }

    /**
     * Assembles a page.
     *
     * @param width    The page image's width
     * @param height   The page image's height
     * @param boxes    The lines' boxes, {@code [x0, y0, x1, y1, ...]}
     * @param rec      Per line, its reading
     * @param vertical Whether the page is set in vertical columns, read turned
     * @param pictures Picture regions {@code [x0, y0, x1, y1]}, or null
     * @return The page's markdown, or null without the order model's layout heads, and its
     *         lines in reading order
     */
    PageText assemble(int width, int height, float[][] boxes, List<Reading> rec, boolean vertical,
                      float[][] pictures) {
        boolean rtl = pageRtl(rec);
        LineText lines = new LineText(models.base.vocab(), rtl);
        TidyLines.Lines tidy = split(lines, boxes, rec, rtl);
        float[][] b = tidy.boxes();
        List<Reading> readings = tidy.readings();
        List<String> texts = readings.stream().map(Reading::text).toList();
        Mirror geo = new Mirror(rtl, width);

        Order found = readingOrder(geo.boxes(b), texts, width, height);
        if (vertical && found.layout()) {
            found = xycut(b, found.cls());
        }
        List<String> ordered = new ArrayList<>(found.order().length);
        for (int i : found.order()) {
            ordered.add(texts.get(i));
        }
        String text = String.join("\n", ordered);

        String markdown = null;
        if (found.layout()) {
            Map<Integer, List<PageMarkdown.Cell>> cells = new HashMap<>();
            for (int i = 0; i < b.length; i++) {
                List<PageMarkdown.Cell> row = new ArrayList<>();
                for (LineText.Piece piece : lines.split(b[i], readings.get(i).tokens(),
                        LineText.CELL_GAP, null)) {
                    row.add(new PageMarkdown.Cell(geo.box(piece.box()), piece.text()));
                }
                cells.put(i, row);
            }
            boolean hasPictures = pictures != null && pictures.length > 0;
            markdown = PageMarkdown.of(geo.boxes(b), texts, found.order(), found.cls(),
                found.same(), keepFurniture, cells, hasPictures ? geo.boxes(pictures) : null);
        } else if (b.length < 2) {
            markdown = String.join("\n", texts);
        }
        return new PageText(markdown, text);
    }

    /** Whether most of the page's letters are right-to-left ones. */
    private static boolean pageRtl(List<Reading> rec) {
        int letters = 0;
        int rtl = 0;
        for (Reading r : rec) {
            int[] cps = r.text().codePoints().toArray();
            for (int cp : cps) {
                if (PythonText.isAlpha(cp)) {
                    letters++;
                    rtl += Rtl.isRtl(cp) ? 1 : 0;
                }
            }
        }
        return rtl > letters - rtl;
    }

    /**
     * Lines cut at wide gaps, as text-layer labels cut them ({@link LineText#SPLIT_GAP}, or a
     * column gutter), then tidied.
     */
    private static TidyLines.Lines split(LineText lines, float[][] boxes, List<Reading> rec,
                                         boolean rtl) {
        List<float[]> nb = new ArrayList<>();
        List<Reading> nrec = new ArrayList<>();
        double[][] cuts = boxes.length > 0 ? lines.gutterCuts(boxes, rec) : new double[0][];
        for (int i = 0; i < boxes.length; i++) {
            Reading r = rec.get(i);
            for (LineText.Piece piece : lines.split(boxes[i], r.tokens(), LineText.SPLIT_GAP,
                    cuts[i])) {
                nb.add(Arrays.copyOf(piece.box(), 4));
                nrec.add(new Reading(piece.text(), r.conf(), piece.tokens()));
            }
        }
        return TidyLines.tidy(nb.toArray(float[][]::new), nrec, rtl, lines.spaceId());
    }

    /**
     * Reading order, and the layout heads' class and same-block logits when the order model
     * has them.
     */
    private Order readingOrder(float[][] boxes, List<String> texts, int width, int height) {
        int n = boxes.length;
        if (n < 2 || models.order == null) {
            return new Order(n == 0 ? new int[0] : ReadingOrder.xycut(boxes), null, null);
        }
        boolean[] mask = new boolean[n];
        Arrays.fill(mask, true);
        List<NdArray> outs = models.order.run(Map.of(
            "feats", new NdArray("f4", new int[] {1, n, OrderFeatures.N_FEATS},
                OrderFeatures.of(boxes, width, height, texts)),
            "mask", new NdArray("b1", new int[] {1, n}, mask)));
        float[] a = outs.get(0).floats();
        if (outs.size() >= 3) {
            // Blocks from the same-block head, lines inside a block by geometry, blocks by the
            // order head.
            float[] cls = outs.get(1).floats();
            float[] same = outs.get(2).floats();
            return new Order(ReadingOrder.decodeBlocks(a, same, boxes, cls, n), cls, same);
        }
        return new Order(ReadingOrder.decode(a, n), null, null);
    }

    /**
     * Blocks and order of a vertical page by XY-cut, without grid classes: the order model saw
     * no vertical pages, and turned, their columns of short lines look like one table read row
     * by row across tiers.
     */
    private static Order xycut(float[][] boxes, float[] cls) {
        int n = boxes.length;
        List<int[]> blocks = ReadingOrder.xycutBlocks(boxes);
        int[] order = blocks.stream().flatMapToInt(Arrays::stream).toArray();
        float[] same = new float[n * n];
        Arrays.fill(same, -1f);
        for (int[] block : blocks) {
            for (int i : block) {
                for (int j : block) {
                    same[i * n + j] = 1f;
                }
            }
        }
        float[] masked = cls.clone();
        int classes = BlockClasses.COUNT;
        for (String label : BlockClasses.GRID) {
            int c = BlockClasses.index(label);
            for (int i = 0; i < n; i++) {
                masked[i * classes + c] = NEVER;
            }
        }
        return new Order(order, masked, same);
    }

    /**
     * A reading order, with the layout heads' logits when there are any.
     *
     * @param order Line indices in reading order
     * @param cls   Per line, block class logits, row-major, or null
     * @param same  Per pair of lines, the same-block logit, row-major, or null
     */
    private record Order(int[] order, float[] cls, float[] same) {

        boolean layout() {
            return cls != null;
        }
    }

    /**
     * The page mirrored left to right when it reads right to left, so its first column is the
     * one reading order and markdown see first; the identity otherwise.
     */
    private record Mirror(boolean rtl, float width) {

        float[][] boxes(float[][] boxes) {
            if (!rtl) {
                return boxes;
            }
            float[][] out = new float[boxes.length][];
            for (int i = 0; i < boxes.length; i++) {
                out[i] = box(boxes[i]);
            }
            return out;
        }

        float[] box(float[] b) {
            if (!rtl) {
                return b;
            }
            float[] m = b.clone();
            m[0] = width - b[2];
            m[2] = width - b[0];
            return m;
        }
    }
}
