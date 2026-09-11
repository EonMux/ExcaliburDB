package io.excalibur.retrieval.pipeline;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import io.excalibur.core.trace.QueryTrace;
import io.excalibur.core.trace.Stage;
import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.store.EdgeStoreWriter;
import io.excalibur.graph.store.MappedEdgeStore;
import io.excalibur.graph.temporal.SupersessionPolicy;
import io.excalibur.graph.temporal.TemporalFilter;
import io.excalibur.planner.ConversationContext;
import io.excalibur.planner.Grammar;
import io.excalibur.planner.QueryClassifier;
import io.excalibur.retrieval.HybridRetriever;
import io.excalibur.retrieval.abstain.AbstentionPolicy;
import io.excalibur.retrieval.abstain.RetrievalOutcome;
import io.excalibur.retrieval.abstain.ThresholdAbstentionPolicy;
import io.excalibur.retrieval.lexical.LexicalIndex;
import io.excalibur.retrieval.lexical.LuceneRanker;
import io.excalibur.retrieval.serialize.BpeTokenCounter;
import io.excalibur.retrieval.serialize.SerializationBudget;
import io.excalibur.retrieval.serialize.TripleSubgraphSerializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end over all ten pieces: a real mapped edge store with bitemporal and provenance columns,
 * a real Lucene index, real fusion, real token counting, real abstention.
 */
class GraphRagPipelineTest {

    // Entities.
    private static final int ADA = 1;
    private static final int BABBAGE = 2;
    private static final int ANALYTICAL_ENGINE = 3;
    private static final int NOTE_G = 4;
    private static final int LONDON = 5;
    private static final int TURIN = 6;
    private static final int ENTITY_COUNT = 16;

    // Relations.
    private static final int COLLABORATED_WITH = 0;
    private static final int AUTHORED = 1;
    private static final int LIVED_IN = 2;
    private static final int DESIGNED = 3;

    private static final Map<Long, String> LABELS = Map.of(
        (long) ADA, "Ada Lovelace",
        (long) BABBAGE, "Charles Babbage",
        (long) ANALYTICAL_ENGINE, "Analytical Engine",
        (long) NOTE_G, "Note G",
        (long) LONDON, "London",
        (long) TURIN, "Turin");

    private static final Map<Long, String> RELATION_NAMES = Map.of(
        (long) COLLABORATED_WITH, "collaboratedWith",
        (long) AUTHORED, "authored",
        (long) LIVED_IN, "livedIn",
        (long) DESIGNED, "designed");

    private static int day(int year, int month, int dayOfMonth) {
        return Math.toIntExact(LocalDate.of(year, month, dayOfMonth).toEpochDay());
    }

    private MappedEdgeStore edgeStore;
    private LexicalIndex lexicalIndex;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        Path graphDir = root.resolve("graph");
        EdgeStoreWriter writer = new EdgeStoreWriter()
            .supersessionPolicy(SupersessionPolicy.forRelations(LIVED_IN));

        Provenance curated = new Provenance(SourceType.KNOWLEDGE_BASE, 1, 98, 7);
        Provenance extracted = new Provenance(SourceType.DOCUMENT, 2, 75, 7);

        writer.add(ADA, COLLABORATED_WITH, BABBAGE, Integer.MIN_VALUE, TemporalFilter.OPEN, curated);
        writer.add(ADA, AUTHORED, NOTE_G, day(1843, 1, 1), TemporalFilter.OPEN, curated);
        writer.add(BABBAGE, DESIGNED, ANALYTICAL_ENGINE, day(1837, 1, 1), TemporalFilter.OPEN,
            curated);
        // Two homes, on a single-valued relation: supersession must close the first.
        writer.add(ADA, LIVED_IN, LONDON, day(1815, 1, 1), TemporalFilter.OPEN, extracted);
        writer.add(ADA, LIVED_IN, TURIN, day(1841, 1, 1), TemporalFilter.OPEN, extracted);
        writer.build(graphDir, ENTITY_COUNT);
        edgeStore = MappedEdgeStore.open(graphDir);

        lexicalIndex = LexicalIndex.open(root.resolve("lucene"), 16.0);
        lexicalIndex.put(ADA, "Ada Lovelace mathematician who wrote Note G");
        lexicalIndex.put(BABBAGE, "Charles Babbage designed the Analytical Engine");
        lexicalIndex.put(ANALYTICAL_ENGINE, "Analytical Engine mechanical general purpose computer");
        lexicalIndex.put(NOTE_G, "Note G the first published algorithm");
        lexicalIndex.put(LONDON, "London capital city");
        lexicalIndex.put(TURIN, "Turin city in Piedmont");
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

    private GraphRagPipeline pipeline() {
        return pipeline(new ThresholdAbstentionPolicy(0.005, 0.001, 0.3, 5));
    }

    private GraphRagPipeline pipeline(AbstentionPolicy abstention) {
        EntityLinker linker = mention -> LABELS.entrySet().stream()
            .filter(entry -> entry.getValue().equalsIgnoreCase(mention)
                || entry.getValue().toLowerCase().contains(mention.toLowerCase()))
            .mapToLong(Map.Entry::getKey)
            .toArray();

        return new GraphRagPipeline(
            new QueryClassifier(),
            linker,
            edgeStore,
            new HybridRetriever(List.of(new LuceneRanker(lexicalIndex))),
            new TripleSubgraphSerializer(
                id -> LABELS.getOrDefault(id, "E" + id),
                relation -> RELATION_NAMES.getOrDefault(relation, "R" + relation)),
            abstention,
            new SerializationBudget(2000, BpeTokenCounter.defaultCounter()),
            10);
    }

    @Test
    @DisplayName("a question about a linked entity produces context the model can use")
    void endToEnd() {
        GraphRagResult result = pipeline().answer("Who did Ada Lovelace collaborate with?");

        assertThat(result.hasAnswer()).isTrue();
        assertThat(result.contextText())
            .contains("Ada Lovelace")
            .contains("Charles Babbage")
            .contains("collaboratedWith");
        assertThat(result.confidence()).isBetween(0.0, 1.0);
        assertThat(result.plan()).isPresent();
    }

    @Test
    @DisplayName("provenance reaches the prompt alongside the facts")
    void provenanceIsInTheContext() {
        GraphRagResult result = pipeline().answer("Who did Ada Lovelace collaborate with?");

        assertThat(result.contextText())
            .as("the model must be able to tell a curated claim from an extracted one")
            .contains("knowledge_base")
            .contains("conf=");
    }

    @Test
    @DisplayName("every stage of the request is timed")
    void perStageLatencyIsRecorded() {
        QueryTrace trace = new QueryTrace();
        pipeline().answer("Who did Ada Lovelace collaborate with?",
            new ConversationContext(), trace);

        assertThat(trace.byStage()).containsKeys(
            Stage.PLAN, Stage.LINK, Stage.TRAVERSE, Stage.RANK, Stage.SERIALIZE);
        assertThat(trace.elapsedNanos()).isPositive();
    }

    @Test
    @DisplayName("supersession means only the current home is reported")
    void supersessionAppliesEndToEnd() {
        // Both livedIn statements were written open-ended. Supersession closed the first, so a
        // present-day question must see one home, not two.
        GraphRagResult result = pipeline().answer("Where does Ada Lovelace live?");

        assertThat(result.hasAnswer()).isTrue();
        assertThat(result.contextText())
            .contains("Turin")
            .doesNotContain("London");
    }

    @Test
    @DisplayName("an as-of question sees what held then, not what holds now")
    void temporalQuestionSeesThePast() {
        GraphRagResult result = pipeline().answer("Where did Ada Lovelace live in 1820?");

        assertThat(result.plan()).isPresent();
        assertThat(result.hasAnswer()).isTrue();
        assertThat(result.contextText())
            .as("in 1820 the first home was still current")
            .contains("London")
            .doesNotContain("Turin");
    }

    @Test
    @DisplayName("an as-of given by the caller answers at that instant, whatever the question says")
    void anExplicitAsOfIsHonoured() {
        // Valid time was reachable only through date words in the question, so a client that
        // knows the instant it wants - a replay, an audit, a "what did the contract say on
        // this date" - had to phrase it and hope the classifier agreed. This is the instant
        // said outright.
        GraphRagResult result = pipeline().answer("Where does Ada Lovelace live?",
            new ConversationContext(),
            TemporalFilter.asOf(java.time.LocalDate.of(1820, 1, 1)),
            new QueryTrace());

        assertThat(result.hasAnswer()).isTrue();
        assertThat(result.contextText())
            .as("the question names no date; the caller does")
            .contains("London")
            .doesNotContain("Turin");
    }

    @Test
    @DisplayName("an as-of given by the caller overrides one read out of the question")
    void anExplicitAsOfBeatsTheInferredOne() {
        // The heuristic is a guess at what the asker meant; an instant in the request is not.
        GraphRagResult result = pipeline().answer("Where did Ada Lovelace live in 1820?",
            new ConversationContext(),
            TemporalFilter.asOf(java.time.LocalDate.of(2024, 1, 1)),
            new QueryTrace());

        assertThat(result.hasAnswer()).isTrue();
        assertThat(result.contextText()).contains("Turin").doesNotContain("London");
    }

    @Test
    @DisplayName("no as-of leaves the question's own dates in charge")
    void noExplicitAsOfKeepsTheInference() {
        GraphRagResult result = pipeline().answer("Where did Ada Lovelace live in 1820?",
            new ConversationContext(), null, new QueryTrace());

        assertThat(result.hasAnswer()).isTrue();
        assertThat(result.contextText()).contains("London").doesNotContain("Turin");
    }

    @Test
    @DisplayName("the whole history is reachable, not only a snapshot of it")
    void theUnboundedFilterSeesSupersededFacts() {
        GraphRagResult result = pipeline().answer("Where does Ada Lovelace live?",
            new ConversationContext(), TemporalFilter.ALL, new QueryTrace());

        assertThat(result.hasAnswer()).isTrue();
        assertThat(result.contextText())
            .as("a closed fact and the one that replaced it are both part of the history")
            .contains("London")
            .contains("Turin");
    }

    @Test
    @DisplayName("an unbound pronoun asks the user rather than guessing")
    void unboundPronounAsks() {
        GraphRagResult result = pipeline().answer(
            "Where did she live?", new ConversationContext(), new QueryTrace());

        assertThat(result.needsUserInput()).isTrue();
        assertThat(result.hasAnswer()).isFalse();
        assertThat(result.askUser()).isPresent();
        assertThat(result.askUser().get().question()).contains("she");
    }

    @Test
    @DisplayName("a bound pronoun carries through to a real answer")
    void boundPronounResolves() {
        ConversationContext context = new ConversationContext()
            .mention("Ada Lovelace", Grammar.SINGULAR_PERSON)
            .nextTurn();

        GraphRagResult result = pipeline().answer(
            "Who did she collaborate with?", context, new QueryTrace());

        assertThat(result.hasAnswer()).isTrue();
        assertThat(result.contextText()).contains("Charles Babbage");
    }

    @Test
    @DisplayName("a mention that links to nothing declines rather than answering emptily")
    void unlinkableMentionDeclines() {
        GraphRagResult result = pipeline().answer("Who did Grace Hopper collaborate with?");

        assertThat(result.hasAnswer()).isFalse();
        assertThat(result.declined()).isPresent();
    }

    @Test
    @DisplayName("a strict abstention policy declines instead of answering weakly")
    void strictPolicyDeclines() {
        // Same question, same evidence; only the threshold differs. The pipeline must respect it
        // rather than answering anyway.
        GraphRagResult result = pipeline(new ThresholdAbstentionPolicy(0.9, 0.001, 0.1, 5))
            .answer("Who did Ada Lovelace collaborate with?");

        assertThat(result.hasAnswer()).isFalse();
        assertThat(result.declined()).isPresent();
        assertThat(result.declined().get()).isInstanceOf(RetrievalOutcome.NoResults.class);
        assertThat(result.contextText())
            .as("nothing is serialised for an answer the system declined to give")
            .isEmpty();
    }

    @Test
    @DisplayName("the serialised context stays inside its token budget")
    void contextRespectsBudget() {
        GraphRagResult result = pipeline().answer("Who did Ada Lovelace collaborate with?");

        assertThat(result.context()).isPresent();
        assertThat(BpeTokenCounter.defaultCounter().count(result.contextText()))
            .isLessThanOrEqualTo(2000);
        assertThat(result.context().get().tokens())
            .isEqualTo(BpeTokenCounter.defaultCounter().count(result.contextText()));
    }

    @Test
    @DisplayName("a declined request is still timed")
    void declinedRequestsAreTimed() {
        // A refusal that took two seconds is as much a performance question as an answer that did.
        QueryTrace trace = new QueryTrace();
        pipeline().answer("Where did she live?", new ConversationContext(), trace);

        assertThat(trace.nanosIn(Stage.PLAN)).isPositive();
        assertThat(trace.elapsedNanos()).isPositive();
    }
}
