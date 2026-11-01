package com.eonmux.excalibur.ocr.pdf;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The bidirectional-text helpers of pdfium's text page: {@code CFX_BidiString}, which splits a
 * line into runs of one direction and decides the line's own direction, and the mirroring and
 * normalization pdfium applies to characters it reverses.
 *
 * <p>Directions come from Java's Unicode bidi classes, which pdfium's own tables also follow.
 * Normalization of reversed characters is NFKC, which equals pdfium's table for every
 * right-to-left letter except a few rare presentation forms; an isolated Arabic mark, which NFKC
 * gives a leading space, keeps only the mark as pdfium's table does.</p>
 */
final class Bidi {

    /** pdfium's {@code CFX_BidiChar::Direction}. */
    enum Direction { NEUTRAL, LEFT, RIGHT, LEFT_WEAK }

    /**
     * A run of characters of one direction.
     *
     * @param start     First index
     * @param count     Length, possibly 0
     * @param direction Its direction
     */
    record Segment(int start, int count, Direction direction) {
    }

    /**
     * A line split into runs, in the order pdfium visits them (reversed for a right-to-left line).
     *
     * @param segments  The runs
     * @param direction The line's direction: {@link Direction#LEFT} or {@link Direction#RIGHT}
     */
    record Line(List<Segment> segments, Direction direction) {
    }

    private static final Map<Integer, Integer> MIRRORS = mirrors(
        "()<>[]{}«»‹›⁅⁆⁽⁾₍₎∈∋"
            + "≤≥≪≫⊂⊃⊆⊇⟨⟩《》〈〉"
            + "「」『』【】（）［］｛｝");

    private Bidi() {
        throw new AssertionError("Static methods only");
    }

    private static Map<Integer, Integer> mirrors(String pairs) {
        Map<Integer, Integer> m = new java.util.HashMap<>();
        for (int i = 0; i + 1 < pairs.length(); i += 2) {
            m.put((int) pairs.charAt(i), (int) pairs.charAt(i + 1));
            m.put((int) pairs.charAt(i + 1), (int) pairs.charAt(i));
        }
        return Collections.unmodifiableMap(m);
    }

    /** The direction pdfium gives a character: {@code CFX_BidiChar::AppendChar}. */
    static Direction direction(int unit) {
        if (unit < 0 || unit > Character.MAX_CODE_POINT) {
            return Direction.NEUTRAL;
        }
        return switch (Character.getDirectionality(unit)) {
            case Character.DIRECTIONALITY_LEFT_TO_RIGHT -> Direction.LEFT;
            case Character.DIRECTIONALITY_ARABIC_NUMBER, Character.DIRECTIONALITY_EUROPEAN_NUMBER,
                 Character.DIRECTIONALITY_NONSPACING_MARK,
                 Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR,
                 Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR,
                 Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR,
                 Character.DIRECTIONALITY_BOUNDARY_NEUTRAL -> Direction.LEFT_WEAK;
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                 Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> Direction.RIGHT;
            default -> Direction.NEUTRAL;
        };
    }

    /**
     * Splits a line into runs: {@code CFX_BidiString(str, auto_order=true)}. A run starts at
     * every change of direction, so empty runs occur (the neutral run before a line's first
     * character, for one); the line reads right to left when more runs go right than left, and
     * then its runs are reversed.
     *
     * @param units The line
     * @return Its runs and direction
     */
    static Line split(int[] units) {
        List<Segment> order = new ArrayList<>();
        int start = 0;
        int count = 0;
        Direction current = Direction.NEUTRAL;
        for (int u : units) {
            Direction d = direction(u);
            if (d != current) {
                order.add(new Segment(start, count, current));
                start += count;
                count = 0;
                current = d;
            }
            count++;
        }
        if (count > 0) {
            order.add(new Segment(start, count, current));
        }
        long right = order.stream().filter(s -> s.direction() == Direction.RIGHT).count();
        long left = order.stream().filter(s -> s.direction() == Direction.LEFT).count();
        if (right > left) {
            Collections.reverse(order);
            return new Line(order, Direction.RIGHT);
        }
        return new Line(order, Direction.LEFT);
    }

    /**
     * A line read right to left from the first, as pdfium's viewer-preference override
     * ({@code SetOverallDirectionRight}) makes it.
     */
    static Line rightToLeft(Line line) {
        if (line.direction() == Direction.RIGHT) {
            return line;
        }
        List<Segment> order = new ArrayList<>(line.segments());
        Collections.reverse(order);
        return new Line(order, Direction.RIGHT);
    }

    /** The mirror image of a bracket or similar, else the character: {@code GetMirrorChar}. */
    static int mirror(int unit) {
        return MIRRORS.getOrDefault(unit, unit);
    }

    /**
     * pdfium's {@code GetUnicodeNormalization}: compatibility forms to their plain letters.
     *
     * @param unit The character
     * @return The units it becomes, the character itself when it has no other form
     */
    static int[] normalize(int unit) {
        int u = unit & 0xFFFF;
        if (Character.isSurrogate((char) u)) {
            return new int[] {u};
        }
        String s = Normalizer.normalize(String.valueOf((char) u), Normalizer.Form.NFKC);
        if (s.length() > 1 && s.charAt(0) == ' ' && u != ' ') {
            s = s.substring(1);
        }
        return s.chars().toArray();
    }
}
