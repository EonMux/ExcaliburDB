package io.excalibur.retrieval.serialize;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongFunction;

import io.excalibur.graph.subgraph.Subgraph;
import io.excalibur.graph.subgraph.SubgraphEdge;
import io.excalibur.graph.temporal.TemporalFilter;

/**
 * Serialises a subgraph as one line per fact, cheapest-to-drop last, stopping at the budget.
 *
 * <h2>What gets kept</h2>
 * Edges are ranked on three things, in order:
 * <ol>
 *   <li><b>Path length.</b> An edge one hop from a seed is about what was asked; one four hops out
 *       is about something related to something related to it. Depth is the strongest signal
 *       available and it costs nothing to compute.</li>
 *   <li><b>Extraction confidence.</b> Between two edges equally close to the question, the one the
 *       extractor was sure of is worth more prompt space than the one it guessed at.</li>
 *   <li><b>Seed match score.</b> The only term that knows anything about the question itself,
 *       which is why it breaks ties the other two cannot.</li>
 * </ol>
 * Ties beyond that fall back to edge index, so the same subgraph always serialises identically —
 * without which an A/B test measures sort instability alongside the format.
 *
 * <h2>Deduplication</h2>
 * Each entity is described once, however many facts mention it. Repeating it wastes budget and, more
 * seriously, presents one entity seen repeatedly as though it were corroboration from several
 * places.
 *
 * <h2>Counting</h2>
 * Every candidate line is measured with the real tokenizer before it is committed, and the running
 * total includes the separators. Estimating and correcting afterwards is how a budget gets
 * overrun by exactly the content that was hardest to predict.
 */
public final class TripleSubgraphSerializer implements SubgraphSerializer {

    private static final String NAME = "triples-v1";

    private static final String ENTITY_HEADER = "# entities";
    private static final String FACT_HEADER = "# facts";

    private final LongFunction<String> labels;
    private final LongFunction<String> relationNames;

    /** A serializer rendering entities and relations by numeric id. */
    public TripleSubgraphSerializer() {
        this(id -> "E" + id, relation -> "R" + relation);
    }

    /**
     * A serializer with real names.
     *
     * <p>Worth supplying. A model reasons far better over {@code Ada_Lovelace -[authored]->
     * Note_G} than over {@code E4711 -[R12]-> E8102}, and the ids mean nothing to it.
     */
    public TripleSubgraphSerializer(LongFunction<String> labels,
                                    LongFunction<String> relationNames) {
        if (labels == null || relationNames == null) {
            throw new IllegalArgumentException("labels and relationNames must not be null");
        }
        this.labels = labels;
        this.relationNames = relationNames;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public SerializedSubgraph serialize(Subgraph subgraph, SerializationBudget budget,
                                        SeedRelevance relevance) {
        if (subgraph == null) {
            throw new IllegalArgumentException("subgraph must not be null");
        }
        if (budget == null) {
            throw new IllegalArgumentException("budget must not be null");
        }
        SeedRelevance scores = relevance == null ? SeedRelevance.uniform() : relevance;

        if (subgraph.isEmpty() || budget.maxTokens() == 0) {
            return new SerializedSubgraph("", 0, 0, subgraph.edgeCount(), NAME);
        }

        List<SubgraphEdge> ranked = rank(subgraph, scores);

        StringBuilder text = new StringBuilder();
        Set<Long> describedEntities = new LinkedHashSet<>();
        List<String> factLines = new ArrayList<>();
        List<String> entityLines = new ArrayList<>();

        int tokens = budget.counter().count(ENTITY_HEADER + "\n" + FACT_HEADER + "\n");
        int included = 0;

        for (SubgraphEdge edge : ranked) {
            // An edge costs its own line plus a description for each endpoint not already
            // described. Charging only for the fact line would let the first few edges quietly
            // spend the budget on entity descriptions.
            // Collected as a set, so a self-loop - subject and object the same entity, which
            // any reflexive relation produces - describes that entity once. Described twice it
            // is charged twice against the budget and reads to a model as two independent
            // sources saying the same thing.
            Set<Long> newEntities = new LinkedHashSet<>(2);
            for (long entity : new long[] { edge.subject(), edge.object() }) {
                if (!describedEntities.contains(entity)) {
                    newEntities.add(entity);
                }
            }
            List<String> newEntityLines = new ArrayList<>(newEntities.size());
            for (long entity : newEntities) {
                newEntityLines.add(entityLine(entity));
            }
            String factLine = factLine(edge);

            int cost = budget.counter().count(factLine + "\n");
            for (String line : newEntityLines) {
                cost += budget.counter().count(line + "\n");
            }

            if (tokens + cost > budget.maxTokens()) {
                // Stop rather than skip. Edges are ranked, so everything after this is worth less
                // than what did not fit; continuing to look for a smaller one would fill the
                // budget with the least useful facts that happened to be short.
                break;
            }

            tokens += cost;
            included++;
            describedEntities.add((long) edge.subject());
            describedEntities.add((long) edge.object());
            entityLines.addAll(newEntityLines);
            factLines.add(factLine);
        }

        if (included == 0) {
            return new SerializedSubgraph("", 0, 0, subgraph.edgeCount(), NAME);
        }

        text.append(ENTITY_HEADER).append('\n');
        for (String line : entityLines) {
            text.append(line).append('\n');
        }
        text.append(FACT_HEADER).append('\n');
        for (String line : factLines) {
            text.append(line).append('\n');
        }

        String rendered = text.toString();
        // Measured on the assembled text, not carried forward from the running total, so the
        // reported cost is the cost of what is actually being sent.
        return new SerializedSubgraph(rendered, budget.counter().count(rendered),
            included, subgraph.edgeCount() - included, NAME);
    }

    /**
     * Orders edges by what is worth prompt space.
     *
     * <p>Seed relevance takes the better-scoring endpoint: an edge is worth keeping if either end
     * of it is what was asked about.
     */
    private List<SubgraphEdge> rank(Subgraph subgraph, SeedRelevance relevance) {
        List<SubgraphEdge> ranked = new ArrayList<>(subgraph.edges());
        ranked.sort(Comparator
            .comparingInt(SubgraphEdge::depth)
            .thenComparing(Comparator.comparingDouble(SubgraphEdge::confidence).reversed())
            .thenComparing(Comparator.comparingDouble(
                (SubgraphEdge edge) -> Math.max(
                    relevance.scoreOf(edge.subject()), relevance.scoreOf(edge.object()))).reversed())
            .thenComparingLong(SubgraphEdge::forwardEdgeIndex));
        return ranked;
    }

    private String entityLine(long entity) {
        return labels.apply(entity) + " (id=" + entity + ")";
    }

    /**
     * One fact, with its valid interval and its provenance.
     *
     * <p>Provenance is in the line rather than dropped, because a model that cannot see where a
     * fact came from cannot weigh a curated claim differently from a sentence an extractor guessed
     * at — and storing provenance the model never sees buys nothing.
     */
    private String factLine(SubgraphEdge edge) {
        StringBuilder line = new StringBuilder()
            .append(labels.apply(edge.subject()))
            .append(" -[").append(relationNames.apply(edge.relation())).append("]-> ")
            .append(labels.apply(edge.object()))
            .append(" [").append(formatInterval(edge.validFrom(), edge.validTo())).append(']')
            .append(" {").append(edge.provenance().toContextString()).append('}');
        return line.toString();
    }

    private static String formatInterval(int validFrom, int validTo) {
        String from = validFrom == Integer.MIN_VALUE
            ? "always"
            : LocalDate.ofEpochDay(validFrom).toString();
        String to = validTo == TemporalFilter.OPEN
            ? "present"
            : LocalDate.ofEpochDay(validTo).toString();
        return from + ".." + to;
    }
}
