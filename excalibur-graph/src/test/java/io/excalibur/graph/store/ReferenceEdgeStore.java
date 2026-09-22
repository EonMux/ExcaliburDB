package io.excalibur.graph.store;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.excalibur.graph.temporal.TemporalFilter;

/**
 * An obviously-correct {@link EdgeStore} over a list of edges, used to check the mapped one.
 *
 * <p>Deliberately naive: it scans every edge for every question. That makes it far too slow to use
 * and far too simple to be wrong in the same way the CSR implementation could be — which is the
 * only property that matters in an oracle. A bug shared by both would have to be a bug in the
 * shared understanding of the semantics, not in the indexing, and the semantics are what the
 * explicit tests pin down.
 */
final class ReferenceEdgeStore implements EdgeStore {

    record Edge(int subject, int relation, int object, int validFrom, int validTo) {
    }

    private final List<Edge> edges;
    private final long entityCount;

    ReferenceEdgeStore(List<Edge> edges, long entityCount) {
        this.edges = new ArrayList<>(edges);
        this.entityCount = entityCount;
    }

    @Override
    public long[] neighbors(long entity, int[] relationFilter) {
        Set<Long> found = new LinkedHashSet<>();
        collect((int) entity, relationFilter, TemporalFilter.ALL, found);
        return toArray(found);
    }

    @Override
    public long[] expand(long[] seeds, int hops, int[] relWhitelist, TemporalFilter asOf) {
        Set<Long> visited = new LinkedHashSet<>();
        for (long seed : seeds) {
            visited.add(seed);
        }
        Set<Long> frontier = new LinkedHashSet<>(visited);
        for (int hop = 0; hop < hops && !frontier.isEmpty(); hop++) {
            Set<Long> next = new LinkedHashSet<>();
            for (long entity : frontier) {
                collect((int) entity, relWhitelist, asOf, next);
            }
            next.removeAll(visited);
            visited.addAll(next);
            frontier = next;
        }
        return toArray(visited);
    }

    @Override
    public long count(long subject, int relation) {
        boolean reverse = RelationSelector.isReverse(relation);
        int wanted = RelationSelector.relationOf(relation);
        long total = 0;
        for (Edge edge : edges) {
            int endpoint = reverse ? edge.object() : edge.subject();
            if (endpoint == subject && edge.relation() == wanted) {
                total++;
            }
        }
        return total;
    }

    @Override
    public void close() {
    }

    private void collect(int entity, int[] selectors, TemporalFilter asOf, Set<Long> sink) {
        for (Edge edge : edges) {
            if (!asOf.admits(edge.validFrom(), edge.validTo())) {
                continue;
            }
            if (selectors == null) {
                // Every relation, both directions: the contract on EdgeStore.
                if (edge.subject() == entity) {
                    sink.add((long) edge.object());
                }
                if (edge.object() == entity) {
                    sink.add((long) edge.subject());
                }
                continue;
            }
            for (int selector : selectors) {
                int relation = RelationSelector.relationOf(selector);
                if (edge.relation() != relation) {
                    continue;
                }
                if (RelationSelector.isReverse(selector)) {
                    if (edge.object() == entity) {
                        sink.add((long) edge.subject());
                    }
                } else if (edge.subject() == entity) {
                    sink.add((long) edge.object());
                }
            }
        }
    }

    long entityCount() {
        return entityCount;
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
