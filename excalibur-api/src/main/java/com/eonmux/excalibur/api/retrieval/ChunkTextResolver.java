package com.eonmux.excalibur.api.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Function;

/**
 * Reads the text behind a result identifier, bounded so one request cannot ask for the corpus.
 *
 * <h2>Why retrieval hands back identifiers at all</h2>
 * <p>The indexes store postings and vectors, not text, which is what keeps the corpus from
 * existing twice. Text lives in storage, under the same chunk identifier every index names, and
 * this is the read that goes and gets it. Retrieval stays a ranking problem and content stays one
 * lookup away rather than being copied into every index that might be asked for it.</p>
 *
 * <h2>The bound</h2>
 * <p>A search returns {@code top_k} identifiers and a caller asking for their text is asking for
 * {@code top_k} chunks in one body. Chunk sizes are an ingestion setting rather than a request
 * one, so without a ceiling the response size is set by whoever ingested the corpus and not by
 * whoever is serving it. Text past the bound is clipped, and the clipping is reported: a caller
 * that cannot tell a whole chunk from a clipped one will quote the clipped one as the whole
 * thing. Callers needing the untruncated chunk fetch it by identifier.</p>
 *
 * <h2>What null means</h2>
 * <p>Null text is an answer. Storage no longer holds that chunk - the usual reason being an
 * erasure the vector index has not been compacted for yet, which can still rank an identifier
 * whose content is gone - or the read itself failed, having logged why. Neither is a reason to
 * fail a search that ranked correctly: the result keeps its identifier and its score, and says
 * that the text is not there.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable, and safe for concurrent use when the source it reads through is.</p>
 */
public final class ChunkTextResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkTextResolver.class);

    private final Function<String, String> source;
    private final int maxChars;

    private ChunkTextResolver(Function<String, String> source, int maxChars) {
        this.source = source;
        this.maxChars = maxChars;
    }

    /**
     * A resolver reading through a chunk text source.
     *
     * @param source   Reads a chunk's text by its identifier, answering null when it has none
     * @param maxChars Longest text returned for one chunk, past which it is clipped
     * @return The resolver
     * @throws NullPointerException     if the source is null
     * @throws IllegalArgumentException if maxChars is not positive
     */
    public static ChunkTextResolver over(Function<String, String> source, int maxChars) {
        Objects.requireNonNull(source, "source must not be null");
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive: " + maxChars);
        }
        return new ChunkTextResolver(source, maxChars);
    }

    /**
     * Reads one chunk's text.
     *
     * @param chunkId The identifier a result carries, or null
     * @return The text and whether it was clipped; never null, though its text may be
     */
    public Resolved resolve(String chunkId) {
        if (chunkId == null) {
            return Resolved.ABSENT;
        }
        String text;
        try {
            text = source.apply(chunkId);
        } catch (RuntimeException e) {
            // One unreadable chunk is a result without text, not a failed search. The ranking
            // that produced it was correct and the identifier is still worth returning.
            LOGGER.warn("Could not read the text of chunk {}: {}", chunkId, e.getMessage());
            return Resolved.ABSENT;
        }
        if (text == null) {
            return Resolved.ABSENT;
        }
        if (text.length() <= maxChars) {
            return new Resolved(text, false);
        }
        // Back off one unit rather than cut a surrogate pair in half. An unpaired surrogate is
        // not encodable: it becomes a literal '?' on the way out, so a passage ending in an
        // emoji or a CJK extension character would gain a stray character it never had.
        int end = maxChars;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return new Resolved(text.substring(0, end), true);
    }

    /**
     * The text behind one identifier.
     *
     * @param text      What storage holds, clipped to the bound, or null when it holds nothing
     * @param truncated Whether text was dropped to meet the bound
     */
    public record Resolved(String text, boolean truncated) {

        /** No text: storage does not hold this chunk, or could not be read for it. */
        static final Resolved ABSENT = new Resolved(null, false);
    }
}
