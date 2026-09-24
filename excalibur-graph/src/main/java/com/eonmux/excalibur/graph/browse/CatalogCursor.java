package com.eonmux.excalibur.graph.browse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The cursors the catalog hands out, and reads back.
 *
 * <h2>Keys, not offsets</h2>
 * <p>A cursor holds the sort key of the last item a page returned, never its position. Positions
 * move whenever the graph is rebuilt; the keys are entity and relation ids, which never do, and
 * names. So a cursor carried across a rebuild resumes after the item it was issued for rather
 * than at whatever now sits at the old offset - nothing skipped, nothing repeated, except among
 * edges that agree on relation and neighbour, which are ordered by when they were staged.</p>
 *
 * <p>Opaque to a caller: base64url, so it survives a query string unescaped, over a short
 * text form that is this class's to change.</p>
 */
final class CatalogCursor {

    private static final String ENTITY = "n";
    private static final String EDGE = "e";

    private CatalogCursor() {
    }

    /**
     * Where an entity listing resumes: after this folded name, and after this id among entities
     * that share it.
     *
     * @param folded The last entity's name, as the listing orders it
     * @param id     The last entity's id
     */
    record NameKey(String folded, long id) {
    }

    /**
     * Where an edge listing resumes.
     *
     * @param reverse  Whether the last edge came from the incoming half
     * @param relation Its relation id
     * @param neighbor Its other endpoint
     * @param skip     How many edges with that relation and neighbour have been returned
     */
    record EdgeKey(boolean reverse, int relation, int neighbor, long skip) {
    }

    static String entity(NameKey key) {
        return encode(ENTITY + ":" + key.id() + ":" + key.folded());
    }

    static String edge(EdgeKey key) {
        return encode(EDGE + ":" + (key.reverse() ? "i" : "o") + ":" + key.relation() + ":"
            + key.neighbor() + ":" + key.skip());
    }

    /**
     * Reads an entity listing's cursor.
     *
     * @param cursor The cursor, or null for the first page
     * @return The key, or null for the first page
     * @throws IllegalArgumentException if it is not a cursor an entity listing issued
     */
    static NameKey entity(String cursor) {
        if (cursor == null) {
            return null;
        }
        String[] parts = decode(cursor).split(":", 3);
        if (parts.length != 3 || !ENTITY.equals(parts[0])) {
            throw malformed();
        }
        return new NameKey(parts[2], number(parts[1]));
    }

    /**
     * Reads an edge listing's cursor.
     *
     * @param cursor The cursor, or null for the first page
     * @return The key, or null for the first page
     * @throws IllegalArgumentException if it is not a cursor an edge listing issued
     */
    static EdgeKey edge(String cursor) {
        if (cursor == null) {
            return null;
        }
        String[] parts = decode(cursor).split(":", -1);
        if (parts.length != 5 || !EDGE.equals(parts[0])
            || !("i".equals(parts[1]) || "o".equals(parts[1]))) {
            throw malformed();
        }
        long relation = number(parts[2]);
        long neighbor = number(parts[3]);
        long skip = number(parts[4]);
        if (relation > Integer.MAX_VALUE || neighbor > Integer.MAX_VALUE) {
            throw malformed();
        }
        return new EdgeKey("i".equals(parts[1]), (int) relation, (int) neighbor, skip);
    }

    private static String encode(String text) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw malformed();
        }
    }

    private static long number(String text) {
        try {
            long value = Long.parseLong(text);
            if (value < 0) {
                throw malformed();
            }
            return value;
        } catch (NumberFormatException e) {
            throw malformed();
        }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException(
            "'cursor' is not one this listing issued; pass back the next_cursor of the page "
                + "before, unchanged");
    }
}
