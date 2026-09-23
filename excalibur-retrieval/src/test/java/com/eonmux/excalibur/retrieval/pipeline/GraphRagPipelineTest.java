package com.eonmux.excalibur.retrieval.pipeline;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.eonmux.excalibur.core.trace.QueryTrace;
import com.eonmux.excalibur.core.trace.Stage;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.store.EdgeStoreWriter;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.temporal.SupersessionPolicy;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.planner.ConversationContext;
import com.eonmux.excalibur.planner.Grammar;
import com.eonmux.excalibur.planner.QueryClassifier;
import com.eonmux.excalibur.retrieval.HybridRetriever;
import com.eonmux.excalibur.retrieval.fusion.ScorePassthrough;
import com.eonmux.excalibur.retrieval.abstain.AbstentionPolicy;
import com.eonmux.excalibur.retrieval.abstain.RetrievalOutcome;
import com.eonmux.excalibur.retrieval.abstain.ThresholdAbstentionPolicy;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;
import com.eonmux.excalibur.retrieval.lexical.LuceneRanker;
import com.eonmux.excalibur.retrieval.serialize.BpeTokenCounter;
import com.eonmux.excalibur.retrieval.serialize.SerializationBudget;
import com.eonmux.excalibur.retrieval.serialize.TripleSubgraphSerializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
    /** Shares a first name with {@link #ADA} and nothing else, for questions that name only it. */
    private static final int ADA_KING = 7;
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
        (long) TURIN, "Turin",
        (long) ADA_KING, "Ada King");

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
        // As many words as Ada Lovelace's, so a question naming only "Ada" scores them alike.
        lexicalIndex.put(ADA_KING, "Ada King countess who wrote long letters");
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

    /** The same pipeline, able to resolve the relation words a question uses. */
    private GraphRagPipeline pipelineWithRelations() {
        Map<Integer, String> names = new java.util.LinkedHashMap<>();
        RELATION_NAMES.forEach((id, name) -> names.put(Math.toIntExact(id), name));
        return pipeline(new ThresholdAbstentionPolicy(0.005, 0.001, 0.3, 5),
            RelationResolver.over(names));
    }

    private GraphRagPipeline pipeline(AbstentionPolicy abstention) {
        return pipeline(abstention, null);
    }

    private GraphRagPipeline pipeline(AbstentionPolicy abstention, RelationResolver relations) {
        EntityLinker linker = mention -> LABELS.entrySet().stream()
            .filter(entry -> entry.getValue().equalsIgnoreCase(mention)
                || entry.getValue().toLowerCase().contains(mention.toLowerCase()))
            .mapToLong(Map.Entry::getKey)
            .sorted()
            .toArray();
        return pipeline(abstention, relations, linker);
    }

    private GraphRagPipeline pipeline(AbstentionPolicy abstention, RelationResolver relations,
                                      EntityLinker linker) {
        return pipeline(abstention, relations, linker, id -> LABELS.getOrDefault(id, "E" + id));
    }

    private GraphRagPipeline pipeline(AbstentionPolicy abstention, RelationResolver relations,
                                      EntityLinker linker, java.util.function.LongFunction<String> names) {
        return new GraphRagPipeline(
            new QueryClassifier(),
            linker,
            edgeStore,
            new HybridRetriever(List.of(
                new LuceneRanker(lexicalIndex, "bm25", LuceneRanker.Scale.ATTAINABLE_SHARE)),
                new ScorePassthrough(), HybridRetriever.DEFAULT_PER_SIGNAL_OVER_FETCH),
            new TripleSubgraphSerializer(
                names,
                relation -> RELATION_NAMES.getOrDefault(relation, "R" + relation)),
            abstention,
            new SerializationBudget(2000, BpeTokenCounter.defaultCounter()),
            10,
            null,
            relations,
            names);
    }

    /** A conversation that has mentioned {@code names}, the way the endpoint builds one. */
    private static ConversationContext mentioning(String... names) {
        ConversationContext context = new ConversationContext();
        for (String name : names) {
            context.mention(name, Grammar.UNKNOWN);
        }
        return context.nextTurn();
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
        assertThat(result.inputRequest()).hasValueSatisfying(request -> {
            assertThat(request.reason()).isEqualTo(InputRequest.Reason.UNBOUND_PRONOUN);
            assertThat(request.unresolved()).contains("she");
        });
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

    @Test
    @DisplayName("answering judges exactly the evidence gathering returns")
    void answerJudgesWhatGatherReturns() {
        // The whole claim of the gather seam is that a harness fitting thresholds against it is
        // fitting the retrieval a served query gets. That holds only while answer() is written in
        // terms of gather() rather than beside it, so it is asserted rather than assumed.
        ThresholdAbstentionPolicy policy = new ThresholdAbstentionPolicy(0.005, 0.001, 0.3, 5);
        GraphRagPipeline pipeline = pipeline(policy);
        String question = "Who did Ada Lovelace collaborate with?";

        Evidence evidence = pipeline.gather(
            question, new ConversationContext(), null, new QueryTrace());

        assertThat(evidence).isInstanceOf(Evidence.Gathered.class);
        Evidence.Gathered gathered = (Evidence.Gathered) evidence;

        RetrievalOutcome directly = policy.decide(gathered.fused(), gathered.subgraph());
        GraphRagResult served = pipeline.answer(question);

        assertThat(served.hasAnswer())
            .isEqualTo(directly instanceof RetrievalOutcome.Results);
        assertThat(served.confidence())
            .isEqualTo(((RetrievalOutcome.Results) directly).confidence());
    }

    @Test
    @DisplayName("gathering reports a question that never reached retrieval, rather than refusing")
    void gatherDistinguishesUnresolvedFromRefused() {
        // A refusal is abstention's; an unresolvable mention is not, and no threshold changes it.
        // A fit that could not tell them apart would gate against a gap in the graph.
        Evidence evidence = pipeline().gather(
            "Who did Zxqv Nonexistent collaborate with?", new ConversationContext(), null,
            new QueryTrace());

        assertThat(evidence).isInstanceOf(Evidence.Unresolved.class);
        assertThat(pipeline().answer("Who did Zxqv Nonexistent collaborate with?").declined())
            .as("serving still turns it into a refusal; only the seam keeps them apart")
            .isPresent();
    }

    @Test
    @DisplayName("a question asked from the other end of an edge is answered too")
    void reverseDirectionQuestionIsAnswered() {
        // The store holds Ada --collaboratedWith--> Babbage. Asked from Babbage's side it is the
        // same fact, and a traversal that followed only the direction it was asserted in would
        // refuse this while holding the answer.
        GraphRagResult forward = pipeline().answer("Who did Ada Lovelace collaborate with?");
        GraphRagResult reverse = pipeline().answer("Who did Charles Babbage collaborate with?");

        assertThat(forward.hasAnswer()).isTrue();
        assertThat(reverse.hasAnswer())
            .as("the fact is in the store; which end it was written from is not the asker's problem")
            .isTrue();
        assertThat(reverse.contextText()).contains("Ada Lovelace");
    }

    @Nested
    @DisplayName("relation hints")
    class RelationHints {

        @Test
        @DisplayName("a hint the graph recognises narrows what the answer is built from")
        void recognisedHintNarrowsTheTraversal() {
            // Ada has four edges: collaboratedWith, authored and two livedIn. The question names
            // only one of those relations, so only it should reach the model.
            GraphRagResult result =
                pipelineWithRelations().answer("Who collaborated with Ada Lovelace?");

            assertThat(result.hasAnswer()).isTrue();
            assertThat(result.contextText())
                .contains("collaboratedWith")
                .contains("Charles Babbage");
            assertThat(result.contextText())
                .as("a traversal narrowed to one relation must not describe the others")
                .doesNotContain("authored")
                .doesNotContain("livedIn");
        }

        @Test
        @DisplayName("the same question without a resolver still sees every relation")
        void withoutAResolverNothingIsNarrowed() {
            GraphRagResult result = pipeline().answer("Who collaborated with Ada Lovelace?");

            assertThat(result.hasAnswer()).isTrue();
            assertThat(result.contextText()).contains("authored");
        }

        @Test
        @DisplayName("a hint that resolves to the wrong relation costs nothing")
        void wrongHintFallsBackRatherThanRefusing() {
            // Note G is authored, never collaborated on. The hint resolves - to a relation this
            // entity has no edge on - so a hard whitelist would answer "nothing found" while
            // holding the fact.
            GraphRagResult result =
                pipelineWithRelations().answer("Who collaborated with Note G?");

            assertThat(result.hasAnswer())
                .as("a hint may sharpen an answer; it may not remove one")
                .isTrue();
            assertThat(result.contextText())
                .contains("authored")
                .contains("Ada Lovelace");
        }

        @Test
        @DisplayName("a hint matching no relation leaves the traversal unfiltered")
        void unresolvableHintIsIgnored() {
            // "who did" is a phrasing cue, not a relation. It must not become a whitelist of
            // nothing, which asks the store for nothing and answers every such question with
            // silence.
            GraphRagResult result =
                pipelineWithRelations().answer("Who did Ada Lovelace collaborate with?");

            assertThat(result.hasAnswer()).isTrue();
            assertThat(result.contextText()).contains("Charles Babbage");
        }

        @Test
        @DisplayName("a narrowed traversal still follows the relation both ways")
        void narrowingKeepsBothDirections() {
            // Asked from Babbage's end. The edge is Ada -> Babbage, and narrowing to the named
            // relation must not reintroduce, per relation, the asymmetry the store no longer has.
            GraphRagResult result =
                pipelineWithRelations().answer("Who collaborated with Charles Babbage?");

            assertThat(result.hasAnswer()).isTrue();
            assertThat(result.contextText()).contains("Ada Lovelace");
        }
    }

    @Nested
    @DisplayName("ambiguity goes back to the user")
    class Ambiguity {

        private static final String QUESTION = "What did Ada write?";

        @Test
        @DisplayName("a name two entities share is handed back with both as candidates, not declined")
        void sharedNameNeedsInput() {
            GraphRagResult result = pipeline().answer(QUESTION);

            assertThat(result.hasAnswer()).isFalse();
            assertThat(result.declined())
                .as("the evidence was there; which entity was meant is the only thing missing")
                .isEmpty();
            assertThat(result.needsUserInput()).isTrue();
            assertThat(result.ambiguity()).isPresent();
            assertThat(result.inputRequest()).hasValueSatisfying(request -> {
                assertThat(request.reason()).isEqualTo(InputRequest.Reason.AMBIGUOUS_ENTITY);
                assertThat(request.candidates()).containsExactlyInAnyOrder("Ada Lovelace", "Ada King");
                assertThat(request.unresolved()).containsExactly("Ada");
                assertThat(request.question()).contains("\"Ada\"").contains("Ada Lovelace")
                    .contains("Ada King");
            });
        }

        @Test
        @DisplayName("sending a candidate back as a conversation entity answers the question")
        void candidateSentBackResolves() {
            GraphRagResult asked = pipeline().answer(QUESTION);
            String chosen = asked.inputRequest().orElseThrow().candidates().stream()
                .filter("Ada Lovelace"::equals).findFirst().orElseThrow();

            GraphRagResult answered = pipeline().answer(QUESTION, mentioning(chosen),
                new QueryTrace());

            assertThat(answered.hasAnswer()).isTrue();
            assertThat(answered.contextText()).contains("Note G").contains("Ada Lovelace");
        }

        @Test
        @DisplayName("entities sharing a label are offered apart, and the one sent back answers")
        void sharedLabelsAreOfferedApart() {
            // Both are called "Ada". Offering the label once would send back a word that names
            // both, and the same question would come back however often it was answered.
            java.util.function.LongFunction<String> names = id -> id == ADA || id == ADA_KING
                ? "Ada"
                : LABELS.getOrDefault(id, "E" + id);
            EntityLinker linker = mention -> mention.equalsIgnoreCase("Ada")
                ? new long[] { ADA, ADA_KING } : new long[0];
            GraphRagPipeline pipeline = pipeline(
                new ThresholdAbstentionPolicy(0.005, 0.001, 0.3, 5), null, linker, names);

            List<String> candidates = pipeline.answer(QUESTION).inputRequest().orElseThrow()
                .candidates();
            assertThat(candidates).containsExactlyInAnyOrder("Ada #" + ADA, "Ada #" + ADA_KING);

            GraphRagResult answered = pipeline.answer(QUESTION, mentioning("Ada #" + ADA),
                new QueryTrace());

            assertThat(answered.needsUserInput()).isFalse();
            assertThat(answered.contextText()).contains("Note G");
        }

        @Test
        @DisplayName("choosing the other candidate answers about that one instead")
        void theOtherCandidateIsHonoured() {
            // Ada King holds no facts; the answer is about her - an empty subgraph - and never
            // borrows Ada Lovelace's.
            GraphRagResult answered = pipeline().answer(QUESTION, mentioning("Ada King"),
                new QueryTrace());

            assertThat(answered.needsUserInput()).isFalse();
            assertThat(answered.contextText()).doesNotContain("Note G");
        }

        @Test
        @DisplayName("a tie with an entity the question never named is not an ambiguity")
        void tieWithAnUnnamedEntityAnswers() {
            // The linker is sure "Ada" is Ada Lovelace; the ranking scores Ada King exactly
            // alike. The question named one entity and is in no doubt which: entities extracted
            // from one snippet are described by it and tie routinely, and asking "which did you
            // mean" between the named one and one never mentioned would be a question with no
            // right answer.
            EntityLinker precise = mention -> mention.equalsIgnoreCase("Ada")
                ? new long[] { ADA } : new long[0];
            GraphRagPipeline pipeline = pipeline(
                new ThresholdAbstentionPolicy(0.005, 0.001, 0.3, 5), null, precise);

            GraphRagResult result = pipeline.answer(QUESTION);

            assertThat(result.needsUserInput()).isFalse();
            assertThat(result.hasAnswer()).isTrue();
            assertThat(result.contextText()).contains("Note G");
        }

        @Test
        @DisplayName("abstention judges the readings of the name, not the whole ranking")
        void gatheredReadingsAreTheNamedEntities() {
            Evidence.Gathered ambiguous = (Evidence.Gathered) pipeline().gather(
                QUESTION, new ConversationContext(), null, new QueryTrace());
            Evidence.Gathered named = (Evidence.Gathered) pipeline().gather(
                "Who did Ada Lovelace collaborate with?", new ConversationContext(), null,
                new QueryTrace());

            assertThat(ambiguous.fused()).extracting(
                    com.eonmux.excalibur.retrieval.fusion.FusedDocument::documentId)
                .containsExactlyInAnyOrder((long) ADA, (long) ADA_KING);
            assertThat(named.fused()).extracting(
                    com.eonmux.excalibur.retrieval.fusion.FusedDocument::documentId)
                .as("one name, one entity: only the score gate has anything to read")
                .containsExactly((long) ADA);
            assertThat(named.ranked().size()).isGreaterThan(named.fused().size());
        }

        @Test
        @DisplayName("a conversation naming neither candidate leaves the question with the user")
        void unrelatedConversationSettlesNothing() {
            GraphRagResult result = pipeline().answer(QUESTION, mentioning("Charles Babbage"),
                new QueryTrace());

            assertThat(result.inputRequest()).hasValueSatisfying(request ->
                assertThat(request.reason()).isEqualTo(InputRequest.Reason.AMBIGUOUS_ENTITY));
        }
    }

    @Nested
    @DisplayName("aggregates")
    class Aggregates {

        @Test
        @DisplayName("a count is the distinct entities related by the facts valid now")
        void countsDistinctRelatedEntities() {
            // Four facts, one of them superseded: the count reads the three that hold today.
            GraphRagResult result =
                pipeline().answer("How many things is Ada Lovelace related to?");

            assertThat(result.hasAnswer()).isTrue();
            assertThat(result.aggregation()).hasValueSatisfying(aggregate -> {
                assertThat(aggregate.function()).isEqualTo(
                    com.eonmux.excalibur.planner.QueryPlan.AggregateFunction.COUNT);
                assertThat(aggregate.count()).isEqualTo(3);
                assertThat(aggregate.factsConsidered()).isEqualTo(3);
                assertThat(aggregate.entities())
                    .containsExactly("Charles Babbage", "Note G", "Turin");
                assertThat(aggregate.validFrom()).isEmpty();
            });
        }

        @Test
        @DisplayName("the aggregate leads the context, inside the budget")
        void aggregateLeadsTheContext() {
            GraphRagResult result =
                pipeline().answer("How many things is Ada Lovelace related to?");

            assertThat(result.contextText()).startsWith("# aggregate\ncount: 3 distinct entities "
                + "related to Ada Lovelace by 3 facts\n");
            assertThat(result.context().orElseThrow().tokens())
                .isEqualTo(BpeTokenCounter.defaultCounter().count(result.contextText()))
                .isLessThanOrEqualTo(2000);
        }

        @Test
        @DisplayName("a count at a past instant counts what held then")
        void countHonoursAsOf() {
            GraphRagResult result = pipeline().answer(
                "How many things is Ada Lovelace related to?", new ConversationContext(),
                TemporalFilter.asOf(LocalDate.of(1820, 1, 1)), new QueryTrace());

            assertThat(result.aggregation()).hasValueSatisfying(aggregate -> {
                assertThat(aggregate.count()).isEqualTo(2);
                assertThat(aggregate.entities()).containsExactly("Charles Babbage", "London");
            });
        }

        @Test
        @DisplayName("earliest is the dated fact that began first, even one that has since ended")
        void earliestReadsHistory() {
            // London was superseded in 1841, yet it is her first home. The collaboration has no
            // recorded start and is not the earliest of anything.
            GraphRagResult result = pipeline().answer("Where did Ada Lovelace first live?");

            assertThat(result.hasAnswer()).isTrue();
            assertThat(result.aggregation()).hasValueSatisfying(aggregate -> {
                assertThat(aggregate.function()).isEqualTo(
                    com.eonmux.excalibur.planner.QueryPlan.AggregateFunction.EARLIEST);
                assertThat(aggregate.validFrom()).hasValue(day(1815, 1, 1));
                assertThat(aggregate.entities()).containsExactly("London");
                assertThat(aggregate.count()).isEqualTo(1);
            });
            assertThat(result.contextText())
                .startsWith("# aggregate\nearliest about Ada Lovelace: began 1815-01-01, with "
                    + "London (1 fact)\n")
                .as("the evidence is the extreme fact, not the whole history")
                .doesNotContain("Turin")
                .doesNotContain("Charles Babbage");
        }

        @Test
        @DisplayName("latest is the fact that began last")
        void latestNow() {
            GraphRagResult result = pipeline().answer("What did Ada Lovelace do last?");

            assertThat(result.aggregation()).hasValueSatisfying(aggregate -> {
                assertThat(aggregate.function()).isEqualTo(
                    com.eonmux.excalibur.planner.QueryPlan.AggregateFunction.LATEST);
                assertThat(aggregate.validFrom()).hasValue(day(1843, 1, 1));
                assertThat(aggregate.entities()).containsExactly("Note G");
            });
        }

        @Test
        @DisplayName("latest at a past instant ignores what had not begun by then")
        void latestHonoursAsOf() {
            GraphRagResult result = pipeline().answer("What did Ada Lovelace do last?",
                new ConversationContext(), TemporalFilter.asOf(LocalDate.of(1830, 1, 1)),
                new QueryTrace());

            assertThat(result.aggregation()).hasValueSatisfying(aggregate -> {
                assertThat(aggregate.validFrom()).hasValue(day(1815, 1, 1));
                assertThat(aggregate.entities()).containsExactly("London");
            });
            assertThat(result.contextText()).doesNotContain("Note G").doesNotContain("Turin");
        }

        @Test
        @DisplayName("an extremum over facts none of which is dated says so, and keeps the facts")
        void extremumWithNothingDated() {
            // Babbage's one dated fact began in 1837. In 1830 the only fact about him is the
            // collaboration, which has no recorded start: nothing is first, and the model still
            // gets the fact.
            GraphRagResult result = pipeline().answer("What did Charles Babbage do first?",
                new ConversationContext(), TemporalFilter.asOf(LocalDate.of(1830, 1, 1)),
                new QueryTrace());

            assertThat(result.hasAnswer()).isTrue();
            assertThat(result.aggregation()).hasValueSatisfying(aggregate -> {
                assertThat(aggregate.count()).isZero();
                assertThat(aggregate.factsConsidered()).isEqualTo(1);
                assertThat(aggregate.validFrom()).isEmpty();
                assertThat(aggregate.entities()).isEmpty();
            });
            assertThat(result.contextText())
                .startsWith("# aggregate\nearliest about Charles Babbage: no fact with a "
                    + "recorded start\n")
                .contains("collaboratedWith")
                .doesNotContain("designed");
        }

        @Test
        @DisplayName("a question that is not an aggregate carries none")
        void nonAggregateCarriesNone() {
            GraphRagResult result = pipeline().answer("Who did Ada Lovelace collaborate with?");

            assertThat(result.aggregation()).isEmpty();
            assertThat(result.contextText()).doesNotContain("# aggregate");
        }
    }

    @Nested
    @DisplayName("traversal direction")
    class Direction {

        private static final String QUESTION = "Who did Charles Babbage collaborate with?";

        private GraphRagResult ask(GraphRagPipeline pipeline, TraversalDirection direction) {
            return pipeline.answer(QUESTION, new ConversationContext(), null, direction,
                new QueryTrace());
        }

        @Test
        @DisplayName("forward keeps only the facts the named entity asserts")
        void forwardFollowsSubjectToObject() {
            GraphRagResult result = ask(pipelineWithRelations(), TraversalDirection.FORWARD);

            assertThat(result.hasAnswer()).isTrue();
            assertThat(result.contextText())
                .contains("designed").contains("Analytical Engine")
                .as("Ada -> Babbage is asserted about him, not by him")
                .doesNotContain("collaboratedWith");
        }

        @Test
        @DisplayName("reverse keeps only the facts asserted about the named entity")
        void reverseFollowsObjectToSubject() {
            GraphRagResult result = ask(pipelineWithRelations(), TraversalDirection.REVERSE);

            assertThat(result.hasAnswer()).isTrue();
            assertThat(result.contextText())
                .contains("collaboratedWith").contains("Ada Lovelace")
                .doesNotContain("designed");
        }

        @Test
        @DisplayName("both, the default, keeps each")
        void bothKeepsEach() {
            GraphRagResult result = ask(pipelineWithRelations(), TraversalDirection.BOTH);

            assertThat(result.contextText()).contains("collaboratedWith").contains("designed");
        }

        @Test
        @DisplayName("a relation hint narrows a one-way walk and still falls back when it finds nothing")
        void hintNarrowsOneWayWalk() {
            // "collaborated" names the relation; forward from Ada it is there, forward from
            // Babbage it is not, and the fallback walks every relation forward instead.
            GraphRagPipeline pipeline = pipelineWithRelations();
            GraphRagResult fromAda = pipeline.answer("Who collaborated with Ada Lovelace?",
                new ConversationContext(), null, TraversalDirection.FORWARD, new QueryTrace());
            GraphRagResult fromBabbage = pipeline.answer("Who collaborated with Charles Babbage?",
                new ConversationContext(), null, TraversalDirection.FORWARD, new QueryTrace());

            assertThat(fromAda.contextText()).contains("collaboratedWith")
                .doesNotContain("authored");
            assertThat(fromBabbage.contextText()).contains("designed")
                .doesNotContain("collaboratedWith");
        }

        @Test
        @DisplayName("a one-way walk without a relation vocabulary is refused, not silently widened")
        void oneWayNeedsTheVocabulary() {
            GraphRagPipeline pipeline = pipeline();

            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> ask(pipeline, TraversalDirection.FORWARD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forward");
            assertThat(ask(pipeline, TraversalDirection.BOTH).hasAnswer()).isTrue();
        }

        @Test
        @DisplayName("a caller can ask which walks the pipeline takes before asking it for one")
        void supportsSaysWhatAnswerWillHonour() {
            // What a server reads to refuse the request as the caller's to change, rather than
            // letting the refusal arrive as the exception above and be reported as its own fault.
            GraphRagPipeline without = pipeline();
            GraphRagPipeline with = pipelineWithRelations();

            assertThat(without.supports(TraversalDirection.BOTH)).isTrue();
            assertThat(without.supports(TraversalDirection.FORWARD)).isFalse();
            assertThat(without.supports(TraversalDirection.REVERSE)).isFalse();
            for (TraversalDirection direction : TraversalDirection.values()) {
                assertThat(with.supports(direction)).as("%s", direction).isTrue();
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> with.supports(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
