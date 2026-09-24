package com.eonmux.excalibur.graph.provenance;

import java.util.Optional;

/**
 * What kind of thing an edge was extracted from.
 *
 * <p>Stored as a single byte per edge, in the staged triple log, the entity text log, the edge
 * store's {@code source_type} column and a source-scoped erasure request. That byte is each
 * constant's {@link #code()}, fixed here rather than taken from its position, so a constant can be
 * removed without renumbering the ones after it - a store written by an earlier build would
 * otherwise decode every edge's origin as a different one, which is worse than failing to decode
 * it.</p>
 *
 * <h2>Retired codes</h2>
 * <p>Codes {@value #RETIRED_KNOWLEDGE_BASE}, {@value #RETIRED_CONVERSATION} and
 * {@value #RETIRED_INFERRED} once named a curated knowledge-base statement, a conversation turn
 * and a fact the system inferred. Nothing ever produced any of them, so they are gone, and their
 * codes are never given to anything else: a byte left by an earlier build decodes as
 * {@link #UNKNOWN} through {@link #fromByte}, and {@link #isRetired} lets a reader that must not
 * coerce - an erasure log, whose byte chooses what is destroyed - recognise it for what it is.</p>
 */
public enum SourceType {

    /** Origin was not recorded. Distinct from any real source, so it cannot be mistaken for one. */
    UNKNOWN(0),

    /** Extracted from the prose of an ingested document. */
    DOCUMENT(2),

    /**
     * Asserted directly through the API rather than extracted.
     *
     * <p>The source id of such a fact is the fact id {@code POST /v1/graph/facts} handed back, so
     * each assertion is its own source and can be retracted on its own.</p>
     */
    USER_ASSERTED(4);

    /** The code that named a curated knowledge-base statement, retired unused. */
    public static final int RETIRED_KNOWLEDGE_BASE = 1;

    /** The code that named a turn of a conversation, retired unused. */
    public static final int RETIRED_CONVERSATION = 3;

    /** The code that named a fact derived from other edges, retired unused. */
    public static final int RETIRED_INFERRED = 5;

    private static final SourceType[] BY_CODE = byCode();

    private final int code;

    SourceType(int code) {
        this.code = code;
    }

    /**
     * The byte this source type is stored as.
     *
     * @return The code, which never changes once assigned
     */
    public int code() {
        return code;
    }

    /** The on-disk byte for this source type. */
    public byte toByte() {
        return (byte) code;
    }

    /**
     * Decodes an on-disk byte.
     *
     * <p>An unrecognised value maps to {@link #UNKNOWN} rather than throwing: a store written by a
     * newer build with an extra source type is still readable, and one edge of uncertain origin
     * should not make a whole traversal fail. A retired code decodes the same way.
     */
    public static SourceType fromByte(byte value) {
        return decode(value).orElse(UNKNOWN);
    }

    /**
     * Decodes an on-disk byte, saying so when this build does not know it.
     *
     * @param value The stored byte
     * @return The source type, or empty for a retired code or one written by a newer build
     */
    public static Optional<SourceType> decode(byte value) {
        int index = value & 0xFF;
        return index < BY_CODE.length ? Optional.ofNullable(BY_CODE[index]) : Optional.empty();
    }

    /**
     * Whether a stored byte is one of the codes this build retired.
     *
     * @param value The stored byte
     * @return true for {@value #RETIRED_KNOWLEDGE_BASE}, {@value #RETIRED_CONVERSATION} and
     *         {@value #RETIRED_INFERRED}
     */
    public static boolean isRetired(byte value) {
        int code = value & 0xFF;
        return code == RETIRED_KNOWLEDGE_BASE || code == RETIRED_CONVERSATION
            || code == RETIRED_INFERRED;
    }

    private static SourceType[] byCode() {
        int highest = 0;
        for (SourceType type : values()) {
            highest = Math.max(highest, type.code);
        }
        SourceType[] table = new SourceType[highest + 1];
        for (SourceType type : values()) {
            table[type.code] = type;
        }
        return table;
    }
}
