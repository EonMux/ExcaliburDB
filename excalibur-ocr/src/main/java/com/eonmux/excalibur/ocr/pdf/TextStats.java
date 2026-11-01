// Ported from Indra OCR 1.1.2, textlayer.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.pdf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The counts behind a text-layer verdict: Indra's {@code TextLayer.stats} dict.
 *
 * <p>A field is null where Indra's dict has no such key: all of them for a layer rejected as an
 * OCR layer before it was read, the box statistics for a page with no characters, the turn and
 * frame before {@code read_text_layer} records them, and the shares of the tests that were not
 * reached. {@link #asMap()} gives the dict itself.</p>
 *
 * @param chars     Characters that are not whitespace
 * @param rotated   Of those, the ones not upright after the turn read at
 * @param bad       Of those, the ones that show a broken font map
 * @param quarters  Characters at each quarter turn on the rendered page, four counts
 * @param offPage   Characters whose centre lies outside the rendered page
 * @param steps     Steps between neighbouring upright characters: {@code [down, right]}
 * @param turn      Quarter turns the page was read at
 * @param frame     Quarter turns of the frame the boxes are in
 * @param visualRtl Share of right-to-left letter pairs that step left to right, three decimals
 * @param noInk     Share of runs with no ink under them, four decimals
 * @param vertical  Whether the page was read as vertical writing (set by the document reader)
 */
public record TextStats(Integer chars, Integer rotated, Integer bad, List<Integer> quarters,
                        Integer offPage, List<Integer> steps, Integer turn, Integer frame,
                        Double visualRtl, Double noInk, Boolean vertical) {

    /** No statistics: Indra's empty dict. */
    public static final TextStats NONE = new TextStats(null, null, null, null, null, null, null,
        null, null, null, null);

    public TextStats {
        quarters = quarters == null ? null : List.copyOf(quarters);
        steps = steps == null ? null : List.copyOf(steps);
    }

    /**
     * The counts of a page's characters, before any box statistics.
     *
     * @param chars    Characters that are not whitespace
     * @param rotated  Characters not upright
     * @param bad      Characters of a broken font map
     * @param quarters Counts per quarter turn
     * @return The statistics
     */
    static TextStats counts(int chars, int rotated, int bad, List<Integer> quarters) {
        return new TextStats(chars, rotated, bad, quarters, null, null, null, null, null, null,
            null);
    }

    TextStats withBoxes(int offPage, int down, int right) {
        return new TextStats(chars, rotated, bad, quarters, offPage, List.of(down, right), turn,
            frame, visualRtl, noInk, vertical);
    }

    TextStats withTurnAndFrame(int turn, int frame) {
        return new TextStats(chars, rotated, bad, quarters, offPage, steps, turn, frame,
            visualRtl, noInk, vertical);
    }

    TextStats withVisualRtl(double share) {
        return new TextStats(chars, rotated, bad, quarters, offPage, steps, turn, frame, share,
            noInk, vertical);
    }

    TextStats withNoInk(double share) {
        return new TextStats(chars, rotated, bad, quarters, offPage, steps, turn, frame,
            visualRtl, share, vertical);
    }

    /**
     * The statistics with another turn, the frame unchanged: {@code stats["turn"] = k}, as Indra's
     * document reader records the turn of a page it read with its rotation removed.
     *
     * @param turn Quarter turns, 0 to 3
     * @return The statistics
     */
    public TextStats withTurn(int turn) {
        if (turn < 0 || turn > 3) {
            throw new IllegalArgumentException("turn must be 0 to 3, got " + turn);
        }
        return new TextStats(chars, rotated, bad, quarters, offPage, steps, turn, frame,
            visualRtl, noInk, vertical);
    }

    /**
     * The statistics marked as vertical writing or not: {@code stats["vertical"] = True}.
     *
     * @param value Whether the page was read as vertical writing
     * @return The statistics
     */
    public TextStats withVertical(boolean value) {
        return new TextStats(chars, rotated, bad, quarters, offPage, steps, turn, frame,
            visualRtl, noInk, value);
    }

    /**
     * {@code stats.get("turn", 0)}.
     *
     * @return The turn the page was read at
     */
    public int turnOrZero() {
        return turn == null ? 0 : turn;
    }

    /**
     * {@code stats.get("frame", turn)}.
     *
     * @return The frame of the layer's boxes
     */
    public int frameOrTurn() {
        return frame == null ? turnOrZero() : frame;
    }

    /**
     * {@code bool(stats.get("vertical"))}.
     *
     * @return Whether the page was read as vertical writing
     */
    public boolean isVertical() {
        return vertical != null && vertical;
    }

    /**
     * {@code stats.get("chars", 0)}.
     *
     * @return The character count, 0 when absent
     */
    public int charCount() {
        return chars == null ? 0 : chars;
    }

    /**
     * {@code stats.get("quarters", [0, 0, 0, 0])}.
     *
     * @return The counts per quarter turn
     */
    public List<Integer> quarterCounts() {
        return quarters == null ? List.of(0, 0, 0, 0) : quarters;
    }

    /**
     * {@code stats.get("steps", (0, 0))}.
     *
     * @return {@code [down, right]}
     */
    public List<Integer> stepCounts() {
        return steps == null ? List.of(0, 0) : steps;
    }

    /**
     * The statistics as Indra's dict, keys in the order Indra sets them.
     *
     * @return The present entries
     */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "chars", chars);
        put(map, "rotated", rotated);
        put(map, "bad", bad);
        put(map, "quarters", quarters);
        put(map, "off_page", offPage);
        put(map, "steps", steps);
        put(map, "turn", turn);
        put(map, "frame", frame);
        put(map, "visual_rtl", visualRtl);
        put(map, "no_ink", noInk);
        put(map, "vertical", vertical);
        return map;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
