// Ported from Indra OCR 1.1.2, textlayer.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pdf;

import com.eonmux.excalibur.ocr.text.PythonText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Cuts a page's text into runs: Indra's {@code extract_runs}.
 *
 * <p>Characters come from pdfium's text page in content order. A run ends at each line break
 * pdfium generated, at a character that is not at a quarter turn, and where the quarter turn
 * changes. A character is upright when its baseline, taken through the page-to-render map, runs
 * left to right once the page is turned by {@code turn}. Boxes go to the rendered frame, turned by
 * {@code frame}; characters whose centre falls outside the page are dropped.</p>
 */
final class RunExtractor {

    /** The runs of a page and the counts behind them. */
    record Extraction(List<TextLine> runs, TextStats stats) {
    }

    private final PdfPage page;
    private final int turn;
    private final int frame;
    private final List<Integer> chars = new ArrayList<>();
    private final List<double[]> raw = new ArrayList<>();
    private final List<Integer> breaks = new ArrayList<>();
    private final List<Integer> qs = new ArrayList<>();
    private final int[] quarters = new int[4];
    private int nChars;
    private int nRotated;
    private int nBad;

    private RunExtractor(PdfPage page, int turn, int frame) {
        this.page = page;
        this.turn = turn;
        this.frame = frame;
    }

    /**
     * The runs of a page.
     *
     * @param page  The page
     * @param text  Its text page
     * @param turn  Quarter turns that make the page upright
     * @param frame Quarter turns of the frame the boxes are given in
     * @return The runs and their statistics
     */
    static Extraction extract(PdfPage page, TextPage text, int turn, int frame) {
        RunExtractor ex = new RunExtractor(page, turn, frame);
        ex.readChars(text);
        TextStats stats = TextStats.counts(ex.nChars, ex.nRotated, ex.nBad,
            List.of(ex.quarters[0], ex.quarters[1], ex.quarters[2], ex.quarters[3]));
        if (ex.chars.isEmpty()) {
            return new Extraction(List.of(), stats);
        }
        return ex.cut(stats);
    }

    /** The character loop of {@code extract_runs}. */
    private void readChars(TextPage text) {
        Affine a = page.deviceAffine();
        double l00 = a.a00();
        double l01 = -a.a01();
        double l10 = -a.a10();
        double l11 = a.a11();
        Map<Float, Integer> upright = new HashMap<>();
        int n = text.count();
        int curQ = 0;
        for (int i = 0; i < n; i++) {
            int u = text.unicode(i);
            int u2 = u >= 0xD800 && u <= 0xDBFF && i + 1 < n ? text.unicode(i + 1) : 0;
            TextLayers.CodePoint cp = TextLayers.codePoint(u, u2);
            if (cp.usedNext()) {
                i++;
            }
            int c = cp.codePoint();
            if (c == '\r' || c == '\n') {
                breaks.add(chars.size());
                continue;
            }
            if (c == 0x02) {
                c = '-';
            }
            int index = cp.usedNext() ? i - 1 : i;
            if (!PyChars.isSpace(c)) {
                nChars++;
                nBad += PyChars.isBad(c) ? 1 : 0;
                float angle = text.angle(index);
                Integer q = upright.computeIfAbsent(angle, k -> quarterOf(k, l00, l01, l10, l11));
                if (q >= 0) {
                    quarters[q]++;
                }
                if (q != turn) {
                    nRotated++;
                }
                if (q < 0) {
                    breaks.add(chars.size());
                    continue;
                }
                curQ = Math.floorMod(q - turn, 4);
            }
            chars.add(c);
            qs.add(curQ);
            FxRect b = text.box(index);
            raw.add(new double[] {b.left(), b.bottom(), b.right(), b.top()});
        }
    }

    /** The quarter turn of a baseline angle on the rendered page, or -1 for none. */
    private static int quarterOf(float angle, double l00, double l01, double l10, double l11) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        Integer q = TextLayers.quarter(l00 * cos + l01 * sin, l10 * cos + l11 * sin);
        return q == null ? -1 : q;
    }

    /** The box arithmetic and run cutting of {@code extract_runs}. */
    private Extraction cut(TextStats counts) {
        int n = chars.size();
        Affine a = page.deviceAffine();
        double w = page.width();
        double h = page.height();
        double[][] px = new double[n][4];
        double[][] py = new double[n][4];
        PageBox[] boxes = new PageBox[n];
        double[] cx = new double[n];
        double[] cy = new double[n];
        boolean[] visible = new boolean[n];
        int offPage = 0;
        for (int i = 0; i < n; i++) {
            double[] b = raw.get(i);
            double[] xs = {b[0], b[2], b[0], b[2]};
            double[] ys = {b[1], b[1], b[3], b[3]};
            for (int k = 0; k < 4; k++) {
                px[i][k] = a.a00() * xs[k] + a.a01() * ys[k] + a.a02();
                py[i][k] = a.a10() * xs[k] + a.a11() * ys[k] + a.a12();
            }
            boxes[i] = bounds(px[i], py[i]);
            cx[i] = (boxes[i].x0() + boxes[i].x1()) / 2;
            cy[i] = (boxes[i].y0() + boxes[i].y1()) / 2;
            visible[i] = cx[i] >= 0 && cx[i] <= w && cy[i] >= 0 && cy[i] <= h;
            offPage += visible[i] ? 0 : 1;
        }
        int[] steps = steps(boxes, cx, cy);
        TextStats stats = counts.withBoxes(offPage, steps[0], steps[1]);
        if (frame != 0) {
            for (int i = 0; i < n; i++) {
                double[] tx = new double[4];
                double[] ty = new double[4];
                for (int k = 0; k < 4; k++) {
                    double[] t = TextLayers.turnFrame(px[i][k], py[i][k], w, h, frame);
                    tx[k] = t[0];
                    ty[k] = t[1];
                }
                boxes[i] = bounds(tx, ty);
            }
        }
        return new Extraction(runs(boxes, visible), stats);
    }

    private static PageBox bounds(double[] xs, double[] ys) {
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < 4; k++) {
            x0 = Math.min(x0, xs[k]);
            y0 = Math.min(y0, ys[k]);
            x1 = Math.max(x1, xs[k]);
            y1 = Math.max(y1, ys[k]);
        }
        return new PageBox(x0, y0, x1, y1);
    }

    /**
     * Steps between neighbouring upright characters of a run that go down (vertical writing)
     * or across (horizontal), in the rendered frame.
     */
    private int[] steps(PageBox[] boxes, double[] cx, double[] cy) {
        int n = chars.size();
        boolean[] ok = new boolean[Math.max(n - 1, 0)];
        java.util.Arrays.fill(ok, true);
        for (int b : breaks) {
            if (b > 0 && b < n) {
                ok[b - 1] = false;
            }
        }
        int down = 0;
        int right = 0;
        for (int i = 0; i < n - 1; i++) {
            boolean turned = qs.get(i) != 0 || qs.get(i + 1) != 0;
            boolean space = PyChars.isSpace(chars.get(i)) || PyChars.isSpace(chars.get(i + 1));
            if (!ok[i] || turned || space) {
                continue;
            }
            double hgt = Math.max(boxes[i].y1() - boxes[i].y0(), 1e-3);
            double dx = cx[i + 1] - cx[i];
            double dy = cy[i + 1] - cy[i];
            if (Math.abs(dx) > 3 * hgt || Math.abs(dy) > 3 * hgt) {
                continue;
            }
            if (dy > 0.3 * hgt && Math.abs(dx) < 0.3 * hgt) {
                down++;
            }
            if (Math.abs(dx) > 0.3 * hgt && Math.abs(dy) < 0.3 * hgt) {
                right++;
            }
        }
        return new int[] {down, right};
    }

    /** Cuts the characters into runs at breaks and turns. */
    private List<TextLine> runs(PageBox[] boxes, boolean[] visible) {
        int n = chars.size();
        TreeSet<Integer> cuts = new TreeSet<>(breaks);
        for (int i = 1; i < n; i++) {
            if (!qs.get(i).equals(qs.get(i - 1))) {
                cuts.add(i);
            }
        }
        cuts.add(n);
        List<TextLine> runs = new ArrayList<>();
        int start = 0;
        for (int end : cuts) {
            List<RunChar> seg = new ArrayList<>();
            for (int i = start; i < end; i++) {
                if (visible[i]) {
                    seg.add(new RunChar(chars.get(i), boxes[i]));
                }
            }
            int q = start < qs.size() ? qs.get(start) : 0;
            start = end;
            TextLine run = run(seg, q, runs.size());
            if (run != null) {
                runs.add(run);
            }
        }
        return runs;
    }

    private TextLine run(List<RunChar> seg, int quarter, int seq) {
        List<PageBox> ink = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (RunChar c : seg) {
            text.appendCodePoint(c.codePoint());
            if (!PyChars.isSpace(c.codePoint())) {
                ink.add(c.box());
            }
        }
        if (ink.isEmpty()) {
            return null;
        }
        double x0 = Double.POSITIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY;
        double y1 = Double.NEGATIVE_INFINITY;
        for (PageBox v : ink) {
            x0 = Math.min(x0, v.x0());
            y0 = Math.min(y0, v.y0());
            x1 = Math.max(x1, v.x1());
            y1 = Math.max(y1, v.y1());
        }
        int q = quarter;
        if (frame != turn && q == 0 && ink.size() > 1) {
            PageBox first = ink.getFirst();
            PageBox last = ink.getLast();
            double dx = Math.abs((last.x0() + last.x1()) / 2 - (first.x0() + first.x1()) / 2);
            double dy = Math.abs((last.y0() + last.y1()) / 2 - (first.y0() + first.y1()) / 2);
            if (dy > 2 * dx) {
                q = 1;
            }
        }
        return new TextLine(new PageBox(x0, y0, x1, y1), PythonText.collapseSpaces(text.toString()),
            seg, q, seq);
    }
}
