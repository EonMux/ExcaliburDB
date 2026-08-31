package io.excalibur.graph.subgraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The part of the graph selected to answer one question.
 *
 * <p>What eventually reaches the model. It is deliberately a materialised, bounded structure rather
 * than a view over the store: everything downstream — ranking, deduplication, token budgeting,
 * serialisation — needs to look at the same fixed set several times, and re-deriving it from a
 * traversal each time would be both slower and non-deterministic as the store changes underneath.
 *
 * <p>Entities are deduplicated on id. That is not an optimisation. The same entity is routinely
 * reached by several paths, and serialising it once per path both wastes the token budget and
 * presents the model with what looks like corroboration from independent sources when it is one
 * fact seen repeatedly.
 *
 * <p>Immutable. Build one with {@link Builder}.
 */
public final class Subgraph {

    private final long[] seeds;
    private final long[] entities;
    private final List<SubgraphEdge> edges;

    private Subgraph(long[] seeds, long[] entities, List<SubgraphEdge> edges) {
        this.seeds = seeds;
        this.entities = entities;
        this.edges = List.copyOf(edges);
    }

    /** A subgraph with nothing in it. */
    public static Subgraph empty() {
        return new Subgraph(new long[0], new long[0], List.of());
    }

    /** The entities the traversal started from. */
    public long[] seeds() {
        return seeds.clone();
    }

    /** Every distinct entity in the subgraph, seeds included. */
    public long[] entities() {
        return entities.clone();
    }

    /** The selected edges. */
    public List<SubgraphEdge> edges() {
        return edges;
    }

    /** How many distinct entities the subgraph holds. */
    public int entityCount() {
        return entities.length;
    }

    /** How many edges it holds. */
    public int edgeCount() {
        return edges.size();
    }

    /** Whether there is nothing here to serialise. */
    public boolean isEmpty() {
        return edges.isEmpty() && entities.length == 0;
    }

    /** The greatest depth any edge was reached at. */
    public int maxDepth() {
        return edges.stream().mapToInt(SubgraphEdge::depth).max().orElse(0);
    }

    /**
     * Mean extractor confidence across the edges.
     *
     * <p>An input to abstention: a subgraph assembled entirely from low-confidence extractions is
     * weak evidence however well it matched the query, and that is exactly the case where
     * answering confidently does the most damage.
     */
    public double meanConfidence() {
        if (edges.isEmpty()) {
            return 0.0;
        }
        return edges.stream().mapToDouble(SubgraphEdge::confidence).average().orElse(0.0);
    }

    @Override
    public String toString() {
        return "Subgraph[" + entities.length + " entities, " + edges.size() + " edges, depth<="
            + maxDepth() + "]";
    }

    /** Accumulates entities and edges, deduplicating as it goes. */
    public static final class Builder {

        private final Set<Long> seeds = new LinkedHashSet<>();
        private final Set<Long> entities = new LinkedHashSet<>();
        private final List<SubgraphEdge> edges = new ArrayList<>();
        private final Set<Long> seenEdgeIndices = new LinkedHashSet<>();

        /** Records a seed. Seeds are also entities. */
        public Builder seed(long entity) {
            seeds.add(entity);
            entities.add(entity);
            return this;
        }

        /**
         * Adds an edge and both its endpoints.
         *
         * <p>An edge already present is ignored rather than added twice, which happens whenever a
         * traversal reaches the same edge from each end.
         */
        public Builder edge(SubgraphEdge edge) {
            if (edge == null) {
                throw new IllegalArgumentException("edge must not be null");
            }
            if (!seenEdgeIndices.add(edge.forwardEdgeIndex())) {
                return this;
            }
            edges.add(edge);
            entities.add((long) edge.subject());
            entities.add((long) edge.object());
            return this;
        }

        /** How many edges have been accumulated. */
        public int edgeCount() {
            return edges.size();
        }

        public Subgraph build() {
            return new Subgraph(toArray(seeds), toArray(entities), edges);
        }

        private static long[] toArray(Set<Long> values) {
            long[] result = new long[values.size()];
            int index = 0;
            for (long value : values) {
                result[index++] = value;
            }
            return result;
        }
    }

    /** Unmodifiable view of the seeds, for callers that want a collection. */
    public List<Long> seedList() {
        List<Long> result = new ArrayList<>(seeds.length);
        for (long seed : seeds) {
            result.add(seed);
        }
        return Collections.unmodifiableList(result);
    }
}
