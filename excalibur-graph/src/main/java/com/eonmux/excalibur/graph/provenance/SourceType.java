package com.eonmux.excalibur.graph.provenance;

/**
 * What kind of thing an edge was extracted from.
 *
 * <p>Stored as a single byte per edge. The ordinal is the on-disk value, so constants may be
 * appended but never reordered or removed — a store written by an earlier build would otherwise
 * decode every edge's origin as a different one, which is worse than failing to decode it.
 */
public enum SourceType {

    /** Origin was not recorded. Distinct from any real source, so it cannot be mistaken for one. */
    UNKNOWN,

    /** A curated knowledge base statement, e.g. a Wikidata claim. */
    KNOWLEDGE_BASE,

    /** Extracted from the prose of an ingested document. */
    DOCUMENT,

    /** Extracted from a turn of a conversation. */
    CONVERSATION,

    /** Asserted directly through the API rather than extracted. */
    USER_ASSERTED,

    /** Derived by the system from other edges rather than read from a source. */
    INFERRED;

    private static final SourceType[] BY_ORDINAL = values();

    /** The on-disk byte for this source type. */
    public byte toByte() {
        return (byte) ordinal();
    }

    /**
     * Decodes an on-disk byte.
     *
     * <p>An unrecognised value maps to {@link #UNKNOWN} rather than throwing: a store written by a
     * newer build with an extra source type is still readable, and one edge of uncertain origin
     * should not make a whole traversal fail.
     */
    public static SourceType fromByte(byte value) {
        int index = value & 0xFF;
        return index < BY_ORDINAL.length ? BY_ORDINAL[index] : UNKNOWN;
    }
}
