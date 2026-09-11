package io.excalibur.retrieval.pipeline;

import org.roaringbitmap.RoaringBitmap;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

import io.excalibur.core.trace.QueryTrace;
import io.excalibur.core.trace.Stage;
import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.store.EdgeStore;
import io.excalibur.graph.store.MappedEdgeStore;
import io.excalibur.graph.subgraph.Subgraph;
import io.excalibur.graph.subgraph.SubgraphEdge;
import io.excalibur.graph.temporal.TemporalFilter;
import io.excalibur.planner.ConversationContext;
import io.excalibur.planner.PlanningOutcome;
import io.excalibur.planner.QueryClassifier;
import io.excalibur.planner.QueryPlan;
import io.excalibur.retrieval.HybridRetriever;
import io.excalibur.retrieval.abstain.AbstentionPolicy;
import io.excalibur.retrieval.abstain.RetrievalOutcome;
import io.excalibur.retrieval.fusion.FusedDocument;
import io.excalibur.retrieval.rank.RetrievalQuery;
import io.excalibur.retrieval.serialize.SeedRelevance;
import io.excalibur.retrieval.serialize.SerializationBudget;
import io.excalibur.retrieval.serialize.SerializedSubgraph;
import io.excalibur.retrieval.serialize.SubgraphSerializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The whole path from a question to the text a model is given, in one place.
 *
 * <p>Plan, link, traverse, rank, serialise, decide. Each stage is a component that can be replaced
 * on its own; this class is only the order they run in and the trace that records what each cost.
 * Having it exist as one object is what makes the pipeline testable end to end and what stops the
 * ordering from being reimplemented slightly differently at each call site.
 *
 * <p>Two stages can stop the pipeline early, and both are deliberate:
 * <ul>
 *   <li>Planning returns a question back when a pronoun cannot be bound. There is no point
 *       retrieving for a question whose subject is unknown.</li>
 *   <li>Abstention declines when the evidence does not support an answer. Serialising a subgraph
 *       the system does not believe in only gives the model material to be confidently wrong
 *       with.</li>
 * </ul>
 */
public final class GraphRagPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphRagPipeline.class);

    private final QueryClassifier classifier;
    private final EntityLinker linker;
    private final EdgeStore edgeStore;
    private final HybridRetriever retriever;
    private final SubgraphSerializer serializer;
    private final AbstentionPolicy abstention;
    private final SerializationBudget budget;
    private final int topK;

    /**
     * Turns the question into a vector, when this node can.
     *
     * <p>Optional, because a deployment with no embedding endpoint still answers - by term overlap
     * alone, without the exact rerank. Null is that deployment.</p>
     */
    private final QueryEmbedder embedder;

    public GraphRagPipeline(QueryClassifier classifier, EntityLinker linker, EdgeStore edgeStore,
                            HybridRetriever retriever, SubgraphSerializer serializer,
                            AbstentionPolicy abstention, SerializationBudget budget, int topK) {
        this(classifier, linker, edgeStore, retriever, serializer, abstention, budget, topK, null);
    }

    /**
     * Creates a pipeline that embeds the question, so retrieval can rerank against real vectors.
     *
     * @param classifier Turns the question into a plan
     * @param linker     Resolves mentions to entities
     * @param edgeStore  The graph to traverse
     * @param retriever  The retrieval signals and their fusion
     * @param serializer Renders the subgraph into the model's context
     * @param abstention Decides whether the evidence is worth answering from
     * @param budget     How much context an answer may carry
     * @param topK       How many documents each signal contributes
     * @param embedder   Embeds the question, or null when this node has no embedding endpoint
     */
    public GraphRagPipeline(QueryClassifier classifier, EntityLinker linker, EdgeStore edgeStore,
                            HybridRetriever retriever, SubgraphSerializer serializer,
                            AbstentionPolicy abstention, SerializationBudget budget, int topK,
                            QueryEmbedder embedder) {
        this.embedder = embedder;
        if (classifier == null || linker == null || edgeStore == null || retriever == null
                || serializer == null || abstention == null || budget == null) {
            throw new IllegalArgumentException("every pipeline component is required");
        }
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be at least 1, got " + topK);
        }
        this.classifier = classifier;
        this.linker = linker;
        this.edgeStore = edgeStore;
        this.retriever = retriever;
        this.serializer = serializer;
        this.abstention = abstention;
        this.budget = budget;
        this.topK = topK;
    }

    /** Answers a question with no conversation behind it. */
    public GraphRagResult answer(String question) {
        return answer(question, new ConversationContext(), new QueryTrace());
    }

    /**
     * Runs the whole pipeline, at whatever instant the question implies.
     *
     * @param question the user's question
     * @param context  what has been mentioned so far, for coreference
     * @param trace    receives per-stage latency
     */
    public GraphRagResult answer(String question, ConversationContext context, QueryTrace trace) {
        return answer(question, context, null, trace);
    }

    /**
     * Runs the whole pipeline at an instant the caller names.
     *
     * <p>Valid time was otherwise reachable only through the date words
     * {@link QueryClassifier} finds in the question, which is a guess at what the asker meant. A
     * client replaying a decision, auditing a contract or comparing two dates knows the instant
     * outright, and a question it phrases to suit the heuristic is a question it has had to
     * distort. An {@code asOf} given here wins over one read out of the text, because one is
     * stated and the other is inferred.</p>
     *
     * @param question the user's question
     * @param context  what has been mentioned so far, for coreference
     * @param asOf     the valid-time instant to answer at, or null to use the question's own
     * @param trace    receives per-stage latency
     */
    public GraphRagResult answer(String question, ConversationContext context,
                                 TemporalFilter asOf, QueryTrace trace) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (context == null || trace == null) {
            throw new IllegalArgumentException("context and trace must not be null");
        }

        PlanningOutcome planning = trace.time(Stage.PLAN, () -> classifier.classify(question, context));
        if (planning instanceof PlanningOutcome.AskUser ask) {
            return GraphRagResult.askUser(ask, trace);
        }
        QueryPlan plan = at(((PlanningOutcome.Planned) planning).plan(), asOf);

        long[] seeds = trace.time(Stage.LINK, () -> linkAll(plan));
        if (seeds.length == 0) {
            return GraphRagResult.declined(
                RetrievalOutcome.NoResults.of(RetrievalOutcome.Reason.UNRESOLVED_REFERENCE),
                plan, trace);
        }

        Subgraph subgraph = trace.time(Stage.TRAVERSE, () -> traverse(plan, seeds));

        List<FusedDocument> fused = retriever.retrieve(
            queryFor(retrievalTextFor(question, plan)), topK, trace);

        RetrievalOutcome outcome = abstention.decide(fused, subgraph);
        if (!(outcome instanceof RetrievalOutcome.Results results)) {
            // Serialising evidence the system does not believe in only gives the model material to
            // be confidently wrong with.
            return GraphRagResult.declined(outcome, plan, trace);
        }

        SeedRelevance relevance = relevanceOf(fused);
        SerializedSubgraph serialized = trace.time(Stage.SERIALIZE,
            () -> serializer.serialize(results.subgraph(), budget, relevance));

        LOGGER.debug("answered in {}", trace);
        return GraphRagResult.answered(results, serialized, plan, trace);
    }

    /**
     * The text the retrieval signals search on: the question plus the entities planning resolved.
     *
     * <p>Not the raw question. "Who did she collaborate with" contains nothing a lexical index can
     * match, so a question whose subject is a pronoun would retrieve nothing even though planning
     * bound it successfully — the graph would find the right entity and the ranking signals would
     * find no documents about it. Appending the resolved mentions makes retrieval see the same
     * subject the traversal does.
     *
     * <p>Appended rather than substituted, because the rest of the question carries the terms that
     * distinguish which facts about that entity are wanted.
     */
    private String retrievalTextFor(String question, QueryPlan plan) {
        StringBuilder text = new StringBuilder(question);
        for (String mention : plan.mentions()) {
            if (!question.toLowerCase(Locale.ROOT).contains(mention.toLowerCase(Locale.ROOT))) {
                text.append(' ').append(mention);
            }
        }
        return text.toString();
    }

    /**
     * Resolves every mention the plan named.
     *
     * <p>All candidates from every mention. Narrowing to one per mention here would make an
     * ambiguity invisible to abstention, which is the stage equipped to notice it.
     */
    private long[] linkAll(QueryPlan plan) {
        List<Long> seeds = new ArrayList<>();
        for (String mention : plan.mentions()) {
            for (long entity : linker.link(mention)) {
                if (!seeds.contains(entity)) {
                    seeds.add(entity);
                }
            }
        }
        long[] result = new long[seeds.size()];
        for (int i = 0; i < seeds.size(); i++) {
            result[i] = seeds.get(i);
        }
        return result;
    }

    /**
     * Puts a plan at the instant the caller named, replacing any the question implied.
     *
     * <p>A temporal plan cannot wrap another, so an inferred one is unwrapped first rather than
     * nested: the question has one as-of instant and this is it.</p>
     *
     * @param plan the plan planning produced
     * @param asOf the caller's instant, or null to leave the plan as it is
     * @return the plan to traverse with
     */
    private static QueryPlan at(QueryPlan plan, TemporalFilter asOf) {
        if (asOf == null) {
            return plan;
        }
        QueryPlan core = plan instanceof QueryPlan.Temporal temporal ? temporal.inner() : plan;
        return new QueryPlan.Temporal(core, asOf);
    }

    /**
     * Walks the graph as the plan directs, at the plan's instant.
     *
     * <p>Exhaustive over the sealed plan hierarchy, so a new plan type is a compile error here
     * rather than a silent fall-through to a default traversal.
     */
    private Subgraph traverse(QueryPlan plan, long[] seeds) {
        TemporalFilter asOf = plan instanceof QueryPlan.Temporal temporal
            ? temporal.asOf()
            : TemporalFilter.now();
        QueryPlan core = plan instanceof QueryPlan.Temporal temporal ? temporal.inner() : plan;

        int hops = switch (core) {
            case QueryPlan.Lookup ignored -> 1;
            case QueryPlan.Traverse traverse -> traverse.hops();
            // An aggregate is answered from the offset array, so it needs the seeds' immediate
            // edges and nothing beyond them.
            case QueryPlan.Aggregate ignored -> 1;
            case QueryPlan.Temporal ignored -> throw new IllegalStateException(
                "a temporal plan cannot wrap another; construction already forbids it");
        };

        long[] reachable = edgeStore.expand(seeds, hops, null, asOf);
        return collect(seeds, reachable, asOf);
    }

    /**
     * Materialises the edges between the reached entities.
     *
     * <p>Only for a {@link MappedEdgeStore}, which is the only implementation that can report an
     * edge's columns. Another implementation yields the entities alone — enough to answer, but with
     * no provenance to show, so abstention will treat it as weak evidence rather than silently
     * treating it as strong.
     */
    private Subgraph collect(long[] seeds, long[] reachable, TemporalFilter asOf) {
        Subgraph.Builder builder = new Subgraph.Builder();
        for (long seed : seeds) {
            builder.seed(seed);
        }
        if (!(edgeStore instanceof MappedEdgeStore mapped)) {
            return builder.build();
        }

        // Bitmaps over entity ids rather than boxed sets: the membership test runs once per edge
        // examined, and RoaringBitmap is already what the traversal itself works in.
        RoaringBitmap inScope = new RoaringBitmap();
        for (long entity : reachable) {
            inScope.add((int) entity);
        }
        RoaringBitmap seedSet = new RoaringBitmap();
        for (long seed : seeds) {
            seedSet.add((int) seed);
        }

        // Only the entities the traversal reached, rather than every entity in the store. The
        // subgraph is a handful of entities and the store can hold tens of millions of edges,
        // so a full scan made the cost of answering a question grow with the size of the graph
        // instead of with the size of the answer.
        for (long entity : reachable) {
            mapped.forEachEdgeOf(entity, (subject, relation, object, index) -> {
                if (!inScope.contains(object)) {
                    return;
                }
                int validFrom = mapped.validFrom(index);
                int validTo = mapped.validTo(index);
                if (!asOf.admits(validFrom, validTo)) {
                    return;
                }
                Provenance provenance = mapped.provenanceOf(index);
                int depth = seedSet.contains(subject) || seedSet.contains(object) ? 1 : 2;
                builder.edge(new SubgraphEdge(subject, relation, object, index,
                    validFrom, validTo, provenance, depth));
            });
        }
        return builder.build();
    }

    /**
     * The retrieval query, embedded when this node can embed it.
     *
     * <p>An embedding that cannot be produced is not a failure of the question. The endpoint is
     * behind the same circuit breaker as every other model call, and a node whose embedder is
     * briefly unavailable should answer by term overlap rather than not answer - so the failure
     * degrades the query to text and the ranking chain drops its exact rerank accordingly.</p>
     *
     * @param text The text to retrieve on
     * @return The query, with an embedding when one could be produced
     */
    private RetrievalQuery queryFor(String text) {
        if (embedder == null) {
            return RetrievalQuery.ofText(text);
        }
        try {
            float[] embedding = embedder.embed(text);
            return embedding == null
                ? RetrievalQuery.ofText(text)
                : RetrievalQuery.of(text, embedding);
        } catch (RuntimeException e) {
            LOGGER.warn("could not embed the question; retrieving on text alone: {}", e.toString());
            return RetrievalQuery.ofText(text);
        }
    }

    /**
     * Turns a question into a vector.
     *
     * <p>An interface rather than the embedding client, so the pipeline does not depend on which
     * model produced it and a test can supply its own.</p>
     */
    @FunctionalInterface
    public interface QueryEmbedder {

        /**
         * Embeds the text a query will retrieve on.
         *
         * @param text The text
         * @return Its vector, or null when there is none to be had
         */
        float[] embed(String text);
    }

    /** Fused scores as per-entity relevance, for ranking what fits in the budget. */
    private SeedRelevance relevanceOf(List<FusedDocument> fused) {
        java.util.Map<Long, Double> scores = new java.util.HashMap<>();
        for (FusedDocument document : fused) {
            scores.merge(document.documentId(), document.score(), Math::max);
        }
        return entityId -> scores.getOrDefault(entityId, 0.0);
    }
}
