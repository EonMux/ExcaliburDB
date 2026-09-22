package com.eonmux.excalibur.retrieval.pipeline;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.LongFunction;

import com.eonmux.excalibur.graph.subgraph.Subgraph;
import com.eonmux.excalibur.graph.subgraph.SubgraphEdge;
import com.eonmux.excalibur.planner.QueryPlan;

/**
 * The number an aggregate question asked for, computed over the facts the traversal collected.
 *
 * <p>Computed here rather than left to the model. "How many people did Ada Lovelace work with"
 * handed over as a list of facts asks a language model to count, and counting is exactly what it
 * does unreliably - it skips a line, counts one entity twice under two relations, or counts the
 * seed. The graph knows the answer outright; saying it is cheaper and cannot be miscounted.
 *
 * <h2>What each function reads</h2>
 * <ul>
 *   <li>{@link QueryPlan.AggregateFunction#COUNT} - how many distinct entities the named ones are
 *       related to by the facts that held at the plan's instant. Entities, not facts: two facts
 *       joining the same pair are one collaborator, not two.</li>
 *   <li>{@link QueryPlan.AggregateFunction#EARLIEST} and {@link QueryPlan.AggregateFunction#LATEST}
 *       - the fact whose valid time began first or last, among the facts that had begun by the
 *       plan's instant. An extremum asks about history, so a fact that has since ended still
 *       counts: "her first home" is almost never the one she lives in now. A fact with no
 *       recorded start takes no part: it is not the earliest of anything, only undated, and
 *       ranking it first would answer "where did she first live" with whichever fact the
 *       extractor could not date.</li>
 * </ul>
 *
 * @param function        what was computed
 * @param count           for a count, the distinct related entities; for an extremum, how many
 *                        facts share the extreme start
 * @param factsConsidered how many facts the function ran over - for an extremum, the facts the
 *                        traversal kept, which are the ones at the extreme, or every one collected
 *                        when none was dated
 * @param validFrom       for an extremum, the day the extreme fact began; empty for a count and
 *                        when no dated fact qualified
 * @param entities        the related entities the value is about, by name, in id order
 */
public record Aggregation(QueryPlan.AggregateFunction function, int count, int factsConsidered,
                          OptionalInt validFrom, List<String> entities) {

    /** The start a fact with no recorded beginning carries, which the serializer calls "always". */
    private static final int NO_RECORDED_START = Integer.MIN_VALUE;

    public Aggregation {
        if (function == null || validFrom == null || entities == null) {
            throw new IllegalArgumentException("function, validFrom and entities are required");
        }
        if (count < 0 || factsConsidered < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        entities = List.copyOf(entities);
    }

    /**
     * Computes the aggregate over a subgraph's facts.
     *
     * <p>The subgraph must already hold the facts valid at the plan's instant for a count, and
     * the ones begun by it for an extremum. Selecting those is the traversal's job, and doing it
     * twice would give two answers to which facts qualified. Of them, only facts with a named
     * entity at one end are read: a walk of more than one hop also collects facts between the
     * entities it reached, and "Ada's earliest fact" is not the earliest fact among her
     * neighbours' neighbours.
     *
     * @param function what to compute
     * @param subgraph the facts to compute it over
     * @param names    names entities for the answer
     * @return the aggregate
     */
    static Aggregation over(QueryPlan.AggregateFunction function, Subgraph subgraph,
                            LongFunction<String> names) {
        Set<Long> seeds = new LinkedHashSet<>();
        for (long seed : subgraph.seeds()) {
            seeds.add(seed);
        }
        List<SubgraphEdge> facts = subgraph.edges().stream()
            .filter(fact -> seeds.contains((long) fact.subject())
                || seeds.contains((long) fact.object()))
            .toList();
        return switch (function) {
            case COUNT -> new Aggregation(function, relatedTo(facts, seeds).length, facts.size(),
                OptionalInt.empty(), namesOf(relatedTo(facts, seeds), names));
            case EARLIEST, LATEST -> extremum(function, facts, seeds, names);
        };
    }

    /**
     * The facts an extremum answers with: every dated fact sharing the earliest or latest start.
     *
     * <p>All of them rather than the first found, because two facts beginning the same day are
     * equally the answer and picking one would be a guess presented as a result.
     *
     * @param function {@link QueryPlan.AggregateFunction#EARLIEST} or
     *                 {@link QueryPlan.AggregateFunction#LATEST}
     * @param facts    the facts begun by the plan's instant
     * @return the facts at the extreme, empty when no fact has a recorded start
     */
    static List<SubgraphEdge> extremeFacts(QueryPlan.AggregateFunction function,
                                           List<SubgraphEdge> facts) {
        List<SubgraphEdge> atExtreme = new ArrayList<>();
        int extreme = 0;
        for (SubgraphEdge fact : facts) {
            int start = fact.validFrom();
            if (start == NO_RECORDED_START) {
                continue;
            }
            boolean beyond = atExtreme.isEmpty()
                || (function == QueryPlan.AggregateFunction.EARLIEST ? start < extreme
                    : start > extreme);
            if (beyond) {
                atExtreme.clear();
                extreme = start;
            }
            if (start == extreme) {
                atExtreme.add(fact);
            }
        }
        return atExtreme;
    }

    private static Aggregation extremum(QueryPlan.AggregateFunction function,
                                        List<SubgraphEdge> facts, Set<Long> seeds,
                                        LongFunction<String> names) {
        List<SubgraphEdge> atExtreme = extremeFacts(function, facts);
        if (atExtreme.isEmpty()) {
            return new Aggregation(function, 0, facts.size(), OptionalInt.empty(), List.of());
        }
        return new Aggregation(function, atExtreme.size(), facts.size(),
            OptionalInt.of(atExtreme.get(0).validFrom()),
            namesOf(relatedTo(atExtreme, seeds), names));
    }

    /**
     * The entities at the far end of the facts from the named ones, each once, in id order.
     *
     * <p>A fact between two named entities relates each to the other, so both count - "how many
     * people did Ada and Charles work with" counts them for each other too. A fact from a named
     * entity to itself relates it to nothing new and counts nothing.
     */
    private static long[] relatedTo(List<SubgraphEdge> facts, Set<Long> seeds) {
        Set<Long> related = new LinkedHashSet<>();
        for (SubgraphEdge fact : facts) {
            long subject = fact.subject();
            long object = fact.object();
            if (subject == object) {
                continue;
            }
            if (seeds.contains(subject)) {
                related.add(object);
            }
            if (seeds.contains(object)) {
                related.add(subject);
            }
        }
        long[] ids = related.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(ids);
        return ids;
    }

    private static List<String> namesOf(long[] ids, LongFunction<String> names) {
        List<String> named = new ArrayList<>(ids.length);
        for (long id : ids) {
            named.add(names.apply(id));
        }
        return named;
    }

    /**
     * The aggregate as one line of model context.
     *
     * <p>Leads the context so the model reads the answer before the evidence for it, and says
     * what it was computed over so the number is not read as more than it is.
     *
     * @param about the entities the question named
     * @return the line, without a trailing newline
     */
    String describe(List<String> about) {
        String subject = String.join(", ", about);
        return switch (function) {
            case COUNT -> "count: " + count + " distinct entities related to " + subject
                + " by " + factsConsidered + " facts";
            case EARLIEST, LATEST -> function.name().toLowerCase(java.util.Locale.ROOT)
                + " about " + subject + ": "
                + (validFrom.isEmpty() ? "no fact with a recorded start"
                    : "began " + LocalDate.ofEpochDay(validFrom.getAsInt()) + ", with "
                        + String.join(", ", entities) + " (" + count
                        + (count == 1 ? " fact)" : " facts)"));
        };
    }
}
