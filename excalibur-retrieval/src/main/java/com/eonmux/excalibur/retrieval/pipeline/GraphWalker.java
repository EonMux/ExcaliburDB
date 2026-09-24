package com.eonmux.excalibur.retrieval.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.roaringbitmap.RoaringBitmap;

import com.eonmux.excalibur.core.util.QueryBudget;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.store.EdgeStore;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.subgraph.Subgraph;
import com.eonmux.excalibur.graph.subgraph.SubgraphEdge;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.planner.QueryPlan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks the graph as a plan directs and collects the facts it passes.
 *
 * <p>The traversal half of {@link GraphRagPipeline}, apart from it because it is the half with
 * rules of its own: which relations a question's words narrow the walk to, which way along each
 * edge it may step, and which facts count at the plan's instant. The pipeline decides the order
 * things happen in; this decides what the graph says.
 *
 * <h2>Which facts count</h2>
 * The ones valid at the plan's instant, for every plan but one. An earliest or latest aggregate
 * asks about history - "her first home" is almost never the one she lives in now - so it reads
 * every fact that had <em>begun</em> by the instant, ended or not, and keeps only those at the
 * extreme. Collecting the history and letting the model find the extreme would hand it the whole
 * list and the job of reading dates, which is the job this does exactly. A fact with no recorded
 * start is not at either extreme; when nothing collected is dated, everything collected is kept
 * and the aggregate says so.
 *
 * <h2>How far a walk may grow</h2>
 * Bounded by {@link WalkLimits}: each hop admits at most {@code maxFrontier} new entities, those
 * reached along the strongest edges, and the walk collects at most {@code maxFacts} facts, those
 * touching the named entities first, then the most confident, then the most recent. What either
 * bound left out is reported beside the subgraph, never silently. The frontier bound needs the
 * edge columns a {@link MappedEdgeStore} has; another store is walked unbounded, and has no facts
 * to collect.
 *
 * <h2>Deadline</h2>
 * A walk whose query's deadline passes stops where it is and hands back what it had collected,
 * marking the budget timed out; a hop that was in progress is discarded whole. A cancelled query
 * ends the walk with a {@link java.util.concurrent.CancellationException}.
 *
 * <h2>Thread Safety</h2>
 * Stateless beyond its immutable collaborators, and safe to share as long as they are.
 */
final class GraphWalker {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphWalker.class);

    private final EdgeStore edgeStore;

    /**
     * The order facts are kept in when a walk passes more than it may collect: nearest the named
     * entities, then most confident, then latest start, then position in the store.
     */
    private static final Comparator<SubgraphEdge> PREFERRED =
        Comparator.comparingInt(SubgraphEdge::depth)
            .thenComparing(Comparator.comparingInt(
                (SubgraphEdge edge) -> edge.provenance().confidence()).reversed())
            .thenComparing(Comparator.comparingInt(SubgraphEdge::validFrom).reversed())
            .thenComparingLong(SubgraphEdge::forwardEdgeIndex);

    /** Resolves relation hints and names the vocabulary; null when this node has no dictionary. */
    private final RelationResolver relations;

    private final WalkLimits limits;

    /**
     * @param edgeStore the graph
     * @param relations the relation vocabulary, or null to traverse every relation both ways
     * @param limits    how wide a walk may grow
     */
    GraphWalker(EdgeStore edgeStore, RelationResolver relations, WalkLimits limits) {
        this.edgeStore = edgeStore;
        this.relations = relations;
        this.limits = limits;
    }

    /**
     * The facts a walk collected, and what its limits left out.
     *
     * @param subgraph   the seeds and the facts collected
     * @param truncation what the frontier and fact bounds dropped
     */
    record Walk(Subgraph subgraph, WalkTruncation truncation) {
    }

    /**
     * Whether this walker can step one way only.
     *
     * <p>The store's absent filter means every relation in both directions, so a one-way walk has
     * to name every relation - which needs the vocabulary.
     */
    boolean canRestrictDirection() {
        return relations != null && relations.all() != null;
    }

    /**
     * Walks the graph as the plan directs, at the plan's instant.
     *
     * <p>Exhaustive over the sealed plan hierarchy, so a new plan type is a compile error here
     * rather than a silent fall-through to a default traversal.
     *
     * <p>Both directions unless the caller asked otherwise - see the contract on {@link EdgeStore}.
     * A question is about an edge, not about the direction someone happened to assert it in:
     * "who did Ada Lovelace collaborate with" and "who did Charles Babbage collaborate with" are
     * the same edge asked from its two ends, and a forward-only walk answers the first and refuses
     * the second with no way for the asker to tell that the fact was there.
     *
     * @param plan      the plan, possibly temporal
     * @param seeds     the entities the question's mentions resolved to
     * @param direction which way the walk may step
     * @param budget    the query's deadline and cancellation
     * @return the facts collected, and what the walk's limits left out
     * @throws java.util.concurrent.CancellationException if the query was cancelled
     */
    Walk walk(QueryPlan plan, long[] seeds, TraversalDirection direction, QueryBudget budget) {
        TemporalFilter asOf = plan instanceof QueryPlan.Temporal temporal
            ? temporal.asOf()
            : TemporalFilter.now();
        QueryPlan core = plan.shape();

        int hops = switch (core) {
            case QueryPlan.Lookup ignored -> 1;
            case QueryPlan.Traverse traverse -> traverse.hops();
            // An aggregate is answered from the seeds' immediate edges and nothing beyond them.
            case QueryPlan.Aggregate ignored -> 1;
            case QueryPlan.Temporal ignored -> throw new IllegalStateException(
                "a temporal plan cannot wrap another; construction already forbids it");
        };

        QueryPlan.AggregateFunction extremum = core instanceof QueryPlan.Aggregate aggregate
            && aggregate.function() != QueryPlan.AggregateFunction.COUNT
            ? aggregate.function()
            : null;
        Window window = extremum == null ? new Window(asOf, asOf::admits) : beganBy(asOf);

        Walk collected = walkNarrowed(core, seeds, hops, direction, window, budget);
        return extremum == null
            ? collected
            : new Walk(atExtreme(collected.subgraph(), extremum), collected.truncation());
    }

    /**
     * Walks the relations the plan names, falling back to every relation if that finds nothing.
     *
     * <p>The field is called relationHints and the fallback is what makes that true. The cues
     * that produce them are a fixed English vocabulary matched against relation names an
     * extractor chose, so a hint that resolves to the wrong relation is an ordinary outcome rather
     * than a rare one. Letting it stand would turn a question the graph can answer into a
     * refusal, and the asker would have no way to see that the fact was there. A hint may sharpen
     * an answer; it may not remove one.
     */
    private Walk walkNarrowed(QueryPlan core, long[] seeds, int hops,
                              TraversalDirection direction, Window window, QueryBudget budget) {
        int[] wanted = relationsNamedBy(core);
        if (wanted.length > 0) {
            Walk narrowed = walkAlong(seeds, hops, selectors(wanted, direction), direction,
                window, wanted, budget);
            if (!narrowed.subgraph().edges().isEmpty()) {
                return narrowed;
            }
            LOGGER.debug("relation hints {} matched no edge; traversing every relation",
                Arrays.toString(wanted));
        }
        int[] every = direction == TraversalDirection.BOTH
            ? null
            : selectors(relations.all(), direction);
        return walkAlong(seeds, hops, every, direction, window, null, budget);
    }

    /**
     * Expands along {@code selectors} and collects the facts the walk passed.
     *
     * @param selectors        the relations and directions to follow, or null for all both ways
     * @param allowedRelations the relations facts may carry, or null for any
     */
    private Walk walkAlong(long[] seeds, int hops, int[] selectors,
                           TraversalDirection direction, Window window,
                           int[] allowedRelations, QueryBudget budget) {
        Reach reach = expand(seeds, hops, selectors, window.walk(), budget);
        // Where a one-way step may start: anything reached with a hop to spare. The capped walk
        // is deterministic hop by hop, so this is exactly the walk above one hop short.
        long[] startable = direction == TraversalDirection.BOTH
            ? reach.reached()
            : expand(seeds, hops - 1, selectors, window.walk(), budget).reached();
        return collect(seeds, reach, startable, window, allowedRelations, direction, budget);
    }

    /**
     * The entities within {@code hops}, each hop capped when the store can rank its edges.
     *
     * @return the entities reached, and how many a cap left out
     */
    private Reach expand(long[] seeds, int hops, int[] selectors, TemporalFilter asOf,
                         QueryBudget budget) {
        if (!(edgeStore instanceof MappedEdgeStore mapped)) {
            budget.throwIfCancelled();
            return new Reach(edgeStore.expand(seeds, hops, selectors, asOf), 0);
        }
        MappedEdgeStore.Expansion expansion = mapped.expand(seeds, hops, selectors, asOf,
            limits.maxFrontier(), budget::isExhausted);
        budget.throwIfCancelled();
        if (expansion.stopped()) {
            budget.markTimedOut();
        }
        return new Reach(expansion.reached(), expansion.entitiesDropped());
    }

    /**
     * What one expansion reached.
     *
     * @param reached         the entities, seeds included
     * @param entitiesDropped how many a hop's cap left out
     */
    private record Reach(long[] reached, long entitiesDropped) {
    }

    /**
     * The relation ids the plan's hints name, empty when there are none or none resolve.
     *
     * <p>Empty rather than an empty whitelist: an empty selector array asks the store for nothing
     * and is a legitimate request, so handing one to {@link EdgeStore#expand} because a question
     * happened to use no recognised relation word would answer every such question with silence.
     */
    private int[] relationsNamedBy(QueryPlan core) {
        if (relations == null) {
            return new int[0];
        }
        List<String> hints = switch (core) {
            case QueryPlan.Traverse traverse -> traverse.relationHints();
            case QueryPlan.Aggregate aggregate -> aggregate.relationHints();
            // A lookup reads facts off the entity; there is no walk for a hint to narrow.
            case QueryPlan.Lookup ignored -> List.of();
            case QueryPlan.Temporal ignored -> throw new IllegalStateException(
                "a temporal plan cannot wrap another; construction already forbids it");
        };

        List<Integer> resolved = new ArrayList<>();
        for (String hint : hints) {
            for (int relation : relations.resolve(hint)) {
                if (!resolved.contains(relation)) {
                    resolved.add(relation);
                }
            }
        }
        return resolved.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Selectors following each relation in the direction asked for.
     *
     * <p>A hint names a relation, never a direction - "collaborated" says nothing about which end
     * the fact was asserted from - so the direction is the caller's, and both unless it said.
     */
    private static int[] selectors(int[] relationIds, TraversalDirection direction) {
        List<Integer> selectors = new ArrayList<>(relationIds.length * 2);
        for (int relation : relationIds) {
            for (int selector : direction.selectorsFor(relation)) {
                selectors.add(selector);
            }
        }
        return selectors.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Materialises the edges the walk passed.
     *
     * <p>Only for a {@link MappedEdgeStore}, which is the only implementation that can report an
     * edge's columns. Another implementation yields the entities alone — enough to answer, but
     * with no provenance to show, so abstention will treat it as weak evidence rather than
     * silently treating it as strong.
     *
     * <p>A one-way walk keeps a fact only if it was stepped along the right way: forward from an
     * entity the walk could start a step from, or reverse into one. Keeping every fact between
     * reached entities would return, for "what did this company acquire", the fact that it was
     * acquired - both ends are in reach, and only the direction says the fact is not the answer.
     */
    private Walk collect(long[] seeds, Reach reach, long[] startable, Window window,
                         int[] allowedRelations, TraversalDirection direction,
                         QueryBudget budget) {
        long[] reachable = reach.reached();
        Subgraph.Builder builder = new Subgraph.Builder();
        for (long seed : seeds) {
            builder.seed(seed);
        }
        if (!(edgeStore instanceof MappedEdgeStore mapped)) {
            return new Walk(builder.build(), new WalkTruncation(reach.entitiesDropped(), 0));
        }

        // Bitmaps over entity ids rather than boxed sets: the membership test runs once per edge
        // examined, and RoaringBitmap is already what the traversal itself works in.
        RoaringBitmap inScope = bitmapOf(reachable);
        RoaringBitmap canStart = bitmapOf(startable);
        RoaringBitmap seedSet = bitmapOf(seeds);

        // Only the entities the traversal reached, rather than every entity in the store. The
        // subgraph is a handful of entities and the store can hold tens of millions of edges,
        // so a full scan made the cost of answering a question grow with the size of the graph
        // instead of with the size of the answer.
        // An edge between two reached entities is met from each end; it is one fact.
        List<SubgraphEdge> passed = new ArrayList<>();
        Set<Long> met = new HashSet<>();
        for (long entity : reachable) {
            if (budget.isExhausted()) {
                budget.throwIfCancelled();
                budget.markTimedOut();
                break;
            }
            mapped.forEachEdgeOf(entity, (subject, relation, object, index) -> {
                if (!inScope.contains(object) || !stepped(direction, subject, object, canStart)) {
                    return;
                }
                // The whitelist narrowed which entities the walk reached; without this it would
                // not narrow what is said about them, and a traversal restricted to one relation
                // would hand the model a subgraph full of every other.
                if (allowedRelations != null && !contains(allowedRelations, relation)) {
                    return;
                }
                int validFrom = mapped.validFrom(index);
                int validTo = mapped.validTo(index);
                if (!window.facts().admits(validFrom, validTo)) {
                    return;
                }
                if (!met.add(index)) {
                    return;
                }
                Provenance provenance = mapped.provenanceOf(index);
                int depth = seedSet.contains(subject) || seedSet.contains(object) ? 1 : 2;
                passed.add(new SubgraphEdge(subject, relation, object, index,
                    validFrom, validTo, provenance, depth));
            });
        }
        List<SubgraphEdge> kept = strongest(passed, limits.maxFacts());
        kept.forEach(builder::edge);
        return new Walk(builder.build(),
            new WalkTruncation(reach.entitiesDropped(), passed.size() - kept.size()));
    }

    /**
     * The {@code max} facts to keep of those a walk passed, in the order it passed them.
     *
     * <p>Ranked by {@link #PREFERRED}: a fact about a named entity is evidence for the answer,
     * one two hops out is context, and the store position that ends the order is unique, so the
     * choice is total. Kept in encounter order afterwards, so a walk inside its bound yields
     * exactly the subgraph it always did.</p>
     */
    private static List<SubgraphEdge> strongest(List<SubgraphEdge> passed, int max) {
        if (passed.size() <= max) {
            return passed;
        }
        List<SubgraphEdge> ranked = new ArrayList<>(passed);
        ranked.sort(PREFERRED);
        Set<Long> chosen = new HashSet<>();
        for (int i = 0; i < max; i++) {
            chosen.add(ranked.get(i).forwardEdgeIndex());
        }
        List<SubgraphEdge> kept = new ArrayList<>(max);
        for (SubgraphEdge edge : passed) {
            if (chosen.contains(edge.forwardEdgeIndex())) {
                kept.add(edge);
            }
        }
        return kept;
    }

    /** Whether a fact from {@code subject} to {@code object} lies along the walk's direction. */
    private static boolean stepped(TraversalDirection direction, int subject, int object,
                                   RoaringBitmap canStart) {
        return switch (direction) {
            case BOTH -> true;
            case FORWARD -> canStart.contains(subject);
            case REVERSE -> canStart.contains(object);
        };
    }

    /**
     * Keeps only the facts at the earliest or latest start, with the seeds they hang from.
     *
     * <p>When no fact has a recorded start there is no extreme to keep, and the facts are kept as
     * collected: the aggregate then says nothing was dated, and the model still has the facts to
     * answer from rather than an empty subgraph that abstention would refuse.
     *
     * @param collected every fact begun by the instant
     * @param extremum  which end of history is wanted
     * @return the seeds and the extreme facts, or everything collected when none is dated
     */
    private static Subgraph atExtreme(Subgraph collected, QueryPlan.AggregateFunction extremum) {
        List<SubgraphEdge> extreme = Aggregation.extremeFacts(extremum, collected.edges());
        if (extreme.isEmpty()) {
            return collected;
        }
        Subgraph.Builder builder = new Subgraph.Builder();
        for (long seed : collected.seeds()) {
            builder.seed(seed);
        }
        for (SubgraphEdge fact : extreme) {
            builder.edge(fact);
        }
        return builder.build();
    }

    /**
     * The window an extremum reads: every fact that had begun by the instant, ended or not.
     *
     * <p>The walk itself admits every interval, because a fact that has since ended still joins
     * its two ends for this question; the collection then drops what had not yet begun.
     */
    private static Window beganBy(TemporalFilter asOf) {
        if (asOf.isUnbounded()) {
            return new Window(TemporalFilter.ALL, (validFrom, validTo) -> true);
        }
        int instant = asOf.asOfDays();
        return new Window(TemporalFilter.ALL, (validFrom, validTo) -> validFrom <= instant);
    }

    private static RoaringBitmap bitmapOf(long[] entities) {
        RoaringBitmap bitmap = new RoaringBitmap();
        for (long entity : entities) {
            bitmap.add((int) entity);
        }
        return bitmap;
    }

    private static boolean contains(int[] values, int wanted) {
        for (int value : values) {
            if (value == wanted) {
                return true;
            }
        }
        return false;
    }

    /**
     * Which intervals a walk may step along, and which facts it keeps.
     *
     * @param walk  applied as edges are expanded
     * @param facts applied to each fact collected
     */
    private record Window(TemporalFilter walk, FactTest facts) {
    }

    /** Whether a fact valid over {@code [validFrom, validTo)} is kept. */
    @FunctionalInterface
    private interface FactTest {
        boolean admits(int validFrom, int validTo);
    }
}
