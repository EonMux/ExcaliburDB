package com.eonmux.excalibur.retrieval.snippet;

import java.util.List;

/**
 * A window onto a passage, and where it came from.
 *
 * <p>The text is a verbatim substring of the passage rather than a decorated one: no ellipsis
 * and no markup, so that {@link #offset()} plus an index into {@link #text()} still points at
 * the same character of the chunk the window was cut from. A caller that wants an ellipsis can
 * add one from {@link #truncated()}; a caller handed one cannot take it back out again without
 * every offset beside it becoming a guess.</p>
 *
 * @param text      The window, verbatim
 * @param offset    Where the window begins in the passage it was cut from
 * @param truncated Whether any of the passage was left out of the window
 * @param matches   Where the query's terms fall inside {@link #text()}, rebased to it
 */
public record Snippet(String text, int offset, boolean truncated, List<TermOccurrence> matches) {

    /**
     * Checks the window describes a real region with locatable matches.
     */
    public Snippet {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
        matches = List.copyOf(matches);
        for (TermOccurrence match : matches) {
            if (match.end() > text.length()) {
                throw new IllegalArgumentException("match at " + match.start() + " runs past the "
                    + "window of " + text.length() + " characters");
            }
        }
    }
}
