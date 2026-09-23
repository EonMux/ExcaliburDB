package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.core.mapped.MappedFile;
import com.eonmux.excalibur.vector.durability.DurableFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Rebuilds a graph without its tombstoned nodes, and resolves whatever a compaction that did
 * not finish left on disk.
 *
 * <h2>Why it is its own class</h2>
 * <p>A compaction is the one operation that replaces the file the graph is serving from while
 * it is serving from it, and it replaces two files - the graph and its node-to-document
 * mapping - that must agree with each other. It therefore owns a crash protocol, a swap, a
 * rollback and a recovery, none of which the rest of the graph ever executes. It is also the
 * only place node ids are renumbered, which is what the graph's generation counter exists to
 * make safe.</p>
 *
 * <h2>The crash protocol</h2>
 * <p>Both replacement files are made durable before either becomes visible, and the graph is
 * renamed first. The staged mapping is then the signal that distinguishes the two outcomes a
 * crash can leave:</p>
 * <ul>
 *   <li>staged graph still present - the swap never started; both staged files are stale and
 *       are removed;</li>
 *   <li>staged graph gone - the graph swap completed and the live graph carries the compacted
 *       numbering, so finishing the mapping's rename is what keeps node ids resolving to the
 *       documents they actually hold.</li>
 * </ul>
 * <p>Writing the mapping after the graph instead would leave the two indistinguishable, and a
 * compacted graph read through a pre-compaction mapping answers every query with the wrong
 * documents.</p>
 *
 * <h2>Renumbering</h2>
 * <p>New node k is the (k+1)-th survivor, so every id above the first tombstone moves. The
 * graph's generation is bumped as part of the swap, which is what lets a node id captured
 * before the compaction be rejected afterwards instead of silently resolving to a different
 * vector. The PQ codes and the document mapping are re-keyed rather than kept or dropped, for
 * the same reason.</p>
 *
 * <h2>Locking</h2>
 * <p>{@link #compact} holds the write lock for its whole duration, because it tears the live
 * mapping down. {@link #compactTo} holds only the read lock: it writes a copy and never
 * touches the live file.</p>
 */
final class HnswCompactor {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswCompactor.class);

    private final HnswGraph graph;

    /**
     * Creates the compaction collaborator for one graph.
     *
     * @param graph The graph being compacted
     */
    HnswCompactor(HnswGraph graph) {
        this.graph = graph;
    }

    /**
     * Rebuilds the graph in place without its tombstoned nodes.
     *
     * @return The number of tombstoned nodes removed
     * @throws IOException if compaction fails
     */
    long compact() throws IOException {
        graph.validateNotClosed();

        graph.lock.writeLock().lock();
        try {
            long removed = graph.tombstoneCount();
            if (removed == 0) {
                LOGGER.debug("No tombstoned nodes to compact");
                return 0; // Nothing to compact
            }

            // Build the compacted graph next to the original. A caller-supplied
            // temp directory can live on a different filesystem, which makes the
            // final ATOMIC_MOVE fail after the live mapping has been torn down.
            Path originalPath = graph.path;
            Path compactPath = HnswSidecarPaths.compactionStaging(originalPath);
            discardStagedPair(compactPath);

            LOGGER.info("Starting HNSW compaction: {} tombstoned nodes to remove", removed);

            // Both replacement files are made durable before either becomes visible, and the
            // graph is renamed first. A crash then leaves a state the next open can read: the
            // staged mapping still beside the staged graph means the swap never started, and
            // beside no staged graph means the graph swap completed and only the mapping
            // rename is outstanding. Writing the mapping after the graph instead leaves the
            // two indistinguishable, and a compacted graph reading a pre-compaction mapping
            // answers every query with the wrong documents.
            CompactedState compacted = buildCompactedGraph(compactPath);
            swapInCompactedGraph(compactPath, originalPath, compacted.pqCodes());
            promoteCompactedMapping(compactPath, originalPath, compacted.documentMapping());

            LOGGER.info("Compacted HNSW graph: removed {} tombstoned nodes", removed);
            return removed;

        } finally {
            graph.lock.writeLock().unlock();
        }
    }

    /**
     * Writes a tombstone-free copy of this graph to {@code compactPath}.
     *
     * <p>Must be called with the write lock held. The copy carries over the
     * node-to-document mapping, the product quantizer codes, and the write-log integration
     * checkpoint, so nothing has to be re-derived after the swap.</p>
     *
     * <p>The mapping is rebuilt rather than shared. Compaction renumbers nodes, so
     * registering the new IDs into the live mapping would leave it holding the old
     * numbering as well; every ID above the compacted node count would then resolve
     * to whichever document held it before, and the next insert to take that ID would
     * inherit that document.</p>
     *
     * <p>It is rebuilt onto disk, at the sidecar path belonging to {@code compactPath},
     * rather than only in memory. The compacted numbering is meaningless without it, so it
     * has to be durable before the graph that uses that numbering is renamed into place.</p>
     *
     * @param compactPath Destination for the compacted graph
     * @return The state of the compacted numbering, to be installed after the swap
     * @throws IOException if the compacted graph cannot be written
     */
    private CompactedState buildCompactedGraph(Path compactPath) throws IOException {
        Map<Long, byte[]> oldPqCodes = graph.quantization.codes();
        CompactedState state;

        try (HnswGraph compacted = new HnswGraph(
                compactPath, graph.config, graph.distanceFunction, graph.maxNodes)) {
            // The replacement graph opens its own sidecar beside itself; document identity is
            // re-registered into it below, against the new node IDs.
            NodeDocumentMapping compactedMapping = compacted.getNodeDocumentMapping();

            // Re-insert all active nodes with their original vectors
            Map<Long, Long> oldToNewId = new HashMap<>();
            for (long oldNodeId = 0; oldNodeId < graph.nodeCount; oldNodeId++) {
                if (graph.isTombstoned(oldNodeId)) {
                    continue;
                }

                HnswGraph.NodeView node = graph.readNode(oldNodeId);
                if (node == null) continue;

                float[] vector = node.vector();
                long newNodeId = compacted.insert(vector);
                oldToNewId.put(oldNodeId, newNodeId);

                // Transfer document ID mapping
                String docId = graph.getDocumentId(oldNodeId);
                if (docId != null) {
                    compacted.registerDocumentId(newNodeId, docId);
                }
            }

            // Carry the write-log integration checkpoint across. Losing it would
            // make the integration worker rescan the write log from byte 0 and
            // re-insert every entry as a duplicate node.
            compacted.setLastIntegratedOffset(graph.checkpoint.get());

            // Forces the graph's pages and writes the sidecar through its own
            // stage-force-rename.
            compacted.flush();
            state = new CompactedState(compactedMapping.getAllMappings(),
                rekeyPqCodes(oldPqCodes, oldToNewId));
        }

        // Both staged names are forced once the files behind them are closed and on storage.
        // Without this the graph swap could be durable while the mapping staged beside it is
        // not, and the next open would see a completed swap with nothing left to finish.
        DurableFiles.syncContainingDirectory(compactPath);
        return state;
    }

    /**
     * Re-keys the PQ codes from the old numbering to the compacted one.
     *
     * <p>A compacted node holds the same vector it held before, so its codes are still the
     * right quantisation of it and only the id they are filed under has moved. Re-keying is
     * therefore exact, and it is what keeps a compaction from quietly switching quantisation
     * off for the segment: the codebook is unaffected, but codes dropped here would only ever
     * come back from another full encoding pass, and nothing triggers one on an active
     * segment.</p>
     *
     * @param oldCodes The codes in the pre-compaction numbering, possibly null
     * @param oldToNewId Which new node each surviving old node became
     * @return The codes in the compacted numbering, never null
     */
    private static Map<Long, byte[]> rekeyPqCodes(
            Map<Long, byte[]> oldCodes, Map<Long, Long> oldToNewId) {

        if (oldCodes == null || oldCodes.isEmpty()) {
            return Map.of();
        }
        Map<Long, byte[]> rekeyed = new HashMap<>(oldToNewId.size());
        for (Map.Entry<Long, Long> entry : oldToNewId.entrySet()) {
            byte[] codes = oldCodes.get(entry.getKey());
            if (codes != null) {
                rekeyed.put(entry.getValue(), codes);
            }
        }
        return rekeyed;
    }

    /**
     * What a compaction produced, in the compacted numbering, for installation after the swap.
     *
     * @param documentMapping The node-to-document mapping, already staged on disk beside the
     *                        compacted graph; carried in memory too so the live graph can
     *                        adopt it without reading the file back
     * @param pqCodes The product quantizer codes, empty when the segment has none
     */
    private record CompactedState(Map<Long, String> documentMapping, Map<Long, byte[]> pqCodes) {}

    /**
     * Renames the already-staged mapping over the live one and adopts it.
     *
     * <p>The contents were forced to storage before the graph was swapped, so this is a
     * rename and nothing more. If it fails, the staged file is deliberately left where it is:
     * it is the only record that the graph on disk is the compacted one, and
     * {@link #finishInterruptedCompaction(Path)} completes the swap from it at the next open.
     * The live graph adopts the compacted mapping regardless, so this process keeps answering
     * correctly while the on-disk pair is still half swapped.</p>
     *
     * @param compactPath      The staging path the compacted graph was built at
     * @param graphPath        The canonical path the graph is served from
     * @param compactedMapping The mapping in the compacted numbering
     */
    private void promoteCompactedMapping(Path compactPath, Path graphPath,
                                         Map<Long, String> compactedMapping) {
        Path stagedMapping = HnswSidecarPaths.documentMapping(compactPath);
        Path livePath = HnswSidecarPaths.documentMapping(graphPath);
        try {
            Files.move(stagedMapping, livePath,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            DurableFiles.syncContainingDirectory(livePath);
        } catch (IOException e) {
            LOGGER.error("Could not install the compacted node-to-document mapping at {}: {}. "
                + "The staged copy at {} is left in place and the next open of this graph "
                + "will finish the swap from it.", livePath, e.toString(), stagedMapping, e);
        }

        NodeDocumentMapping installed = HnswGraphOpener.openDocumentMapping(graphPath, false);
        installed.clear();
        for (Map.Entry<Long, String> entry : compactedMapping.entrySet()) {
            installed.register(entry.getKey(), entry.getValue());
        }
        graph.nodeDocumentMapping = installed;
    }

    /**
     * Resolves whatever a compaction that did not run to completion left on disk.
     *
     * <p>A failure to finish a completed graph swap is raised rather than logged. Opening
     * anyway would serve a compacted graph through a pre-compaction mapping, which returns
     * confident answers naming the wrong documents; refusing to open says so instead.</p>
     *
     * <p>Called from every path that attaches a graph file - the constructor, which is how a
     * storage segment attaches one, and {@link HnswGraph#open(Path,
     * com.eonmux.excalibur.vector.distance.DistanceFunction)}. Both, because a recovery is only a
     * recovery on the path the deployment actually takes.</p>
     *
     * @param graphPath The graph about to be opened
     * @throws IOException if a completed graph swap's mapping cannot be installed
     */
    static void finishInterruptedCompaction(Path graphPath) throws IOException {
        Path compactPath = HnswSidecarPaths.compactionStaging(graphPath);
        Path stagedMapping = HnswSidecarPaths.documentMapping(compactPath);
        if (!Files.exists(stagedMapping)) {
            return;
        }

        if (Files.exists(compactPath)) {
            LOGGER.warn("Discarding the remains of an HNSW compaction of {} that never "
                + "swapped its graph into place.", graphPath);
            try {
                discardStagedPair(compactPath);
            } catch (IOException e) {
                // The pair is intact, so the next open reaches the same verdict and tries
                // again; the live graph and mapping beside it are consistent either way.
                LOGGER.warn("Could not remove the abandoned compaction of {}: {}",
                    graphPath, e.toString());
            }
            return;
        }

        Path livePath = HnswSidecarPaths.documentMapping(graphPath);
        LOGGER.warn("An HNSW compaction of {} swapped its graph in and did not install the "
            + "matching node-to-document mapping. Completing the swap from {}.",
            graphPath, stagedMapping);
        try {
            Files.move(stagedMapping, livePath,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IOException("HNSW graph " + graphPath + " holds a compacted numbering "
                + "whose node-to-document mapping is still staged at " + stagedMapping
                + " and could not be installed. Opening it would attribute every result to "
                + "the wrong document.", e);
        }
        DurableFiles.syncContainingDirectory(livePath);
    }

    /**
     * Removes a staged graph and the staged mapping beside it, mapping first.
     *
     * <p>The order is the whole of the safety argument. {@link #finishInterruptedCompaction}
     * reads a staged mapping with no staged graph beside it as a graph swap that completed, and
     * installs that mapping over the live one. Removing the graph first would let a crash - or a
     * failed delete - between the two removals leave exactly that state behind a compaction that
     * never swapped anything, and the next open would then attribute every node of an
     * uncompacted graph to the document the compacted numbering gave it. Removing the mapping
     * first, and making its removal durable before the graph's, means every intermediate state
     * is either the intact pair or a lone staged graph, which nothing reads.</p>
     *
     * @param compactPath The staging path of the compacted graph
     * @throws IOException if the staged mapping cannot be removed, in which case the staged
     *                     graph is left in place
     */
    private static void discardStagedPair(Path compactPath) throws IOException {
        Path stagedMapping = HnswSidecarPaths.documentMapping(compactPath);
        if (Files.deleteIfExists(stagedMapping)) {
            DurableFiles.syncContainingDirectory(stagedMapping);
        }
        deleteQuietly(compactPath);
    }

    /**
     * Removes a leftover compaction artefact, reporting rather than raising a failure.
     *
     * <p>The pair that remains on disk is already consistent at this point, so a stray file
     * costs space and a confusing directory listing, not correctness.</p>
     *
     * @param path The artefact to remove
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("Could not remove the abandoned compaction artefact {}: {}",
                path, e.toString());
        }
    }

    /**
     * Replaces the live graph file with the compacted one and re-maps it.
     *
     * <p>Must be called with the write lock held. If the rename fails the original
     * file is untouched, so the graph is re-mapped from it and the compacted temp
     * file is deleted - the graph is never left permanently closed by a failed
     * swap.</p>
     *
     * @param compactPath The compacted graph built by {@link #buildCompactedGraph}
     * @param originalPath The canonical path the graph is served from
     * @param rekeyedPqCodes The PQ codes in the compacted numbering
     * @throws IOException if the swap fails
     */
    private void swapInCompactedGraph(Path compactPath, Path originalPath,
                                      Map<Long, byte[]> rekeyedPqCodes) throws IOException {
        // Release the current mapping before renaming over it.
        graph.closed = true;
        if (graph.mappedFile != null) {
            try {
                graph.mappedFile.flush();
                graph.mappedFile.close();
            } catch (Exception e) {
                LOGGER.warn("Error closing mapped file during compaction: {}", e.getMessage());
            }
        }
        if (graph.arena != null) {
            try {
                graph.arena.close();
            } catch (Exception e) {
                LOGGER.warn("Error closing arena during compaction: {}", e.getMessage());
            }
        }

        boolean moved = false;
        try {
            Files.move(compactPath, originalPath,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;

            // Compaction renumbers node IDs. Invalidate every ID handed out by the
            // previous generation so a stale ID cannot rebind to a different vector.
            graph.generation++;

            // The PQ code map is keyed by node ID and describes the old numbering: new node k
            // is the (k+1)-th survivor, whose old id is larger than k as soon as anything below
            // it was tombstoned. Left as it was, every PQ distance would score a node against
            // another vector's codes; dropped, the segment would keep a quantizer that no
            // longer has codes for anything and would silently fall back to full precision
            // forever. The codes re-keyed to the compacted numbering are installed instead.
            graph.quantization.replaceCodes(rekeyedPqCodes);

            reopenAfterCompaction(originalPath);
        } catch (IOException | RuntimeException e) {
            if (!moved) {
                rollbackFailedSwap(compactPath, originalPath);
            }
            throw e;
        }
    }

    /**
     * Restores the graph after a failed compaction swap.
     *
     * <p>The rename did not happen, so the original file still holds the live data:
     * re-map it and drop both staged files.</p>
     *
     * @param compactPath  The staging path the compacted graph was built at
     * @param originalPath The canonical path the graph is served from
     */
    private void rollbackFailedSwap(Path compactPath, Path originalPath) {
        try {
            reopenAfterCompaction(originalPath);
        } catch (Exception reopenFailure) {
            LOGGER.error("Failed to restore HNSW graph {} after an aborted compaction swap: {}",
                originalPath, reopenFailure.getMessage());
        }
        // The staged mapping goes with the staged graph. Left alone it would tell the next
        // open that a graph swap had completed, and it would install a compacted numbering
        // over the graph that was never replaced.
        try {
            discardStagedPair(compactPath);
        } catch (IOException e) {
            LOGGER.warn("Could not remove the staged compaction of {}: {}. The pair is intact "
                + "and the next open discards it.", originalPath, e.toString());
        }
    }

    /**
     * Creates a compacted copy of the graph at the specified path.
     *
     * @param destPath Destination path for the compacted graph
     * @return The number of tombstoned nodes that were skipped
     * @throws IOException if compaction fails
     */
    long compactTo(Path destPath) throws IOException {
        graph.validateNotClosed();
        Objects.requireNonNull(destPath, "destPath cannot be null");

        graph.lock.readLock().lock();
        try {
            long skipped = graph.tombstoneCount();

            LOGGER.info("Creating compacted HNSW copy at {}: {} tombstoned nodes to skip", destPath, skipped);

            // Delete an existing copy, mapping included. The copy renumbers nodes, so a
            // mapping left over from an earlier copy would attribute this one's nodes to
            // whichever documents held those IDs before.
            Files.deleteIfExists(destPath);
            Files.deleteIfExists(HnswSidecarPaths.documentMapping(destPath));

            try (HnswGraph compacted = new HnswGraph(
                    destPath, graph.config, graph.distanceFunction, graph.maxNodes)) {
                // Copy all active nodes
                for (long nodeId = 0; nodeId < graph.nodeCount; nodeId++) {
                    if (graph.isTombstoned(nodeId)) {
                        continue;
                    }

                    HnswGraph.NodeView node = graph.readNode(nodeId);
                    if (node == null) continue;

                    long newNodeId = compacted.insert(node.vector());

                    String docId = graph.getDocumentId(nodeId);
                    if (docId != null) {
                        compacted.registerDocumentId(newNodeId, docId);
                    }
                }
                compacted.flush();
            }

            LOGGER.info("Created compacted HNSW copy at {}: skipped {} tombstoned nodes", destPath, skipped);
            return skipped;

        } finally {
            graph.lock.readLock().unlock();
        }
    }

    /**
     * Reopens the graph after compaction.
     *
     * <p>This method reinitializes all internal state after the graph file
     * has been replaced by a compacted version.</p>
     *
     * @param graphPath The path to the graph file
     * @throws IOException if reopening fails
     */
    private void reopenAfterCompaction(Path graphPath) throws IOException {
        // Create new arena
        graph.arena = Arena.ofShared();
        graph.path = graphPath;

        // Reinitialize the mapped file. MappedFile.open(Path) is the READ-ONLY
        // factory; the two-argument overload maps READ_WRITE. Using the read-only
        // one here leaves the graph permanently unable to accept inserts, tombstones
        // or offset updates after a compaction.
        MappedFile file = MappedFile.open(graphPath, Files.size(graphPath));
        graph.mappedFile = file;
        if (file.isReadOnly()) {
            throw new IOException("HNSW graph re-mapped read-only after compaction: " + graphPath);
        }

        // Read header to restore state
        long magic = file.readLong(HnswGraphHeader.OFFSET_MAGIC);
        if (magic != HnswGraphHeader.MAGIC) {
            throw new IOException("Invalid HNSW graph file after compaction: bad magic number");
        }

        int version = file.readInt(HnswGraphHeader.OFFSET_VERSION);
        if (!HnswGraphHeader.isSupportedVersion(version)) {
            throw new IOException("Unsupported HNSW graph version after compaction: " + version);
        }

        // The compacted graph was sealed when it was closed, so the swap that renamed it into
        // place is checkable here: a rename that published a torn copy is caught before the
        // live graph starts serving from it.
        HnswGraphIntegrity.requireIntact(file, graphPath, version);
        graph.formatVersion = version;

        HnswGraphHeader.requireMatchingMetric(
            HnswGraphHeader.persistedMetric(
                version, file.readInt(HnswGraphHeader.OFFSET_METRIC), graphPath),
            graph.distanceFunction, graphPath);

        int dimension = file.readInt(HnswGraphHeader.OFFSET_DIMENSION);
        graph.nodeCount = file.readLong(HnswGraphHeader.OFFSET_NODE_COUNT);
        graph.entryPointId = file.readLong(HnswGraphHeader.OFFSET_ENTRY_POINT);
        graph.maxLevel = file.readInt(HnswGraphHeader.OFFSET_MAX_LEVEL);
        graph.checkpoint.adopt(file.readLong(HnswGraphHeader.OFFSET_LAST_INTEGRATED));

        // Restore config (dimension might have been validated)
        if (dimension != graph.config.dimension()) {
            throw new IOException("Dimension mismatch after compaction: expected "
                + graph.config.dimension() + ", got " + dimension);
        }

        // Calculate data region offset
        graph.dataRegionOffset = HnswGraphHeader.HEADER_SIZE
            + (graph.maxNodes * HnswNodeRecords.NODE_INDEX_ENTRY_SIZE);

        // Recalculate dataWriteOffset from the last node's index entry.
        // Setting to 0 would cause the next insert to overwrite node 0's data.
        graph.dataWriteOffset =
            HnswNodeRecords.recoverDataWriteOffset(file, graph.config, graph.nodeCount);

        // Update nodeIdGenerator to reflect existing nodes
        graph.nodeIdGenerator.set(graph.nodeCount);

        // Mark as open
        graph.closed = false;
        graph.lifecycle.breakSeal();

        LOGGER.debug("Reopened compacted HNSW graph: {} nodes, entryPoint={}, maxLevel={}",
            graph.nodeCount, graph.entryPointId, graph.maxLevel);
    }
}
