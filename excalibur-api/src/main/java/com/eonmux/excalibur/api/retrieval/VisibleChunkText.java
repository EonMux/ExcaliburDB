package com.eonmux.excalibur.api.retrieval;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.ingest.registry.DocumentLocks;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker;
import com.eonmux.excalibur.query.visibility.DocumentAtomicityFilter;

import java.util.function.Function;

/**
 * A chunk's stored text, withheld while its document is being written.
 *
 * <h2>Why the read needs its own gate</h2>
 * <p>Storage is rewritten in place, one chunk at a time, and a result's text is read after the
 * search that ranked it. A document visible when it was ranked can be in the middle of a rewrite
 * when its text is read, and a page of a document's chunks can straddle one - some chunks the old
 * version, some the new. Filtering the ranking cannot see either; only the read can.</p>
 *
 * <h2>The rule</h2>
 * <p>A chunk's text is read only if the visibility checker does not withhold its document, and
 * kept only if no rewrite of the document overlapped the read
 * ({@link DocumentLocks#readUnlessRewriting}). Every writer holds the document's lock from
 * marking it in flight to publishing or rolling it back, and an erasure holds it while it
 * removes the chunks, so a read kept sees one whole version, and a read that overlapped a writer
 * answers null - the same answer as a chunk storage does not hold. A read never waits for a
 * writer, so rendering a page costs no more than it did while a large document is re-chunked,
 * and the rewrite of another document sharing the lock's stripe does not withhold it.</p>
 *
 * <p>The whole time a writer holds the document's lock counts as rewriting it, including the
 * moments before its mark and after its publication, when storage already holds one whole
 * version: a reader cannot tell those apart from the write between them. A thread already
 * holding the lock - a writer reading its own document - reads through.</p>
 *
 * <p>The ranking's own index maintenance - the lexical compactor, the erasure sweep - reads the
 * stored text directly and must not be handed this view: it needs the text precisely while the
 * document is being written.</p>
 */
public final class VisibleChunkText {

    private VisibleChunkText() {
    }

    /**
     * The stored text, withheld while its document is being written.
     *
     * @param source  Reads a chunk's stored text, or null when storage does not hold it
     * @param checker The node's document visibility checker, or null when the node withholds
     *                nothing - then the source itself is returned
     * @return Reads a chunk's text, or null when storage does not hold it or its document is
     *         being written
     */
    public static Function<String, String> over(Function<String, String> source,
                                                DocumentVisibilityChecker checker) {
        if (checker == null) {
            return source;
        }
        return chunkId -> {
            if (chunkId == null) {
                return null;
            }
            String documentKey = ChunkIdentifiers.documentKeyOfChunk(chunkId);
            return DocumentLocks.shared().readUnlessRewriting(documentKey,
                () -> withheld(checker, documentKey) ? null : source.apply(chunkId), null);
        };
    }

    /**
     * Whether the checker withholds a document from a read.
     *
     * <p>Unbounded by epoch: a read has no snapshot of its own, and a version published since the
     * search began is whole in storage.</p>
     *
     * @param checker     The node's document visibility checker
     * @param documentKey The document's key
     * @return True when the document is tracked and not visible
     */
    private static boolean withheld(DocumentVisibilityChecker checker, String documentKey) {
        return checker.isDocumentTracked(documentKey)
            && !checker.isDocumentVisible(documentKey, DocumentAtomicityFilter.UNBOUNDED);
    }
}
