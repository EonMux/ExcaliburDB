package com.eonmux.excalibur.retrieval.lexical.native_;

/**
 * What a document key is allowed to contain.
 *
 * <p>Keys are stored one per line beside a segment and read back positionally: line N names
 * document N. A key holding a line break would not merely corrupt its own record, it would shift
 * every document after it by one, so every hit in that segment would report a different document's
 * key. Nothing downstream could detect that - the index is intact, the search is correct, and only
 * the answer is wrong - which is why the character is refused where it enters rather than escaped
 * where it is written.</p>
 */
final class DocumentKeys {

    private DocumentKeys() {
    }

    /** Returns the key, or throws if it cannot be stored. */
    static String require(String key) {
        if (key == null) {
            throw new IllegalArgumentException("document key must not be null");
        }
        if (key.isEmpty()) {
            throw new IllegalArgumentException("document key must not be empty");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                throw new IllegalArgumentException(
                    "document key must not contain a line break or tab: " + escaped(key));
            }
        }
        return key;
    }

    private static String escaped(String key) {
        StringBuilder sb = new StringBuilder(key.length() + 8);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            switch (c) {
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
