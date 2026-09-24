package com.eonmux.excalibur.api.embedded;

import java.util.List;
import java.util.Objects;

/**
 * One chunk a search returned.
 *
 * @param chunkId The identifier ingestion assigned to the chunk,
 *                {@code <sourceId>:<documentId>:chunk:<n>} - the one the HTTP API returns
 * @param score   How well it matched. Comparable only within one answer: a similarity on the
 *                vector route, a BM25 score on the lexical one and a sum of reciprocal ranks on
 *                the hybrid one
 * @param signals The signals that returned it, best rank first: {@code vector}, {@code bm25}
 * @param text    The chunk's text, or null when storage no longer holds it
 */
public record SearchHit(String chunkId, double score, List<String> signals, String text) {

    /**
     * @throws NullPointerException if chunkId or signals is null
     */
    public SearchHit {
        Objects.requireNonNull(chunkId, "chunkId cannot be null");
        signals = List.copyOf(signals);
    }
}
