package com.eonmux.excalibur.retrieval.lexical.native_;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one definition of what a term is.
 *
 * <p>Indexing and querying must agree exactly: a term written one way and looked up another simply
 * does not match, and nothing downstream can tell that from a document that genuinely lacks the
 * word. Keeping the rule in one place is what makes that agreement structural rather than a
 * convention two call sites happen to share.</p>
 *
 * <p>Deliberately simple: fold to lower case, split on anything that is not a letter or a digit.
 * That keeps identifiers, error codes and version fragments intact as searchable tokens, which is
 * most of what this corpus is made of, and it costs nothing at 165 GiB.</p>
 */
public final class Tokens {

    /** Longest token kept; beyond this a "word" is a hash, a base64 blob or corruption. */
    public static final int MAX_TERM_CHARS = 64;

    private Tokens() {
        throw new AssertionError("Utility class");
    }

    /** Splits text into terms, in order, including repeats. */
    public static List<String> of(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int start = -1;
        for (int i = 0; i <= text.length(); i++) {
            boolean word = i < text.length()
                && (Character.isLetterOrDigit(text.charAt(i)));
            if (word && start < 0) {
                start = i;
            } else if (!word && start >= 0) {
                if (i - start <= MAX_TERM_CHARS) {
                    out.add(text.substring(start, i).toLowerCase(Locale.ROOT));
                }
                start = -1;
            }
        }
        return out;
    }
}
