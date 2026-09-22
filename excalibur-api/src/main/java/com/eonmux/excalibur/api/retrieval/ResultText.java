package com.eonmux.excalibur.api.retrieval;

import java.util.function.Function;

/**
 * Where a node reads result text from, and when a request is allowed to ask for it.
 *
 * <p>A result is a chunk identifier and a score. The text behind it lives in chunk storage,
 * which not every node keeps: a query router holds no corpus, and a node built without storage
 * can rank but cannot quote. This is where the query path decides, so that its two halves - the
 * fused answer and the lexical-only one - refuse or answer the same request the same way.</p>
 *
 * <p>The routes that read text on its own, {@code GET /v1/documents} and
 * {@code POST /v1/documents/fetch}, hold a {@link ChunkTextResolver} directly: they exist only
 * on a node that has the storage, so there is nothing for them to decide.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The source is set during startup and read by every request, so
 * it is volatile rather than final - a node wires its storage after the handler exists.</p>
 *
 * @author ExcaliburDB
 */
public final class ResultText {

    private final int maxChars;
    private volatile Function<String, String> source;

    /**
     * Creates a holder that clips text at a bound.
     *
     * @param maxChars Longest text one result may carry
     * @throws IllegalArgumentException if maxChars is not positive
     */
    public ResultText(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive: " + maxChars);
        }
        this.maxChars = maxChars;
    }

    /**
     * Gives this node a chunk-text source, or takes it away.
     *
     * @param source Reads a chunk's text by identifier, or null to serve identifiers only
     */
    public void source(Function<String, String> source) {
        this.source = source;
    }

    /**
     * Returns the reader for one request.
     *
     * <p>Refused rather than answered without text: a response that quietly omits what was
     * asked for reads as a corpus that holds no text, and the caller has no way to tell the
     * difference.</p>
     *
     * @param includeText Whether the request asked for text
     * @return The reader, or null when the request did not ask for text
     * @throws IllegalArgumentException if text was asked for and this node keeps none
     */
    public ChunkTextResolver resolverFor(boolean includeText) {
        if (!includeText) {
            return null;
        }
        Function<String, String> reader = this.source;
        if (reader == null) {
            throw new IllegalArgumentException("Field 'include_text' is not supported on this "
                + "node: it keeps no chunk storage to read result text from");
        }
        return ChunkTextResolver.over(reader, maxChars);
    }
}
