// Ported from Indra OCR 1.1.2, lm.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr.lm;

import java.util.Arrays;

/**
 * A beam's token sequence, used as a map key as {@code beam_search} uses tuples: equal when the
 * tokens are, and never changed once made.
 */
final class TokenPrefix {

    /** The empty prefix, where every search starts. */
    static final TokenPrefix EMPTY = new TokenPrefix(new int[0]);

    private final int[] ids;
    private final int hash;

    private TokenPrefix(int[] ids) {
        this.ids = ids;
        this.hash = Arrays.hashCode(ids);
    }

    /** The tokens, as a copy. */
    int[] ids() {
        return ids.clone();
    }

    /** The last token, or -1 (no class) for the empty prefix: Python's {@code prefix[-1]}. */
    int last() {
        return ids.length == 0 ? -1 : ids[ids.length - 1];
    }

    /** This prefix with one more token: Python's {@code prefix + (c,)}. */
    TokenPrefix plus(int c) {
        int[] longer = Arrays.copyOf(ids, ids.length + 1);
        longer[ids.length] = c;
        return new TokenPrefix(longer);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TokenPrefix p && hash == p.hash && Arrays.equals(ids, p.ids);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return Arrays.toString(ids);
    }
}
