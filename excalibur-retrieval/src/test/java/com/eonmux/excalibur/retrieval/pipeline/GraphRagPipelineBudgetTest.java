package com.eonmux.excalibur.retrieval.pipeline;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.eonmux.excalibur.core.trace.QueryTrace;
import com.eonmux.excalibur.core.util.QueryBudget;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.store.EdgeStoreWriter;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.subgraph.SubgraphEdge;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.planner.ConversationContext;
import com.eonmux.excalibur.planner.PlanningOutcome;
import com.eonmux.excalibur.planner.QueryClassifier;
import com.eonmux.excalibur.planner.QueryPlan;
import com.eonmux.excalibur.retrieval.HybridRetriever;
import com.eonmux.excalibur.retrieval.abstain.RetrievalOutcome;
import com.eonmux.excalibur.retrieval.abstain.ThresholdAbstentionPolicy;
import com.eonmux.excalibur.retrieval.fusion.ScorePassthrough;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;
import com.eonmux.excalibur.retrieval.lexical.LuceneRanker;
import com.eonmux.excalibur.retrieval.serialize.BpeTokenCounter;
import com.eonmux.excalibur.retrieval.serialize.SerializationBudget;
import com.eonmux.excalibur.retrieval.serialize.TripleSubgraphSerializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A graph question's deadline, cancellation and walk limits, end to end over a hub: one entity
 * with thirty neighbours, each fact about it extracted with a different confidence.
 */
class GraphRagPipelineBudgetTest {

    private static final int HUB = 1;
    private static final int FIRST_SPOKE = 2;
    private static final int SPOKES = 30;
    private static final int ENTITY_COUNT = FIRST_SPOKE + SPOKES;
    private static final int CONNECTED_TO = 0;
    private static final String QUESTION = "Who did Grand Central collaborate with?";

    private MappedEdgeStore edgeStore;
    private LexicalIndex lexicalIndex;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        EdgeStoreWriter writer = new EdgeStoreWriter();
        for (int spoke = FIRST_SPOKE; spoke < ENTITY_COUNT; spoke++) {
            writer.add(HUB, CONNECTED_TO, spoke, Integer.MIN_VALUE, TemporalFilter.OPEN,
                new Provenance(SourceType.DOCUMENT, spoke, confidenceOf(spoke), 1));
        }
        writer.build(root.resolve("graph"), ENTITY_COUNT);
        edgeStore = MappedEdgeStore.open(root.resolve("graph"));

        lexicalIndex = LexicalIndex.open(root.resolve("lucene"), 16.0);
        lexicalIndex.put(HUB, "Grand Central the hub every line collaborates through");
        for (int spoke = FIRST_SPOKE; spoke < ENTITY_COUNT; spoke++) {
            lexicalIndex.put(spoke, "Stop " + spoke + " a station on one line");
        }
        lexicalIndex.refresh();
    }

    @AfterEach
    void tearDown() {
        if (edgeStore != null) {
            edgeStore.close();
        }
        if (lexicalIndex != null) {
            lexicalIndex.close();
        }
    }

    /** The later the spoke, the more confident its extraction: 50 up to 79. */
    private static int confidenceOf(int spoke) {
        return 50 + (spoke - FIRST_SPOKE);
    }

    private GraphRagPipeline pipeline(WalkLimits limits,
                                      GraphRagPipeline.QueryEmbedder embedder) {
        EntityLinker linker = mention -> mention.equalsIgnoreCase("Grand Central")
            ? new long[] {HUB}
            : new long[0];
        return new GraphRagPipeline(
            new QueryClassifier(),
            linker,
            edgeStore,
            new HybridRetriever(List.of(
                new LuceneRanker(lexicalIndex, "bm25", LuceneRanker.Scale.ATTAINABLE_SHARE)),
                new ScorePassthrough(), HybridRetriever.DEFAULT_PER_SIGNAL_OVER_FETCH),
            new TripleSubgraphSerializer(id -> id == HUB ? "Grand Central" : "Stop " + id,
                relation -> "connectedTo"),
            new ThresholdAbstentionPolicy(0.005, 0.001, 0.3, 5),
            new SerializationBudget(4000, BpeTokenCounter.defaultCounter()),
            10,
            embedder,
            null,
            null,
            limits);
    }

    private static List<Integer> objectsOf(GraphRagResult result) {
        return result.results().orElseThrow().subgraph().edges().stream()
            .map(SubgraphEdge::object).sorted().toList();
    }

    @Test
    @DisplayName("a hub's walk admits only its strongest neighbours, and says how many it left")
    void hubFrontierIsCapped() {
        GraphRagResult result = pipeline(new WalkLimits(5, 1000), null).answer(QUESTION,
            new ConversationContext(), null, TraversalDirection.BOTH, new QueryTrace(),
            QueryBudget.unbounded());

        assertThat(result.hasAnswer()).isTrue();
        assertThat(objectsOf(result)).containsExactly(27, 28, 29, 30, 31);
        WalkTruncation walk = result.walk().orElseThrow();
        assertThat(walk.truncated()).isTrue();
        assertThat(walk.entitiesDropped()).isEqualTo(SPOKES - 5);
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    @DisplayName("the same capped question gets the same answer every time")
    void cappedWalkIsDeterministic() {
        GraphRagPipeline pipeline = pipeline(new WalkLimits(7, 1000), null);

        List<Integer> first = objectsOf(pipeline.answer(QUESTION));
        for (int i = 0; i < 5; i++) {
            assertThat(objectsOf(pipeline.answer(QUESTION))).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("a walk that passes more facts than it may collect keeps the most confident")
    void factsAreCapped() {
        GraphRagResult result = pipeline(new WalkLimits(1000, 3), null).answer(QUESTION);

        assertThat(objectsOf(result)).containsExactly(29, 30, 31);
        assertThat(result.walk().orElseThrow())
            .isEqualTo(new WalkTruncation(0, SPOKES - 3));
    }

    @Test
    @DisplayName("a walk inside its limits reports nothing left out")
    void uncappedWalkIsNotTruncated() {
        GraphRagResult result = pipeline(WalkLimits.defaults(), null).answer(QUESTION);

        assertThat(objectsOf(result)).hasSize(SPOKES);
        assertThat(result.walk()).contains(WalkTruncation.NONE);
    }

    @Test
    @DisplayName("a deadline already passed before the walk declines the question as timed out")
    void deadlineBeforeTheWalkDeclines() {
        GraphRagResult result = pipeline(WalkLimits.defaults(), null).answer(QUESTION,
            new ConversationContext(), null, TraversalDirection.BOTH, new QueryTrace(),
            QueryBudget.until(System.nanoTime() - 1));

        assertThat(result.hasAnswer()).isFalse();
        assertThat(result.timedOut()).isTrue();
        assertThat(result.declined()).map(RetrievalOutcome.NoResults::reason)
            .contains(RetrievalOutcome.Reason.TIMED_OUT);
        assertThat(result.plan()).isPresent();
        assertThat(result.walk()).isEmpty();
    }

    @Test
    @DisplayName("an embedding slower than the deadline is abandoned and the question answered on text")
    void slowEmbedderIsAbandoned() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        GraphRagPipeline.QueryEmbedder hanging = text -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return new float[] {1f};
        };
        QueryBudget budget = QueryBudget.ofMillis(300);

        long begun = System.nanoTime();
        GraphRagResult result = pipeline(WalkLimits.defaults(), hanging).answer(QUESTION,
            new ConversationContext(), null, TraversalDirection.BOTH, new QueryTrace(), budget);
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun);

        assertThat(tookMillis).as("bounded by the deadline, not the embedder").isLessThan(5_000);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(result.timedOut()).isTrue();
        assertThat(result.hasAnswer())
            .as("the walk finished inside the deadline, so its facts are still the answer")
            .isTrue();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!interrupted.get() && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertThat(interrupted).isTrue();
    }

    @Test
    @DisplayName("an embedding inside the deadline leaves the answer untouched by it")
    void embedderInsideTheDeadline() {
        QueryBudget budget = QueryBudget.ofMillis(30_000);

        GraphRagResult result = pipeline(WalkLimits.defaults(), text -> new float[] {1f})
            .answer(QUESTION, new ConversationContext(), null, TraversalDirection.BOTH,
                new QueryTrace(), budget);

        assertThat(result.hasAnswer()).isTrue();
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    @DisplayName("an embedder that fails still leaves an answer on text, without a timeout")
    void failingEmbedderDegradesToText() {
        GraphRagResult result = pipeline(WalkLimits.defaults(), text -> {
            throw new IllegalStateException("endpoint down");
        }).answer(QUESTION, new ConversationContext(), null, TraversalDirection.BOTH,
            new QueryTrace(), QueryBudget.ofMillis(30_000));

        assertThat(result.hasAnswer()).isTrue();
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    @DisplayName("a query cancelled before it starts ends with a cancellation, not an answer")
    void cancelledQueryThrows() {
        QueryBudget budget = QueryBudget.ofMillis(30_000);
        budget.cancel("Withdrawn by the caller");

        assertThatThrownBy(() -> pipeline(WalkLimits.defaults(), null).answer(QUESTION,
            new ConversationContext(), null, TraversalDirection.BOTH, new QueryTrace(), budget))
            .isInstanceOf(CancellationException.class)
            .hasMessageContaining("Withdrawn by the caller");
    }

    @Test
    @DisplayName("cancelling while the question is being linked stops it before the walk")
    void cancelledDuringLinking() {
        QueryBudget budget = QueryBudget.ofMillis(30_000);
        AtomicBoolean walked = new AtomicBoolean();
        EntityLinker cancelling = mention -> {
            budget.cancel("Withdrawn by the caller");
            return new long[] {HUB};
        };
        GraphRagPipeline pipeline = new GraphRagPipeline(new QueryClassifier(), cancelling,
            edgeStore,
            new HybridRetriever(List.of(new LuceneRanker(lexicalIndex, "bm25",
                LuceneRanker.Scale.ATTAINABLE_SHARE)), new ScorePassthrough(),
                HybridRetriever.DEFAULT_PER_SIGNAL_OVER_FETCH),
            new TripleSubgraphSerializer(id -> "E" + id, relation -> "R" + relation),
            new ThresholdAbstentionPolicy(0.005, 0.001, 0.3, 5),
            new SerializationBudget(4000, BpeTokenCounter.defaultCounter()), 10, text -> {
                walked.set(true);
                return null;
            }, null, null, WalkLimits.defaults());

        assertThatThrownBy(() -> pipeline.answer(QUESTION, new ConversationContext(), null,
            TraversalDirection.BOTH, new QueryTrace(), budget))
            .isInstanceOf(CancellationException.class);
        assertThat(walked).as("nothing after linking ran").isFalse();
    }

    @Test
    @DisplayName("a walk cancelled while it runs ends the query")
    void cancelledWalkThrows() {
        QueryBudget budget = QueryBudget.ofMillis(30_000);
        budget.cancel("Withdrawn by the caller");
        GraphWalker walker = new GraphWalker(edgeStore, null, WalkLimits.defaults());

        assertThatThrownBy(() -> walker.walk(planFor(QUESTION), new long[] {HUB},
            TraversalDirection.BOTH, budget))
            .isInstanceOf(CancellationException.class);
    }

    @Test
    @DisplayName("a walk whose deadline passes keeps its seeds, collects nothing more, and says so")
    void expiredWalkStopsWhereItIs() {
        QueryBudget budget = QueryBudget.until(System.nanoTime() - 1);
        GraphWalker walker = new GraphWalker(edgeStore, null, WalkLimits.defaults());

        GraphWalker.Walk walk = walker.walk(planFor(QUESTION), new long[] {HUB},
            TraversalDirection.BOTH, budget);

        assertThat(walk.subgraph().seeds()).containsExactly(HUB);
        assertThat(walk.subgraph().edges()).isEmpty();
        assertThat(budget.timedOut()).isTrue();
    }

    @Test
    @DisplayName("gathering refuses a missing budget")
    void budgetIsRequired() {
        assertThatThrownBy(() -> pipeline(WalkLimits.defaults(), null).gather(QUESTION,
            new ConversationContext(), null, TraversalDirection.BOTH, new QueryTrace(), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pipeline(null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("gathering reports the walk's truncation with the evidence")
    void gatheredEvidenceCarriesTheTruncation() {
        Evidence evidence = pipeline(new WalkLimits(4, 1000), null).gather(QUESTION,
            new ConversationContext(), null, TraversalDirection.BOTH, new QueryTrace(),
            QueryBudget.unbounded());

        assertThat(evidence).isInstanceOf(Evidence.Gathered.class);
        assertThat(((Evidence.Gathered) evidence).walk().entitiesDropped()).isEqualTo(SPOKES - 4);
    }

    private static QueryPlan planFor(String question) {
        PlanningOutcome planning = new QueryClassifier().classify(question);
        return ((PlanningOutcome.Planned) planning).plan();
    }
}
