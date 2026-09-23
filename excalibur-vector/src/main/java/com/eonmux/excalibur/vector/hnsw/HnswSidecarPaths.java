package com.eonmux.excalibur.vector.hnsw;

import java.nio.file.Path;

/**
 * Names the files that live beside a graph file.
 *
 * <h2>Why beside, and why derived</h2>
 * <p>A graph's node-to-chunk mapping and its trained codebook are only meaningful for the
 * graph they were produced from, and its compaction staging file has to be renamed over the
 * live one. All three therefore sit in the graph's own directory under a name derived from the
 * graph's: the rename is then a move within one filesystem rather than a copy that can fail
 * half way, and anything a crash leaves behind can be attributed to the graph it belongs to
 * instead of becoming an orphan in a scratch directory.</p>
 *
 * <p>Deriving the names rather than passing them also means the compaction recovery path can
 * find the artefacts of a compaction it did not run, which is the only way a crash between the
 * two renames can be resolved at all.</p>
 */
final class HnswSidecarPaths {

    /** Suffix for the compacted graph built alongside the live file before the swap. */
    private static final String COMPACT_SUFFIX = ".compact";

    private HnswSidecarPaths() {
    }

    /**
     * Returns the path of the node-to-chunk mapping that belongs beside a graph file.
     *
     * @param graphPath The graph file
     * @return The sidecar path, which is the graph path plus
     *         {@link HnswGraph#DOCUMENT_MAPPING_SUFFIX}
     */
    static Path documentMapping(Path graphPath) {
        return Path.of(graphPath.toString() + HnswGraph.DOCUMENT_MAPPING_SUFFIX);
    }

    /**
     * Returns the path of the product quantizer codebook belonging to a graph file.
     *
     * @param graphPath The graph file
     * @return The sidecar path, which is the graph path plus
     *         {@link HnswGraph#PRODUCT_QUANTIZER_SUFFIX}
     */
    static Path productQuantizer(Path graphPath) {
        return graphPath.resolveSibling(graphPath.getFileName() + HnswGraph.PRODUCT_QUANTIZER_SUFFIX);
    }

    /**
     * Returns the path a compaction builds the replacement graph at.
     *
     * @param graphPath The graph being compacted
     * @return The staging path for the compacted graph
     */
    static Path compactionStaging(Path graphPath) {
        return graphPath.resolveSibling(graphPath.getFileName() + COMPACT_SUFFIX);
    }
}
