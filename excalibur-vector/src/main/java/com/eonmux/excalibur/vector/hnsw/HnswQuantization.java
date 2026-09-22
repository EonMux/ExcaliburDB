package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.quantization.PQCodebookFile;
import com.eonmux.excalibur.vector.quantization.PQDistanceTable;
import com.eonmux.excalibur.vector.quantization.ProductQuantizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The product quantizer a graph may carry, the codes it has produced, and the sidecar that
 * lets the next process skip the training.
 *
 * <h2>Why it is its own class</h2>
 * <p>Quantisation is optional and independently stateful: a graph works identically without
 * it, and with it there are two pieces of state - the codebook and the per-node codes - whose
 * relationship has to hold or the segment silently ranks nodes against the wrong vectors. The
 * pairing is what needs guarding, and it is easier to guard when the pair lives in one object
 * than when it is two fields on a class that has thirty others.</p>
 *
 * <h2>The pairing rule</h2>
 * <p>Codes are meaningful only against the codebook that produced them, and only under the
 * node numbering they were filed against. So a new quantizer always arrives with a fresh,
 * empty code map, and a compaction - which renumbers nodes - installs codes re-keyed to the
 * new numbering rather than keeping or dropping the old ones. Dropping them would leave a
 * trained quantizer with codes for nothing, which falls back to full precision forever with
 * nothing to say so.</p>
 *
 * <h2>What is persisted, and what is not</h2>
 * <p>The codebook is written beside the graph, because training is expensive and a restart
 * would otherwise pay for it again. The codes are not: they are keyed by node id, which a
 * compaction renumbers, so a file of them would have to be rewritten by every compaction or be
 * read back describing the wrong vectors. They rebuild themselves instead - a node the
 * quantised beam reaches without a stored code is encoded on the spot and cached.</p>
 *
 * <h2>Locking</h2>
 * <p>Both fields are volatile and are replaced, never mutated in place, under the graph's write
 * lock; the code map is itself concurrent, so the encoding passes can run under the read lock
 * while queries and inserts continue. A reader that takes one snapshot of each field can
 * therefore see a codebook and a code map that were published together.</p>
 */
final class HnswQuantization {

    private static final Logger LOGGER = LoggerFactory.getLogger(HnswQuantization.class);

    private final HnswGraph graph;

    /** Optional product quantizer for compressed vectors. Volatile for safe publication. */
    private volatile ProductQuantizer pq;

    /** Maps nodeId to PQ codes. Replaced, never mutated in place, alongside {@link #pq}. */
    private volatile ConcurrentHashMap<Long, byte[]> pqCodes;

    /**
     * Creates the quantisation collaborator for one graph.
     *
     * @param graph The graph whose vectors are quantised
     */
    HnswQuantization(HnswGraph graph) {
        this.graph = graph;
    }

    /**
     * Returns the quantizer in force, if any.
     *
     * @return The quantizer, or null if none is configured
     */
    ProductQuantizer quantizer() {
        return pq;
    }

    /**
     * Reports whether quantised search is available.
     *
     * @return true if a trained quantizer is configured
     */
    boolean isEnabled() {
        return pq != null && pq.isTrained();
    }

    /**
     * Returns how many vectors currently have stored codes.
     *
     * @return The size of the code map, or 0 if there is none
     */
    int encodedCount() {
        ConcurrentHashMap<Long, byte[]> codes = pqCodes;
        return codes != null ? codes.size() : 0;
    }

    /**
     * Returns the codes as they stand, for a compaction to re-key.
     *
     * @return The live code map, or null if there is none
     */
    Map<Long, byte[]> codes() {
        return pqCodes;
    }

    /**
     * Installs codes that have been re-keyed to a new node numbering.
     *
     * @param rekeyed The codes in the compacted numbering
     */
    void replaceCodes(Map<Long, byte[]> rekeyed) {
        this.pqCodes = new ConcurrentHashMap<>(rekeyed);
    }

    /**
     * Adopts a quantizer and starts a fresh code map for it.
     *
     * @param quantizer A quantizer whose dimension matches the graph's, trained or not
     * @throws IllegalArgumentException if quantizer dimension doesn't match
     */
    void set(ProductQuantizer quantizer) {
        Objects.requireNonNull(quantizer, "ProductQuantizer cannot be null");
        if (quantizer.originalDim() != graph.config.dimension()) {
            throw new IllegalArgumentException(
                "ProductQuantizer dimension (" + quantizer.originalDim() +
                ") must match graph dimension (" + graph.config.dimension() + ")");
        }

        graph.lock.writeLock().lock();
        try {
            this.pq = quantizer;
            this.pqCodes = new ConcurrentHashMap<>();
            if (quantizer.isTrained()) {
                LOGGER.info("ProductQuantizer set for graph at {} (compression ratio: {}x)",
                    graph.path, quantizer.compressionRatio());
            } else {
                LOGGER.info("ProductQuantizer set for graph at {} (not yet trained, will use full-precision fallback)",
                    graph.path);
            }
        } finally {
            graph.lock.writeLock().unlock();
        }
    }

    /**
     * Trains a quantizer on a sample of this graph's vectors and publishes it.
     *
     * @param sampleSize Number of vectors to sample for training (0 = use all)
     * @param numSubspaces Number of subspaces for PQ
     * @param numCentroids Number of centroids per subspace
     * @return The trained quantizer
     */
    ProductQuantizer train(int sampleSize, int numSubspaces, int numCentroids) {
        graph.validateNotClosed();

        List<float[]> samples = collectTrainingSamples(sampleSize);

        LOGGER.info("Training ProductQuantizer on {} sample vectors", samples.size());

        // Create and train quantizer
        ProductQuantizer quantizer =
            new ProductQuantizer(graph.config.dimension(), numSubspaces, numCentroids);
        quantizer.train(samples.toArray(new float[0][]));

        // Publishing the quantizer and its code map is a mutation of shared state,
        // so it needs the write lock, not the read lock the sampling ran under.
        graph.lock.writeLock().lock();
        try {
            // Publish the (empty) code map before the quantizer so no reader can see
            // a trained quantizer paired with the previous quantizer's codes.
            this.pqCodes = new ConcurrentHashMap<>();
            this.pq = quantizer;
        } finally {
            graph.lock.writeLock().unlock();
        }

        LOGGER.info("ProductQuantizer trained successfully (compression ratio: {}x)",
            quantizer.compressionRatio());

        return quantizer;
    }

    /**
     * Samples vectors from the graph for product quantizer training.
     *
     * @param sampleSize Number of vectors to sample (0 = use all)
     * @return The sampled vectors
     * @throws IllegalStateException if the graph is empty or holds no readable vectors
     */
    private List<float[]> collectTrainingSamples(int sampleSize) {
        graph.lock.readLock().lock();
        try {
            if (graph.nodeCount == 0) {
                throw new IllegalStateException("Cannot train ProductQuantizer on empty graph");
            }

            long nodeCount = graph.nodeCount;
            int actualSampleSize = sampleSize > 0
                ? Math.min(sampleSize, (int) nodeCount) : (int) nodeCount;
            List<float[]> samples = new ArrayList<>(actualSampleSize);

            // Sample uniformly from the graph
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Set<Integer> sampledIndices = new HashSet<>();

            while (sampledIndices.size() < actualSampleSize && sampledIndices.size() < nodeCount) {
                int idx = random.nextInt((int) nodeCount);
                if (sampledIndices.add(idx)) {
                    HnswGraph.NodeView node = graph.readNodeUnderLock(idx);
                    if (node != null && !node.isTombstoned()) {
                        samples.add(node.vector());
                    }
                }
            }

            if (samples.isEmpty()) {
                throw new IllegalStateException("No valid vectors found for training");
            }

            return samples;
        } finally {
            graph.lock.readLock().unlock();
        }
    }

    /**
     * Encodes every live vector and writes the codebook beside the graph.
     *
     * @throws IllegalStateException if no trained quantizer is configured
     */
    void encodeAll() {
        if (pq == null || !pq.isTrained()) {
            throw new IllegalStateException("ProductQuantizer must be set and trained");
        }

        int encoded = encodeEveryVector();
        LOGGER.info("Encoded {} vectors with ProductQuantizer", encoded);

        // The codebook is written once the codes exist, because that is the point at which the
        // quantiser is actually in use. A codebook on disk is what lets the next process skip
        // the training this one just paid for.
        persistCodebook();
    }

    /**
     * Encodes every live node against the current quantizer.
     *
     * <p>The read lock, not the write lock. The loop reads one node per iteration off the
     * mapped file - a page-in per node, and the whole vector region for a large segment - and
     * its only mutation is into a concurrent map. Holding the exclusive lock across that
     * excluded every query and every insert on the segment for the length of a full scan, and
     * this runs on a WARMING segment, which is queryable.</p>
     *
     * @return The number of vectors encoded
     */
    private int encodeEveryVector() {
        graph.lock.readLock().lock();
        try {
            int encoded = 0;
            for (long nodeId = 0; nodeId < graph.nodeCount; nodeId++) {
                HnswGraph.NodeView node = graph.readNode(nodeId);
                if (node != null && !node.isTombstoned()) {
                    byte[] codes = pq.encode(node.vector());
                    pqCodes.put(nodeId, codes);
                    encoded++;
                }
            }
            return encoded;
        } finally {
            graph.lock.readLock().unlock();
        }
    }

    /**
     * Encodes and stores the codes for a single vector.
     *
     * @param nodeId The node ID
     * @param vector The vector to encode
     */
    void encodeAndStore(long nodeId, float[] vector) {
        if (pq == null || !pq.isTrained()) {
            return; // No PQ configured, skip silently
        }
        byte[] codes = pq.encode(vector);
        pqCodes.put(nodeId, codes);
    }

    /**
     * Writes the trained codebook beside the graph file.
     *
     * <p>Failure is logged rather than thrown. The codes this process holds are unaffected, so
     * the cost of a failed write is that the next process has to warm the segment again -
     * against a graph that is already durable. Raising here would instead fail the encoding
     * that did succeed.</p>
     */
    private void persistCodebook() {
        ProductQuantizer quantizer = this.pq;
        if (quantizer == null || !quantizer.isTrained()) {
            return;
        }
        Path codebookPath = HnswSidecarPaths.productQuantizer(graph.path);
        try {
            PQCodebookFile.write(codebookPath, quantizer);
            LOGGER.debug("Wrote the product quantizer codebook for {} to {}",
                graph.path, codebookPath);
        } catch (IOException e) {
            LOGGER.warn("Could not write the product quantizer codebook for {} to {}: {}. "
                + "This segment will have to be re-trained after a restart.",
                graph.path, codebookPath, e.toString());
        }
    }

    /**
     * Adopts the codebook sitting beside an existing graph file, if there is one.
     *
     * <p>Called when re-attaching to a graph that already holds data. Training only happens
     * while a segment is warming, and a segment that is reopened is past that, so a codebook
     * that is not picked up here is one the segment will never have again.</p>
     *
     * <p>Only the codebook is read; the codes are not scanned back in. Opening a graph is on
     * the startup path and quantised search is off unless a deployment asked for it, so a full
     * encoding pass here would be a per-segment scan at every boot that most nodes would never
     * consult. The codes rebuild themselves instead: a node the quantised beam reaches without
     * a stored code is encoded on the spot and cached, which costs the same in total and only
     * for the nodes a query actually visits. A caller that would rather pay it up front - one
     * that knows quantised search is enabled - calls {@link HnswGraph#encodeAllVectors()} after
     * opening.</p>
     *
     * <p>A codebook that does not match this graph is refused and reported, and the graph opens
     * without one. The vectors are the data; a sidecar that cannot be trusted costs
     * quantisation, and refusing to open the graph over it would cost the corpus.</p>
     */
    void restorePersisted() {
        Path codebookPath = HnswSidecarPaths.productQuantizer(graph.path);
        if (!Files.exists(codebookPath)) {
            return;
        }

        ProductQuantizer restored;
        try {
            restored = PQCodebookFile.read(codebookPath, graph.config.dimension());
        } catch (IOException e) {
            LOGGER.warn("Refusing the product quantizer codebook at {}: {}. The graph is open "
                + "without quantisation and searches it at full precision.",
                codebookPath, e.toString());
            return;
        }

        graph.setProductQuantizer(restored);
        LOGGER.info("Restored the product quantizer for {} from {}", graph.path, codebookPath);
    }

    /**
     * Computes distance using PQ when available, falling back to full precision.
     *
     * @param query The query vector, kept at full precision
     * @param node The node being scored
     * @param distanceTable The precomputed query-to-centroid table
     * @return The distance on the table's scale, or the ordering distance if there is no
     *         quantizer at all
     */
    float distance(float[] query, HnswGraph.NodeView node, PQDistanceTable distanceTable) {
        // Try PQ-based distance first
        ConcurrentHashMap<Long, byte[]> codeMap = this.pqCodes;
        if (codeMap != null) {
            byte[] codes = codeMap.get(node.nodeId());
            if (codes != null) {
                return distanceTable.lookup(codes);
            }
        }

        // A node with no stored code must still be scored on the distance table's
        // scale. Mixing raw distance-function values with PQ lookups in the same
        // priority queue compares two different metrics and ranks nodes by which
        // encoding they happen to have, so encode on the fly instead.
        ProductQuantizer quantizer = this.pq;
        if (quantizer != null && quantizer.isTrained()) {
            byte[] codes = quantizer.encode(node.vector());
            if (codeMap != null) {
                codeMap.put(node.nodeId(), codes);
            }
            return distanceTable.lookup(codes);
        }

        // No quantizer at all: fall back to the ordering distance (smaller is closer).
        return graph.computeDistance(query, node.vector());
    }
}
