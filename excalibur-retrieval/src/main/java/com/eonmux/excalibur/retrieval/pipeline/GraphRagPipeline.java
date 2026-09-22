package com.eonmux.excalibur.retrieval.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;

import com.eonmux.excalibur.core.trace.QueryTrace;
import com.eonmux.excalibur.core.trace.Stage;
import com.eonmux.excalibur.graph.store.EdgeStore;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.subgraph.Subgraph;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.planner.ConversationContext;
import com.eonmux.excalibur.planner.Mention;
import com.eonmux.excalibur.planner.PlanningOutcome;
import com.eonmux.excalibur.planner.QueryClassifier;
import com.eonmux.excalibur.planner.QueryPlan;
import com.eonmux.excalibur.retrieval.HybridRetriever;
import com.eonmux.excalibur.retrieval.abstain.AbstentionPolicy;
import com.eonmux.excalibur.retrieval.abstain.RetrievalOutcome;
import com.eonmux.excalibur.retrieval.fusion.FusedDocument;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import com.eonmux.excalibur.retrieval.serialize.SeedRelevance;
import com.eonmux.excalibur.retrieval.serialize.SerializationBudget;
import com.eonmux.excalibur.retrieval.serialize.SerializedSubgraph;
import com.eonmux.excalibur.retrieval.serialize.SubgraphSerializer;

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
 * An abstention that found several readings of one name it could not tell apart goes back to the
 * user as a question, the same as an unbound pronoun, because one word from the user settles it.
 * The conversation the caller sends is what that word arrives in: a name linked to several
 * entities resolves to the ones the conversation names, and the rest are kept out of the walk and
 * the ranking both.
 */
public final class GraphRagPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphRagPipeline.class);

    private final QueryClassifier classifier;
    private final EntityLinker linker;
    private final GraphWalker walker;
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

    /**
     * Names entities for the people a question goes back to.
     *
     * <p>An ambiguity is put to someone as names, and it is a name they send back - so the same
     * names are what the conversation is matched against. Without a dictionary an entity is
     * called by its id, which is still a name that round-trips.</p>
     */
    private final LongFunction<String> entityNames;

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
        this(classifier, linker, edgeStore, retriever, serializer, abstention, budget, topK,
            embedder, null);
    }

    /**
     * Creates a pipeline that can also narrow a traversal to the relations a question names.
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
     * @param relations  Resolves the plan's relation hints, or null to traverse every relation
     */
    public GraphRagPipeline(QueryClassifier classifier, EntityLinker linker, EdgeStore edgeStore,
                            HybridRetriever retriever, SubgraphSerializer serializer,
                            AbstentionPolicy abstention, SerializationBudget budget, int topK,
                            QueryEmbedder embedder, RelationResolver relations) {
        this(classifier, linker, edgeStore, retriever, serializer, abstention, budget, topK,
            embedder, relations, null);
    }

    /**
     * Creates a pipeline that can put an ambiguity to the user by name.
     *
     * @param classifier  Turns the question into a plan
     * @param linker      Resolves mentions to entities
     * @param edgeStore   The graph to traverse
     * @param retriever   The retrieval signals and their fusion
     * @param serializer  Renders the subgraph into the model's context
     * @param abstention  Decides whether the evidence is worth answering from
     * @param budget      How much context an answer may carry
     * @param topK        How many documents each signal contributes
     * @param embedder    Embeds the question, or null when this node has no embedding endpoint
     * @param relations   Resolves the plan's relation hints, or null to traverse every relation
     * @param entityNames Names entities, or null to call each by its id
     */
    public GraphRagPipeline(QueryClassifier classifier, EntityLinker linker, EdgeStore edgeStore,
                            HybridRetriever retriever, SubgraphSerializer serializer,
                            AbstentionPolicy abstention, SerializationBudget budget, int topK,
                            QueryEmbedder embedder, RelationResolver relations,
                            LongFunction<String> entityNames) {
        this.embedder = embedder;
        this.entityNames = entityNames != null ? entityNames : id -> "E" + id;
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
        this.walker = new GraphWalker(edgeStore, relations);
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
        return answer(question, context, asOf, TraversalDirection.BOTH, trace);
    }

    /**
     * Runs the whole pipeline at an instant the caller names, stepping one way if it asks to.
     *
     * @param question  the user's question
     * @param context   what has been mentioned so far, for coreference and disambiguation
     * @param asOf      the valid-time instant to answer at, or null to use the question's own
     * @param direction which way along an edge the traversal may step
     * @param trace     receives per-stage latency
     */
    public GraphRagResult answer(String question, ConversationContext context,
                                 TemporalFilter asOf, TraversalDirection direction,
                                 QueryTrace trace) {
        return switch (gather(question, context, asOf, direction, trace)) {
            case Evidence.NeedsUser needsUser -> GraphRagResult.askUser(needsUser.ask(), trace);
            case Evidence.Unresolved unresolved -> GraphRagResult.declined(
                RetrievalOutcome.NoResults.of(RetrievalOutcome.Reason.UNRESOLVED_REFERENCE),
                unresolved.plan(), trace);
            case Evidence.Gathered gathered -> judge(gathered, trace);
        };
    }

    /**
     * Runs the pipeline up to, but not including, the decision about whether to answer.
     *
     * <p>Everything {@link #answer} does before abstention, and the same code path: answer is
     * written in terms of this rather than beside it, so a harness that fits thresholds against
     * what this returns is fitting the retrieval a served query actually gets. Two
     * implementations would be two pipelines, and the thresholds would be measured on the one
     * nobody runs.
     *
     * <p>Public for that harness. Serving has no use for it - a served request wants an answer,
     * and the stage that decides whether there is one is the stage this stops short of.
     *
     * @param question the user's question
     * @param context  what has been mentioned so far, for coreference
     * @param asOf     the valid-time instant to answer at, or null to use the question's own
     * @param trace    receives per-stage latency
     * @return the evidence, or the reason the question never reached retrieval
     */
    public Evidence gather(String question, ConversationContext context, TemporalFilter asOf,
                           QueryTrace trace) {
        return gather(question, context, asOf, TraversalDirection.BOTH, trace);
    }

    /**
     * As {@link #gather(String, ConversationContext, TemporalFilter, QueryTrace)}, stepping one
     * way along each edge if the caller asks to.
     *
     * @param question  the user's question
     * @param context   what has been mentioned so far, for coreference and disambiguation
     * @param asOf      the valid-time instant to answer at, or null to use the question's own
     * @param direction which way along an edge the traversal may step
     * @param trace     receives per-stage latency
     * @return the evidence, or the reason the question never reached retrieval
     * @throws IllegalStateException if a one-way walk is asked of a pipeline with no relation
     *                               vocabulary to name the relations with
     */
    public Evidence gather(String question, ConversationContext context, TemporalFilter asOf,
                           TraversalDirection direction, QueryTrace trace) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (context == null || trace == null || direction == null) {
            throw new IllegalArgumentException("context, direction and trace must not be null");
        }
        if (direction != TraversalDirection.BOTH && !walker.canRestrictDirection()) {
            throw new IllegalStateException("a " + direction.wireName() + " traversal needs the "
                + "graph's relation vocabulary, and this pipeline was built without one");
        }

        PlanningOutcome planning =
            trace.time(Stage.PLAN, () -> classifier.classify(question, context));
        if (planning instanceof PlanningOutcome.AskUser ask) {
            return new Evidence.NeedsUser(ask);
        }
        QueryPlan plan = at(((PlanningOutcome.Planned) planning).plan(), asOf);

        Linked linked = trace.time(Stage.LINK, () -> linkAll(plan, namedBy(context)));
        if (linked.seeds().length == 0) {
            return new Evidence.Unresolved(plan);
        }

        Subgraph subgraph =
            trace.time(Stage.TRAVERSE, () -> walker.walk(plan, linked.seeds(), direction));
        List<FusedDocument> ranked = without(retriever.retrieve(
            queryFor(retrievalTextFor(question, plan)), topK, trace), linked.ruledOut());
        return new Evidence.Gathered(readings(ranked, linked.readings()), ranked, subgraph, plan);
    }

    /**
     * The ranked entries abstention judges: the readings of what the question named.
     *
     * <p>Not the whole ranking. The answer is the walk from the linked entities, so the evidence
     * for answering is how well <em>they</em> matched, and an ambiguity is several entities
     * competing for one name the question used. An entity the question did not name is neither:
     * it may tie with the named one - every entity extracted from one snippet is described by
     * that snippet, and scores exactly alike - without the question being in any doubt about whom
     * it asked about. Judged over the whole ranking, that tie would hand back a "which did you
     * mean" between an entity the question named and one it never mentioned.
     *
     * <p>So: when a mention resolved to several entities that retrieval ranked, the question is
     * judged on the one in most doubt - the mention whose two best readings score closest - and
     * the gap between them is what separation measures. When no mention is in doubt, it is judged
     * on its best-matching named entity alone, and only the score gate applies.
     *
     * @param ranked   every entity retrieval ranked, best first
     * @param readings the entities each mention resolved to, one array per mention
     * @return the entries to judge, best first; empty when retrieval ranked no named entity
     */
    private static List<FusedDocument> readings(List<FusedDocument> ranked,
                                                List<long[]> readings) {
        List<FusedDocument> contested = List.of();
        double closest = Double.NEGATIVE_INFINITY;
        FusedDocument best = null;
        for (long[] mention : readings) {
            List<FusedDocument> entries = new ArrayList<>();
            for (FusedDocument document : ranked) {
                if (contains(mention, document.documentId())) {
                    entries.add(document);
                }
            }
            if (!entries.isEmpty() && (best == null || entries.get(0).score() > best.score())) {
                best = entries.get(0);
            }
            if (entries.size() > 1) {
                double closeness = closeness(entries.get(0), entries.get(1));
                if (closeness > closest) {
                    closest = closeness;
                    contested = entries;
                }
            }
        }
        if (!contested.isEmpty()) {
            return contested;
        }
        return best == null ? List.of() : List.of(best);
    }

    /**
     * How near the runner-up comes to the best, as a fraction of the best; higher is closer.
     *
     * <p>Only for choosing which mention is in most doubt. A best score of zero or less has no
     * fraction to take, and two such readings are as close as readings get.
     */
    private static double closeness(FusedDocument best, FusedDocument second) {
        return best.score() > 0 ? second.score() / best.score() : 1.0;
    }

    private static boolean contains(long[] values, long wanted) {
        for (long value : values) {
            if (value == wanted) {
                return true;
            }
        }
        return false;
    }

    /** Applies the abstention policy to gathered evidence, and serialises what survives it. */
    private GraphRagResult judge(Evidence.Gathered gathered, QueryTrace trace) {
        RetrievalOutcome outcome = abstention.decide(gathered.fused(), gathered.subgraph());
        return switch (outcome) {
            case RetrievalOutcome.Ambiguous ambiguous ->
                GraphRagResult.ambiguous(labelled(ambiguous), gathered.plan(), trace);
            // Serialising evidence the system does not believe in only gives the model material to
            // be confidently wrong with.
            case RetrievalOutcome.NoResults refused ->
                GraphRagResult.declined(refused, gathered.plan(), trace);
            case RetrievalOutcome.Results results ->
                answered(results, gathered.ranked(), gathered.plan(), trace);
        };
    }

    /** Serialises the evidence abstention accepted, led by the aggregate when there is one. */
    private GraphRagResult answered(RetrievalOutcome.Results results, List<FusedDocument> fused,
                                    QueryPlan plan, QueryTrace trace) {
        Aggregation aggregation = aggregationOf(plan, results.subgraph());
        SeedRelevance relevance = relevanceOf(fused);
        SerializedSubgraph serialized = trace.time(Stage.SERIALIZE,
            () -> serialize(results.subgraph(), relevance, aggregation, plan));

        LOGGER.debug("answered in {}", trace);
        return GraphRagResult.answered(results, serialized, plan, aggregation, trace);
    }

    /**
     * The aggregate an aggregate plan asked for, or null for any other plan.
     *
     * <p>Null too for a store that cannot report its edges: the count of facts it did not
     * report would be zero, and zero is an answer, not an absence of one.
     */
    private Aggregation aggregationOf(QueryPlan plan, Subgraph subgraph) {
        if (!(plan.shape() instanceof QueryPlan.Aggregate aggregate)
                || !(edgeStore instanceof MappedEdgeStore)) {
            return null;
        }
        return Aggregation.over(aggregate.function(), subgraph, entityNames);
    }

    /**
     * Serialises the evidence, led by the aggregate when there is one.
     *
     * <p>The aggregate's line is charged against the same budget as the facts, before them: it is
     * the answer the question asked for, and the facts are the evidence for it. When the budget
     * cannot hold even the line, the facts alone are sent and the aggregate still reaches the
     * caller beside the context.
     */
    private SerializedSubgraph serialize(Subgraph subgraph, SeedRelevance relevance,
                                         Aggregation aggregation, QueryPlan plan) {
        if (aggregation == null) {
            return serializer.serialize(subgraph, budget, relevance);
        }
        String line = "# aggregate\n" + aggregation.describe(plan.mentions()) + "\n";
        int cost = budget.counter().count(line);
        if (cost > budget.maxTokens()) {
            return serializer.serialize(subgraph, budget, relevance);
        }
        SerializedSubgraph facts = serializer.serialize(subgraph,
            new SerializationBudget(budget.maxTokens() - cost, budget.counter()), relevance);
        String text = line + facts.text();
        return new SerializedSubgraph(text, budget.counter().count(text), facts.edgesIncluded(),
            facts.edgesDropped(), facts.format());
    }

    /** The same ambiguity, with each reading named so it can be put to a person. */
    private RetrievalOutcome.Ambiguous labelled(RetrievalOutcome.Ambiguous ambiguous) {
        List<RetrievalOutcome.Candidate> labelled = new ArrayList<>(ambiguous.candidates().size());
        for (RetrievalOutcome.Candidate candidate : ambiguous.candidates()) {
            labelled.add(new RetrievalOutcome.Candidate(candidate.entityId(),
                entityNames.apply(candidate.entityId()), candidate.score()));
        }
        return new RetrievalOutcome.Ambiguous(labelled);
    }

    /** The names the conversation mentions, normalised for matching against entity names. */
    private static Set<String> namedBy(ConversationContext context) {
        Set<String> named = new HashSet<>();
        for (Mention mention : context.visibleMentions()) {
            named.add(normalized(mention.text()));
        }
        return named;
    }

    private static String normalized(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** The fused list without the given entities, order kept. */
    private static List<FusedDocument> without(List<FusedDocument> fused, Set<Long> excluded) {
        if (excluded.isEmpty()) {
            return fused;
        }
        List<FusedDocument> kept = new ArrayList<>(fused.size());
        for (FusedDocument document : fused) {
            if (!excluded.contains(document.documentId())) {
                kept.add(document);
            }
        }
        return kept;
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
     *
     * <p>Unless the conversation has already narrowed it. A mention that resolves to several
     * entities, some of which the conversation names, resolves to those: the caller has said which
     * one it means, typically by sending back a candidate an earlier ambiguity offered, by name
     * or, where entities share the name, by {@link InputRequest#qualifiedName}. The rest
     * are ruled out - kept out of the walk, so their facts do not reach the model, and out of the
     * ranking, so abstention does not find the same ambiguity a second time.
     *
     * @param plan  the plan whose mentions to resolve
     * @param named the names the conversation mentions, normalised
     * @return the seeds, and the entities the conversation ruled out
     */
    private Linked linkAll(QueryPlan plan, Set<String> named) {
        List<Long> seeds = new ArrayList<>();
        Set<Long> ruledOut = new HashSet<>();
        List<long[]> readings = new ArrayList<>();
        for (String mention : plan.mentions()) {
            long[] candidates = linker.link(mention);
            List<Long> chosen = new ArrayList<>();
            if (candidates.length > 1) {
                for (long entity : candidates) {
                    String name = entityNames.apply(entity);
                    if (named.contains(normalized(name))
                            || named.contains(normalized(InputRequest.qualifiedName(name, entity)))) {
                        chosen.add(entity);
                    }
                }
            }
            List<Long> kept = new ArrayList<>();
            for (long entity : candidates) {
                if (chosen.isEmpty() || chosen.contains(entity)) {
                    kept.add(entity);
                    if (!seeds.contains(entity)) {
                        seeds.add(entity);
                    }
                } else {
                    ruledOut.add(entity);
                }
            }
            readings.add(kept.stream().mapToLong(Long::longValue).toArray());
        }
        // An entity another mention resolved to on its own is wanted for that mention.
        seeds.forEach(ruledOut::remove);
        return new Linked(seeds.stream().mapToLong(Long::longValue).toArray(), readings,
            ruledOut);
    }

    /**
     * What linking produced.
     *
     * @param seeds    the entities to walk from
     * @param readings the entities each mention resolved to, one array per mention
     * @param ruledOut the entities the conversation passed over in favour of a named one
     */
    private record Linked(long[] seeds, List<long[]> readings, Set<Long> ruledOut) {
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
        Map<Long, Double> scores = new HashMap<>();
        for (FusedDocument document : fused) {
            scores.merge(document.documentId(), document.score(), Math::max);
        }
        return entityId -> scores.getOrDefault(entityId, 0.0);
    }
}
