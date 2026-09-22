package com.eonmux.excalibur.vector.hnsw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Flushing, sealing and closing: what reaches storage, when, and what a later open is
 * therefore entitled to believe.
 *
 * <h2>Why it is its own class</h2>
 * <p>These four operations are the only ones that make a statement about the file as a whole
 * rather than about a node in it, and each of them exists at the point it does for a cost
 * reason. Sealing digests every byte of graph data, so it happens on close and not on flush;
 * the document mapping is written per flush and not per registration; breaking the seal is
 * forced while the mapping is still clean and costs nothing. Those three decisions together
 * are the graph's durability contract, and they are easier to hold in mind - and to change
 * deliberately - when they sit beside each other.</p>
 *
 * <h2>Sealed, open, and unprotected</h2>
 * <p>A graph file carries checksums only while nobody is writing to it. Attaching clears the
 * seal; a clean close restores it. So a graph is verifiable across a clean shutdown, and a
 * graph whose process died is reported unsealed rather than intact - a statement about the
 * writer, not about the bytes. A file older than version 3 has no integrity block and is left
 * alone: writing one now would vouch for bytes that were never covered by a checksum.</p>
 *
 * <h2>Locking</h2>
 * <p>{@link #close()} takes the write lock and is idempotent under it. {@link #flush()} does
 * not take a lock, which is what it did before and what keeps a per-batch flush off the
 * critical path; it is safe because both of the things it touches - the mapping's own flush
 * and the sidecar write - are independently safe to run concurrently with readers.</p>
 */
final class HnswGraphLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswGraphLifecycle.class);

    private final HnswGraph graph;

    /**
     * Creates the lifecycle collaborator for one graph.
     *
     * @param graph The graph whose durability this manages
     */
    HnswGraphLifecycle(HnswGraph graph) {
        this.graph = graph;
    }

    /** Flushes the mapping and writes the node-to-document mapping beside it. */
    void flush() {
        graph.validateNotClosed();
        graph.mappedFile.flush();
        persistDocumentMapping();
    }

    /**
     * Records that this process is writing to the file, so its stored checksums lapse.
     *
     * <p>Called once the graph is attached and its header has stopped changing. A file that
     * is open for writing carries no valid checksum, and saying so is what keeps a later open
     * from refusing a graph that is merely in use.</p>
     */
    void breakSeal() {
        if (graph.formatVersion != HnswGraphHeader.VERSION
            || graph.mappedFile == null || graph.mappedFile.isReadOnly()) {
            return;
        }
        HnswGraphIntegrity.markOpen(graph.mappedFile);

        // Forced here, while the mapping is still clean and the flush costs nothing. Left to
        // the page cache, the cleared seal could reach storage after the writes it disclaims,
        // and a crash in that window would leave a sound graph claiming a checksum it no
        // longer matches - which the next open would refuse.
        graph.mappedFile.flush();
    }

    /**
     * Covers the graph with checksums a later open can check.
     *
     * <p>On close rather than on flush: sealing digests every byte of graph data, and flush
     * runs on the ingestion path once per batch.</p>
     */
    private void sealIntegrity() {
        if (graph.formatVersion != HnswGraphHeader.VERSION
            || graph.mappedFile == null || graph.mappedFile.isReadOnly()) {
            return;
        }
        HnswGraphIntegrity.seal(graph.mappedFile,
            graph.dataRegionOffset + graph.dataWriteOffset - HnswGraphHeader.HEADER_SIZE);
    }

    /**
     * Writes the node-to-document mapping beside the graph.
     *
     * <p>On flush and on close rather than on every registration: the integration worker
     * records one entry per vector it inserts, and a file write per vector would put the
     * mapping on the ingest hot path. A crash therefore loses whatever was registered since
     * the last flush, which is the same exposure the graph's own checkpoint has.</p>
     *
     * <p>Failure is logged rather than thrown. A mapping that could not be written costs
     * document attribution after the next restart; letting it fail a close would cost the
     * graph's own durability.</p>
     */
    void persistDocumentMapping() {
        NodeDocumentMapping mapping = graph.nodeDocumentMapping;
        if (mapping == null || !mapping.isPersistent()) {
            return;
        }
        try {
            mapping.persist();
        } catch (IOException e) {
            LOGGER.warn("Failed to persist the node-to-document mapping for {}: {}",
                graph.path, e.toString());
        }
    }

    /** Seals the file, releases the mapping and the arena, and marks the graph closed. */
    void close() {
        graph.lock.writeLock().lock();
        try {
            if (graph.closed) {
                return;
            }
            graph.closed = true;

            persistDocumentMapping();

            // Flush before closing
            if (graph.mappedFile != null) {
                try {
                    sealIntegrity();
                    graph.mappedFile.flush();
                    graph.mappedFile.close();
                } catch (Exception e) {
                    LOGGER.warn("Error closing mapped file: {}", e.getMessage());
                }
            }

            // Close arena
            if (graph.arena != null) {
                try {
                    graph.arena.close();
                } catch (Exception e) {
                    LOGGER.warn("Error closing arena: {}", e.getMessage());
                }
            }

            LOGGER.info("Closed HNSW graph at {} ({} nodes)", graph.path, graph.nodeCount);
        } finally {
            graph.lock.writeLock().unlock();
        }
    }
}
