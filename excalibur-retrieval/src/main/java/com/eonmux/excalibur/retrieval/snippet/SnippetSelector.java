package com.eonmux.excalibur.retrieval.snippet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks the part of a passage worth showing for a query.
 *
 * <h2>Why not the beginning</h2>
 * <p>A chunk clipped from its start shows the caller whatever the document opens with, which
 * for anything longer than a paragraph is the part least likely to be why the document ranked.
 * A search result is a claim that this passage answers the query, and the evidence for that
 * claim is the region the query's terms are in.</p>
 *
 * <h2>What "best" means here</h2>
 * <p>The window that covers the most distinct query terms, and among ties the one covering the
 * most occurrences, and among those the earliest. Distinct first because a passage saying one
 * term ten times is weaker evidence than one saying three terms once each - that is the same
 * judgement an inverted index makes when it weights by how many of the query's clauses a
 * document satisfies.</p>
 *
 * <h2>What it will not do</h2>
 * <p>Alter the text. The window is a verbatim substring, so its offset and the offsets of the
 * matches inside it still describe the chunk it came from. Ellipses, highlight markup and
 * escaping all belong to whoever renders it, because each of them is a decision about a format
 * this class cannot see.</p>
 */
public final class SnippetSelector {

    /**
     * Most occurrences one passage is scanned for before the search for a window gives up.
     *
     * <p>The window is chosen by comparing runs of occurrences against each other, so the work
     * grows with how many there are - and how many there are is set by the length of the chunk,
     * which is an ingestion setting, times the number of query terms, which the caller types.
     * Past this many the extra occurrences cannot change which region is densest in any way a
     * reader would notice, and the bound is what stops a pathological chunk costing more than
     * the search that found it.</p>
     */
    public static final int MAX_MATCHES_PER_PASSAGE = 512;

    private SnippetSelector() {
    }

    /**
     * The opening window of a passage, for when there is nothing to centre on.
     *
     * @param text        The passage
     * @param windowChars Most characters the window may hold
     * @return The window, reporting whether anything was left out
     * @throws IllegalArgumentException if text is null or windowChars is not positive
     */
    public static Snippet fromStart(String text, int windowChars) {
        requireWindow(text, windowChars);
        if (text.length() <= windowChars) {
            return new Snippet(text, 0, false, List.of());
        }
        int end = wholeCharacterEnd(text, windowChars);
        return new Snippet(text.substring(0, end), 0, true, List.of());
    }

    /**
     * The window of a passage that best covers the query's terms.
     *
     * @param text        The passage
     * @param occurrences Where the query's terms fall in it, in order, from
     *                    {@link QueryTerms#occurrences}
     * @param windowChars Most characters the window may hold
     * @return The window, with the matches inside it rebased to it
     * @throws IllegalArgumentException if text is null or windowChars is not positive
     */
    public static Snippet around(String text, List<TermOccurrence> occurrences, int windowChars) {
        requireWindow(text, windowChars);
        if (text.length() <= windowChars) {
            return new Snippet(text, 0, false, within(occurrences, 0, text.length()));
        }
        if (occurrences == null || occurrences.isEmpty()) {
            return fromStart(text, windowChars);
        }
        int[] run = densestRun(occurrences, windowChars);
        TermOccurrence first = occurrences.get(run[0]);
        TermOccurrence last = occurrences.get(run[1]);
        int span = Math.max(first.length(), last.end() - first.start());
        int start = clamp(first.start() - Math.max(0, (windowChars - span) / 2),
            text.length() - windowChars);
        start = wholeCharacterStart(text, wordStart(text, start, first.start()));
        int end = wordEnd(text, Math.min(text.length(), start + windowChars),
            Math.min(text.length(), Math.max(last.end(), start + 1)));
        end = wholeCharacterEnd(text, end);
        return new Snippet(text.substring(start, end), start,
            start > 0 || end < text.length(), within(occurrences, start, end));
    }

    /**
     * The run of occurrences a window of this width can cover the most of.
     *
     * @param occurrences Where the query's terms fall, in order
     * @param windowChars Most characters the window may hold
     * @return The first and last index of the chosen run
     */
    private static int[] densestRun(List<TermOccurrence> occurrences, int windowChars) {
        int bestFirst = 0;
        int bestLast = 0;
        int bestDistinct = -1;
        int bestCount = 0;
        for (int i = 0; i < occurrences.size(); i++) {
            Set<String> distinct = new HashSet<>();
            int j = i;
            while (j < occurrences.size()
                    && occurrences.get(j).end() - occurrences.get(i).start() <= windowChars) {
                distinct.add(occurrences.get(j).term());
                j++;
            }
            int count = Math.max(1, j - i);
            if (distinct.size() > bestDistinct
                    || (distinct.size() == bestDistinct && count > bestCount)) {
                bestDistinct = distinct.size();
                bestCount = count;
                bestFirst = i;
                bestLast = Math.max(i, j - 1);
            }
        }
        return new int[]{bestFirst, bestLast};
    }

    /**
     * The occurrences lying wholly inside a window, measured against that window.
     *
     * @param occurrences Where the query's terms fall in the passage
     * @param start       Where the window begins
     * @param end         Where the window ends, exclusive
     * @return The matches a caller can locate in the window it was given
     */
    private static List<TermOccurrence> within(List<TermOccurrence> occurrences, int start,
                                               int end) {
        if (occurrences == null || occurrences.isEmpty()) {
            return List.of();
        }
        List<TermOccurrence> inside = new ArrayList<>();
        for (TermOccurrence occurrence : occurrences) {
            if (occurrence.start() >= start && occurrence.end() <= end) {
                inside.add(occurrence.rebasedTo(start));
            }
        }
        return List.copyOf(inside);
    }

    /**
     * Moves a window's start forward off the middle of a word, when it can be done cheaply.
     *
     * <p>Only as far as the first match, never past it: a window that begins tidily and has
     * dropped the term it was chosen for is not an improvement on one that begins mid-word.</p>
     */
    private static int wordStart(String text, int start, int limit) {
        if (start <= 0) {
            return 0;
        }
        int i = start;
        while (i < limit && !Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i < limit ? i + 1 : start;
    }

    /**
     * Moves a window's end back off the middle of a word, but never before {@code floor}.
     *
     * @param floor The offset the window must still reach, being the end of its last match
     */
    private static int wordEnd(String text, int end, int floor) {
        if (end >= text.length()) {
            return end;
        }
        for (int i = end; i > floor; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return i - 1;
            }
        }
        return end;
    }

    /**
     * Pulls an end offset back off the second half of a surrogate pair.
     *
     * <p>An unpaired surrogate is not encodable and leaves the wire as a literal {@code ?}, so
     * a passage ending in an emoji or a CJK extension character would gain a character it
     * never had.</p>
     */
    private static int wholeCharacterEnd(String text, int end) {
        return end > 0 && end <= text.length() && Character.isHighSurrogate(text.charAt(end - 1))
            ? end - 1
            : end;
    }

    /** Pulls a start offset back onto the first half of a surrogate pair. */
    private static int wholeCharacterStart(String text, int start) {
        return start > 0 && Character.isLowSurrogate(text.charAt(start)) ? start - 1 : start;
    }

    /** Holds a candidate start inside the passage. */
    private static int clamp(int value, int highest) {
        return Math.max(0, Math.min(value, Math.max(0, highest)));
    }

    /** Rejects the arguments a window cannot be cut with. */
    private static void requireWindow(String text, int windowChars) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (windowChars <= 0) {
            throw new IllegalArgumentException("windowChars must be positive: " + windowChars);
        }
    }
}
