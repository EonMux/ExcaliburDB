// Ported from Indra OCR 1.1.2, layout_post.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.layout;

import com.eonmux.excalibur.ocr.array.NumpyOps;
import com.eonmux.excalibur.ocr.text.PythonText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A page's markdown from its ordered lines and the order model's layout heads: Indra's
 * {@code page_markdown}.
 *
 * <p>Walking the reading order, a new block starts wherever the same-block logit between
 * consecutive lines is not positive. Each block is written by its class: a section header as
 * {@code ## } and its text; a list one item per line; a table as HTML assembled from its cell
 * lines; page headers and footers not at all unless kept; anything else as one paragraph, a word
 * split by a hyphen at a line end joined again. Picture regions are written as
 * {@code ![](picture-<k>)} before the first block below them that they overlap horizontally.</p>
 *
 * <p>Arithmetic follows numpy's on Indra's float32 arrays, so a comparison lands on the same
 * side.</p>
 */
public final class PageMarkdown {

    /** List markers that are one character. */
    private static final String MARKERS = "•-–▪◦*·→";

    private PageMarkdown() {
        throw new AssertionError("Static methods only");
    }

    /**
     * Markdown for one page.
     *
     * @param boxes         Line boxes {@code [x0, y0, x1, y1, ...]}
     * @param texts         Line texts
     * @param order         Reading order, line indices
     * @param cls           Block class logits, {@code [n, BlockClasses.COUNT]} row-major
     * @param same          Same-block logits, {@code [n, n]} row-major
     * @param keepFurniture Whether page headers and footers are written
     * @param cells         Per line, its table cells when it holds several; null for none
     * @param pictures      Picture regions {@code [x0, y0, x1, y1]}; null or empty for none
     * @return The markdown
     */
    public static String of(float[][] boxes, List<String> texts, int[] order, float[] cls,
                            float[] same, boolean keepFurniture, Map<Integer, List<Cell>> cells,
                            float[][] pictures) {
        int n = boxes.length;
        List<String> parts = new ArrayList<>();
        List<float[]> partBoxes = new ArrayList<>();
        for (List<Integer> blk : splitBlocks(order, same, n)) {
            int before = parts.size();
            blockPart(parts, blk, boxes, texts, cls, keepFurniture, cells);
            if (parts.size() > before) {
                partBoxes.add(union(boxes, blk));
            }
        }
        if (pictures != null && pictures.length > 0) {
            parts = withPictures(parts, partBoxes, pictures);
        }
        List<String> written = parts.stream().filter(p -> !p.isEmpty()).toList();
        return String.join("\n\n", written);
    }

    /** Pictures placed before the first part below each that overlaps it horizontally. */
    private static List<String> withPictures(List<String> parts, List<float[]> partBoxes,
                                             float[][] pictures) {
        Map<Integer, List<String>> slots = new HashMap<>();
        for (int k = 0; k < pictures.length; k++) {
            float[] p = pictures[k];
            float cy = (p[1] + p[3]) / 2f;
            int at = parts.size();
            for (int j = 0; j < partBoxes.size(); j++) {
                float[] b = partBoxes.get(j);
                if ((b[1] + b[3]) / 2f > cy && Math.min(p[2], b[2]) > Math.max(p[0], b[0])) {
                    at = j;
                    break;
                }
            }
            slots.computeIfAbsent(at, x -> new ArrayList<>()).add("![](picture-" + k + ")");
        }
        List<String> merged = new ArrayList<>();
        for (int j = 0; j <= parts.size(); j++) {
            merged.addAll(slots.getOrDefault(j, List.of()));
            if (j < parts.size()) {
                merged.add(parts.get(j));
            }
        }
        return merged;
    }

    /**
     * Runs of the reading order with a positive same-block logit between neighbours: Indra's
     * {@code split_blocks}.
     *
     * @param order The reading order
     * @param same  Same-block logits, {@code [n, n]} row-major
     * @param n     How many lines there are
     * @return The blocks, each in reading order
     */
    static List<List<Integer>> splitBlocks(int[] order, float[] same, int n) {
        List<List<Integer>> blocks = new ArrayList<>();
        for (int i : order) {
            if (!blocks.isEmpty() && same[blocks.getLast().getLast() * n + i] > 0) {
                blocks.getLast().add(i);
            } else {
                List<Integer> block = new ArrayList<>();
                block.add(i);
                blocks.add(block);
            }
        }
        return blocks;
    }

    /** Appends one block's markdown, or nothing for furniture left out. */
    private static void blockPart(List<String> parts, List<Integer> blk, float[][] boxes,
                                  List<String> texts, float[] cls, boolean keepFurniture,
                                  Map<Integer, List<Cell>> cells) {
        int c = blockClass(blk, cls);
        List<String> textsB = blk.stream().map(texts::get).toList();
        if ((c == BlockClasses.HEADER || c == BlockClasses.FOOTER) && !keepFurniture) {
            return;
        }
        if (textsB.stream().allMatch(t -> wholeMath(PythonText.strip(t)))) {
            // Display equations.
            parts.add(String.join("\n", textsB.stream().map(t -> {
                String s = PythonText.strip(t);
                return "$$" + s.substring(1, s.length() - 1) + "$$";
            }).toList()));
            return;
        }
        if (c == BlockClasses.SECTION) {
            parts.add("## " + joinLines(textsB));
        } else if (c == BlockClasses.TABLE && blk.size() > 1) {
            List<float[]> cb = new ArrayList<>();
            List<String> ct = new ArrayList<>();
            for (int i : blk) {
                List<Cell> row = cells == null ? null : cells.get(i);
                if (row == null || row.isEmpty()) {
                    cb.add(new float[] {boxes[i][0], boxes[i][1], boxes[i][2], boxes[i][3]});
                    ct.add(texts.get(i));
                } else {
                    for (Cell cell : row) {
                        cb.add(cell.box());
                        ct.add(cell.text());
                    }
                }
            }
            parts.add(tableHtml(cb.toArray(float[][]::new), ct));
        } else if (c == BlockClasses.LIST) {
            List<List<String>> items = new ArrayList<>();
            for (String t : textsB) {
                if (items.isEmpty() || isItem(t)) {
                    items.add(new ArrayList<>(List.of(t)));
                } else {
                    items.getLast().add(t);
                }
            }
            parts.add(String.join("\n", items.stream().map(PageMarkdown::joinLines).toList()));
        } else {
            parts.add(joinLines(textsB));
        }
    }

    /**
     * A block's class: the largest of its lines' summed logits, summed in float32 line by line
     * as numpy sums along the first axis.
     */
    private static int blockClass(List<Integer> blk, float[] cls) {
        int classes = BlockClasses.COUNT;
        float[] sum = new float[classes];
        for (int i : blk) {
            for (int c = 0; c < classes; c++) {
                sum[c] += cls[i * classes + c];
            }
        }
        return NumpyOps.argmax(sum);
    }

    /** Whether a stripped line is one formula: {@code ^\$[^$]+\$$}. */
    static boolean wholeMath(String s) {
        return s.length() >= 3 && s.charAt(0) == '$' && s.charAt(s.length() - 1) == '$'
            && s.substring(1, s.length() - 1).indexOf('$') < 0;
    }

    /**
     * Whether a line starts a list item: a marker followed by text or alone on its line,
     * Indra's {@code ^\s*([•\-–▪◦*·→]|\(?\d{1,3}[.)]|\(?[a-zA-Z][.)])(\s|$)} with Python's
     * {@code \s}, {@code \d} and {@code $}.
     *
     * @param t The line
     * @return Whether it starts an item
     */
    static boolean isItem(String t) {
        int[] cps = t.codePoints().toArray();
        int i = 0;
        while (i < cps.length && PythonText.isSpace(cps[i])) {
            i++;
        }
        int end = marker(cps, i);
        if (end < 0) {
            return false;
        }
        // (\s|$): a space, the end, or a final newline before it.
        return end == cps.length || PythonText.isSpace(cps[end]);
    }

    /** Where the marker starting at {@code i} ends, or -1 for none. */
    private static int marker(int[] cps, int i) {
        if (i >= cps.length) {
            return -1;
        }
        if (MARKERS.indexOf(cps[i]) >= 0) {
            return i + 1;
        }
        int j = cps[i] == '(' ? i + 1 : i;
        int digits = 0;
        while (j + digits < cps.length && digits < 3 && PythonText.isDecimal(cps[j + digits])) {
            digits++;
        }
        if (digits > 0) {
            int k = j + digits;
            return k < cps.length && (cps[k] == '.' || cps[k] == ')') ? k + 1 : -1;
        }
        if (j < cps.length && isAsciiLetter(cps[j]) && j + 1 < cps.length
                && (cps[j + 1] == '.' || cps[j + 1] == ')')) {
            return j + 2;
        }
        return -1;
    }

    private static boolean isAsciiLetter(int cp) {
        return cp >= 'a' && cp <= 'z' || cp >= 'A' && cp <= 'Z';
    }

    /**
     * The lines of one paragraph joined: {@code tumori-} and {@code genesis} become
     * {@code tumorigenesis}, and a break between two CJK characters takes no space. Indra's
     * {@code join_lines}.
     *
     * @param texts The lines
     * @return The paragraph
     */
    static String joinLines(List<String> texts) {
        StringBuilder out = new StringBuilder();
        for (String raw : texts) {
            String t = PythonText.strip(raw);
            if (t.isEmpty()) {
                continue;
            }
            int first = t.codePointAt(0);
            int len = out.codePointCount(0, out.length());
            boolean hyphen = len > 1 && out.charAt(out.length() - 1) == '-'
                && PythonText.isAlpha(out.codePointBefore(out.length() - 1))
                && PythonText.isAlpha(first) && PythonText.isLower(first);
            if (hyphen) {
                out.setLength(out.length() - 1);
                out.append(t);
            } else if (!out.isEmpty() && cjk(out.codePointBefore(out.length())) && cjk(first)) {
                out.append(t);
            } else {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(t);
            }
        }
        return out.toString();
    }

    /**
     * Han, kana, CJK punctuation and fullwidth forms: written without spaces. Hangul is not:
     * Korean separates words with spaces.
     */
    static boolean cjk(int o) {
        return 0x3000 <= o && o <= 0x30FF || 0x3400 <= o && o <= 0x9FFF
            || 0xF900 <= o && o <= 0xFAFF || 0xFF00 <= o && o <= 0xFFEF
            || 0x20000 <= o && o <= 0x2FA1F;
    }

    /**
     * An HTML table from cell lines, one text line per cell: Indra's {@code table_html}.
     *
     * <p>A first row that is one line across most of the table is a title, written before the
     * table as text. The first remaining row is the header.</p>
     *
     * @param boxes Cell boxes {@code [x0, y0, x1, y1]}
     * @param texts Cell texts
     * @return The table
     */
    static String tableHtml(float[][] boxes, List<String> texts) {
        List<List<Integer>> rows = rows(boxes);
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        for (float[] b : boxes) {
            minX = Math.min(minX, b[0]);
            maxX = Math.max(maxX, b[2]);
        }
        float spanX = maxX - minX;
        int width = rows.stream().mapToInt(List::size).max().orElseThrow();
        int head = rows.getFirst().getFirst();
        if (rows.size() > 2 && rows.getFirst().size() == 1 && width > 1
                && boxes[head][2] - boxes[head][0] > 0.6f * spanX) {
            List<float[]> keepBoxes = new ArrayList<>();
            List<String> keepTexts = new ArrayList<>();
            for (int k = 0; k < texts.size(); k++) {
                if (k != head) {
                    keepBoxes.add(boxes[k]);
                    keepTexts.add(texts.get(k));
                }
            }
            return texts.get(head) + "\n\n" + tableHtml(keepBoxes.toArray(float[][]::new),
                keepTexts);
        }
        List<List<Integer>> full = rows.stream().filter(r -> r.size() == width).toList();
        rows = mergeWrapped(rows, boxes);
        // Column bands from the rows with the most cells: those rows rarely span.
        float[][] bands = new float[width][2];
        for (int c = 0; c < width; c++) {
            float[] x0 = new float[full.size()];
            float[] x1 = new float[full.size()];
            for (int j = 0; j < full.size(); j++) {
                x0[j] = boxes[full.get(j).get(c)][0];
                x1[j] = boxes[full.get(j).get(c)][2];
            }
            bands[c][0] = NumpyOps.median(x0);
            bands[c][1] = NumpyOps.median(x1);
        }
        // Each band widened to the midpoints between neighbours, so every x falls in one band.
        double[] edges = new double[width + 1];
        edges[0] = Double.NEGATIVE_INFINITY;
        for (int c = 0; c < width - 1; c++) {
            edges[c + 1] = (bands[c][1] + bands[c + 1][0]) / 2f;
        }
        edges[width] = Double.POSITIVE_INFINITY;
        List<String> out = new ArrayList<>();
        out.add("<table>");
        for (int ri = 0; ri < rows.size(); ri++) {
            String tag = ri == 0 && rows.size() > 1 ? "th" : "td";
            out.add(row(rows.get(ri), boxes, texts, bands, edges, width, tag));
        }
        out.add("</table>");
        return String.join("\n", out);
    }

    /** One table row's HTML. */
    private static String row(List<Integer> r, float[][] boxes, List<String> texts,
                              float[][] bands, double[] edges, int width, String tag) {
        Map<Integer, Span> cells = new TreeMap<>();
        List<Integer> sorted = new ArrayList<>(r);
        sorted.sort(Comparator.<Integer>comparingDouble(k -> boxes[k][1])
            .thenComparingDouble(k -> boxes[k][0]));
        for (int k : sorted) {
            float x0 = boxes[k][0];
            float x1 = boxes[k][2];
            int first = NumpyOps.searchsortedRight(edges, x0) - 1;
            int last = NumpyOps.searchsortedRight(edges, x1) - 1;
            // A cell spans every band it covers by more than half of the band.
            List<Integer> span = new ArrayList<>();
            for (int c = Math.max(first, 0); c <= Math.min(last, width - 1); c++) {
                if (Math.min(x1, bands[c][1]) - Math.max(x0, bands[c][0])
                        > 0.5f * (bands[c][1] - bands[c][0])) {
                    span.add(c);
                }
            }
            if (span.isEmpty()) {
                span.add(Math.min(Math.max(first, 0), width - 1));
            }
            int c0 = span.getFirst();
            Span prev = cells.get(c0);
            cells.put(c0, prev == null ? new Span(span.size(), texts.get(k))
                : new Span(Math.max(prev.n(), span.size()), prev.text() + " " + texts.get(k)));
        }
        StringBuilder tds = new StringBuilder("<tr>");
        int c = 0;
        while (c < width) {
            Span cell = cells.get(c);
            if (cell != null) {
                String attr = cell.n() > 1 ? " colspan=\"" + cell.n() + "\"" : "";
                tds.append('<').append(tag).append(attr).append('>').append(escape(cell.text()))
                    .append("</").append(tag).append('>');
                c += cell.n();
            } else {
                tds.append('<').append(tag).append("></").append(tag).append('>');
                c++;
            }
        }
        return tds.append("</tr>").toString();
    }

    /**
     * Cell lines grouped into rows by vertical centre, top to bottom, each left to right:
     * Indra's {@code _rows}.
     */
    static List<List<Integer>> rows(float[][] boxes) {
        int n = boxes.length;
        float[] cy = new float[n];
        float[] heights = new float[n];
        for (int i = 0; i < n; i++) {
            cy[i] = (boxes[i][1] + boxes[i][3]) / 2f;
            heights[i] = boxes[i][3] - boxes[i][1];
        }
        // numpy compares a float32 with a Python float in float32.
        float near = (float) (0.5 * NumpyOps.median(heights));
        List<List<Integer>> rows = new ArrayList<>();
        for (int i : NumpyOps.argsort(cy)) {
            if (!rows.isEmpty() && Math.abs(cy[i] - mean(cy, rows.getLast())) < near) {
                rows.getLast().add(i);
            } else {
                rows.add(new ArrayList<>(List.of(i)));
            }
        }
        for (List<Integer> row : rows) {
            row.sort(Comparator.comparingDouble(k -> boxes[k][0]));
        }
        return rows;
    }

    private static float mean(float[] values, List<Integer> at) {
        float[] picked = new float[at.size()];
        for (int j = 0; j < picked.length; j++) {
            picked[j] = values[at.get(j)];
        }
        return NumpyOps.mean(picked);
    }

    /**
     * The line rows of wrapped cells joined into one table row: Indra's {@code _merge_wrapped}.
     *
     * <p>A row joins the row above when its gap is under half the table's median row gap and
     * under 0.6 line heights, and it has fewer lines than the row above: a continuation fills
     * only the wrapped columns.</p>
     */
    static List<List<Integer>> mergeWrapped(List<List<Integer>> rows, float[][] boxes) {
        if (rows.size() < 3) {
            return rows;
        }
        float[] heights = new float[boxes.length];
        for (int i = 0; i < boxes.length; i++) {
            heights[i] = boxes[i][3] - boxes[i][1];
        }
        double h = NumpyOps.median(heights);
        double[] gaps = new double[rows.size() - 1];
        for (int j = 0; j < gaps.length; j++) {
            float top = Float.POSITIVE_INFINITY;
            for (int k : rows.get(j + 1)) {
                top = Math.min(top, boxes[k][1]);
            }
            float bottom = Float.NEGATIVE_INFINITY;
            for (int k : rows.get(j)) {
                bottom = Math.max(bottom, boxes[k][3]);
            }
            gaps[j] = top - bottom;
        }
        double med = NumpyOps.median(gaps);
        List<List<Integer>> out = new ArrayList<>();
        out.add(new ArrayList<>(rows.getFirst()));
        List<Integer> prev = rows.getFirst();
        for (int j = 0; j < gaps.length; j++) {
            List<Integer> r = rows.get(j + 1);
            double g = gaps[j];
            if (g < 0.5 * med && g < 0.6 * h && r.size() < prev.size()) {
                out.getLast().addAll(r);
            } else {
                out.add(new ArrayList<>(r));
            }
            prev = r;
        }
        return out;
    }

    /** Python's {@code html.escape(text, quote=False)}. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static float[] union(float[][] boxes, List<Integer> blk) {
        float[] u = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int i : blk) {
            u[0] = Math.min(u[0], boxes[i][0]);
            u[1] = Math.min(u[1], boxes[i][1]);
            u[2] = Math.max(u[2], boxes[i][2]);
            u[3] = Math.max(u[3], boxes[i][3]);
        }
        return u;
    }

    /**
     * One cell of a table line that holds several.
     *
     * @param box  The cell's box {@code [x0, y0, x1, y1]}
     * @param text Its text
     */
    public record Cell(float[] box, String text) {
    }

    /** A table cell: the bands it spans, and its text. */
    private record Span(int n, String text) {
    }
}
