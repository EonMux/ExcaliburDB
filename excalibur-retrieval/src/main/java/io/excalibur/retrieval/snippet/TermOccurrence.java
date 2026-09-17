package io.excalibur.retrieval.snippet;

/**
 * One place a query term appears in a passage.
 *
 * <p>Offsets are in characters of the text the occurrence was found in, so a window cut out of
 * that text has to rebase them before handing them to anyone: a highlight measured against a
 * string the caller was never sent marks the wrong words.</p>
 *
 * @param start  Index of the first character of the occurrence
 * @param length How many characters it spans
 * @param term   The query term, lower-cased, that this run of text matched
 */
public record TermOccurrence(int start, int length, String term) {

    /**
     * Checks the occurrence describes a real span of a real term.
     */
    public TermOccurrence {
        if (start < 0) {
            throw new IllegalArgumentException("start must not be negative: " + start);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }
        if (term == null || term.isEmpty()) {
            throw new IllegalArgumentException("term must not be empty");
        }
    }

    /**
     * The index one past the last character of this occurrence.
     *
     * @return The exclusive end offset
     */
    public int end() {
        return start + length;
    }

    /**
     * The same occurrence, measured against a window that starts at {@code windowStart}.
     *
     * @param windowStart Offset the window begins at, in the text this occurrence is measured in
     * @return The rebased occurrence
     * @throws IllegalArgumentException if the occurrence begins before the window
     */
    public TermOccurrence rebasedTo(int windowStart) {
        return new TermOccurrence(start - windowStart, length, term);
    }
}
