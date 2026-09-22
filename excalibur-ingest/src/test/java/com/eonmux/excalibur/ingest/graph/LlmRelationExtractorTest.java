package com.eonmux.excalibur.ingest.graph;

import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.client.LLMHttpClient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading facts out of what a chat model says.
 *
 * <p>A model asked for JSON returns prose, an extra field, an empty subject, or a relation phrased
 * as a sentence often enough that the extractor's job is as much refusing as reading. A graph is
 * only worth traversing if an edge means what it says, so a malformed triple is dropped and a reply
 * nothing at all can be read from is an error rather than a document that asserted nothing.</p>
 */
@DisplayName("LLM relation extraction")
class LlmRelationExtractorTest {

    /** A client whose chat call returns a canned reply, or throws. */
    private static final class StubClient extends LLMHttpClient {

        private final AtomicReference<String> reply = new AtomicReference<>();
        private final AtomicReference<LLMException> failure = new AtomicReference<>();

        StubClient() {
            super(LLMClientConfig.builder()
                .baseUrl("http://localhost:9")
                .apiKey("test")
                .chatModel("gpt-4o-mini")
                .embeddingModel("text-embedding-3-small")
                .embeddingDimension(8)
                .timeout(Duration.ofSeconds(1))
                .build());
        }

        StubClient replying(String value) {
            reply.set(value);
            return this;
        }

        StubClient failingWith(LLMException value) {
            failure.set(value);
            return this;
        }

        @Override
        public String completeWithCircuitBreaker(String instruction, String input,
                                                 String correlationId) {
            LLMException thrown = failure.get();
            if (thrown != null) {
                throw thrown;
            }
            return reply.get();
        }
    }

    private static List<ExtractedTriple> extract(String reply) {
        return new LlmRelationExtractor(new StubClient().replying(reply), 7)
            .extract("docs:ada", "Ada Lovelace worked with Charles Babbage.");
    }

    @Nested
    @DisplayName("reading a reply")
    class ReadingAReply {

        @Test
        @DisplayName("a well-formed reply becomes facts")
        void aWellFormedReplyBecomesFacts() {
            List<ExtractedTriple> triples = extract(
                "[{\"subject\":\"Ada Lovelace\",\"relation\":\"collaborated_with\","
                    + "\"object\":\"Charles Babbage\",\"confidence\":0.9}]");

            assertThat(triples).hasSize(1);
            ExtractedTriple triple = triples.get(0);
            assertThat(triple.subject()).isEqualTo("Ada Lovelace");
            assertThat(triple.relation()).isEqualTo("collaborated_with");
            assertThat(triple.object()).isEqualTo("Charles Babbage");
            assertThat(triple.provenance().sourceType()).isEqualTo(SourceType.DOCUMENT);
            assertThat(triple.provenance().confidence()).isEqualTo(90);
            assertThat(triple.provenance().extractorVersion()).isEqualTo(7);
        }

        @Test
        @DisplayName("field order does not matter")
        void fieldOrderDoesNotMatter() {
            List<ExtractedTriple> triples = extract(
                "[{\"object\":\"Babbage\",\"confidence\":0.5,\"relation\":\"knows\","
                    + "\"subject\":\"Ada\"}]");

            assertThat(triples).hasSize(1);
            assertThat(triples.get(0).subject()).isEqualTo("Ada");
            assertThat(triples.get(0).object()).isEqualTo("Babbage");
        }

        @Test
        @DisplayName("extra fields are tolerated")
        void extraFieldsAreTolerated() {
            List<ExtractedTriple> triples = extract(
                "[{\"subject\":\"Ada\",\"relation\":\"knows\",\"object\":\"Babbage\","
                    + "\"reason\":\"stated in the second paragraph\",\"confidence\":0.7}]");

            assertThat(triples).hasSize(1);
        }

        @Test
        @DisplayName("prose around the array is ignored")
        void proseAroundTheArrayIsIgnored() {
            List<ExtractedTriple> triples = extract(
                "Here are the facts:\n"
                    + "[{\"subject\":\"Ada\",\"relation\":\"knows\",\"object\":\"Babbage\"}]\n"
                    + "Let me know if you need more.");

            assertThat(triples).hasSize(1);
        }

        @Test
        @DisplayName("an empty array means the text asserts nothing")
        void anEmptyArrayMeansNoFacts() {
            assertThat(extract("[]")).isEmpty();
        }

        @Test
        @DisplayName("a missing confidence gets the default rather than dropping the fact")
        void aMissingConfidenceGetsTheDefault() {
            List<ExtractedTriple> triples = extract(
                "[{\"subject\":\"Ada\",\"relation\":\"knows\",\"object\":\"Babbage\"}]");

            assertThat(triples).hasSize(1);
            assertThat(triples.get(0).provenance().confidenceFraction())
                .isEqualTo(LlmRelationExtractor.DEFAULT_CONFIDENCE);
        }

        @Test
        @DisplayName("an unreadable confidence keeps the fact")
        void anUnreadableConfidenceKeepsTheFact() {
            List<ExtractedTriple> triples = extract(
                "[{\"subject\":\"Ada\",\"relation\":\"knows\",\"object\":\"Babbage\","
                    + "\"confidence\":\"very high\"}]");

            assertThat(triples).hasSize(1);
        }

        @Test
        @DisplayName("a confidence outside the range is clamped")
        void anOutOfRangeConfidenceIsClamped() {
            List<ExtractedTriple> triples = extract(
                "[{\"subject\":\"Ada\",\"relation\":\"knows\",\"object\":\"Babbage\","
                    + "\"confidence\":4.2}]");

            assertThat(triples.get(0).provenance().confidence()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("refusing a reply")
    class RefusingAReply {

        @Test
        @DisplayName("a triple missing a field is dropped, not stored")
        void anIncompleteTripleIsDropped() {
            List<ExtractedTriple> triples = extract(
                "[{\"subject\":\"Ada\",\"relation\":\"knows\"},"
                    + "{\"subject\":\"Ada\",\"relation\":\"knows\",\"object\":\"Babbage\"}]");

            assertThat(triples).hasSize(1);
            assertThat(triples.get(0).object()).isEqualTo("Babbage");
        }

        @Test
        @DisplayName("a self-loop is dropped")
        void aSelfLoopIsDropped() {
            assertThat(extract(
                "[{\"subject\":\"Ada\",\"relation\":\"same_as\",\"object\":\"ada\"}]"))
                .isEmpty();
        }

        @Test
        @DisplayName("a repeated triple is stored once")
        void aRepeatedTripleIsStoredOnce() {
            List<ExtractedTriple> triples = extract(
                "[{\"subject\":\"Ada\",\"relation\":\"knows\",\"object\":\"Babbage\"},"
                    + "{\"subject\":\"Ada\",\"relation\":\"knows\",\"object\":\"Babbage\"}]");

            assertThat(triples).hasSize(1);
        }

        @Test
        @DisplayName("prose instead of an array is an extraction failure")
        void proseInsteadOfAnArrayFails() {
            assertThatThrownBy(() -> extract("I could not find any relationships in that text."))
                .isInstanceOf(RelationExtractor.ExtractionException.class);
        }

        @Test
        @DisplayName("an empty reply is an extraction failure")
        void anEmptyReplyFails() {
            assertThatThrownBy(() -> extract("  "))
                .isInstanceOf(RelationExtractor.ExtractionException.class);
        }

        @Test
        @DisplayName("a model that refuses the call is an extraction failure")
        void aModelFailureSurfaces() {
            LlmRelationExtractor extractor = new LlmRelationExtractor(
                new StubClient().failingWith(
                    new LLMException("circuit open", LLMException.ErrorType.CIRCUIT_OPEN)), 1);

            assertThatThrownBy(() -> extractor.extract("docs:ada", "Ada and Babbage."))
                .isInstanceOf(RelationExtractor.ExtractionException.class);
        }

        @Test
        @DisplayName("blank text asserts nothing without asking the model")
        void blankTextIsNotSentToTheModel() {
            LlmRelationExtractor extractor = new LlmRelationExtractor(
                new StubClient().failingWith(
                    new LLMException("should not be called", LLMException.ErrorType.NETWORK)), 1);

            assertThat(extractor.extract("docs:ada", "   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("normalising")
    class Normalising {

        @Test
        @DisplayName("a relation is lower-cased and its spaces closed up")
        void relationsAreNormalised() {
            List<ExtractedTriple> triples = extract(
                "[{\"subject\":\"Ada\",\"relation\":\"Worked With\",\"object\":\"Babbage\"}]");

            // A graph in which one relationship appears under three spellings cannot be traversed
            // by relation at all.
            assertThat(triples.get(0).relation()).isEqualTo("worked_with");
        }

        @Test
        @DisplayName("an entity label keeps its case and loses its padding")
        void entityLabelsAreTrimmed() {
            List<ExtractedTriple> triples = extract(
                "[{\"subject\":\"  Ada Lovelace \",\"relation\":\"knows\","
                    + "\"object\":\"Charles Babbage\"}]");

            assertThat(triples.get(0).subject()).isEqualTo("Ada Lovelace");
        }

        @Test
        @DisplayName("an absurdly long label is dropped")
        void anAbsurdlyLongLabelIsDropped() {
            String long_ = "x".repeat(LlmRelationExtractor.MAX_LABEL_CHARS + 1);

            assertThat(extract(
                "[{\"subject\":\"" + long_ + "\",\"relation\":\"knows\",\"object\":\"Babbage\"}]"))
                .isEmpty();
        }

        @Test
        @DisplayName("escaped characters in a label are decoded")
        void escapesAreDecoded() {
            List<ExtractedTriple> triples = extract(
                "[{\"subject\":\"Ada \\\"the Countess\\\"\",\"relation\":\"knows\","
                    + "\"object\":\"Babbage\"}]");

            assertThat(triples.get(0).subject()).isEqualTo("Ada \"the Countess\"");
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("an extractor version that will not fit the column is refused")
        void anOversizedVersionIsRefused() {
            assertThatThrownBy(() ->
                new LlmRelationExtractor(new StubClient(), Integer.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("non-positive bounds are refused")
        void nonPositiveBoundsAreRefused() {
            assertThatThrownBy(() -> new LlmRelationExtractor(new StubClient(), 1, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LlmRelationExtractor(new StubClient(), 1, 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the extractor that finds nothing asks no model")
        void theNoneExtractorFindsNothing() {
            assertThat(RelationExtractor.none().extract("docs:ada", "anything")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the sink")
    class Sink {

        @Test
        @DisplayName("facts and the text that produced them are both staged")
        void factsAndTextAreStaged(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                StagingGraphIngestSink sink = new StagingGraphIngestSink(staging,
                    (documentKey, text) -> List.of(ExtractedTriple.alwaysValid(
                        "Ada", "knows", "Babbage",
                        com.eonmux.excalibur.graph.provenance.Provenance.UNKNOWN)));

                sink.onDocumentPublished("docs:ada", "Ada knows Babbage.");

                assertThat(sink.documentsSeen()).isEqualTo(1);
                assertThat(sink.factsStaged()).isEqualTo(1);
                assertThat(staging.stagedCount()).isEqualTo(1);

                // Both endpoints get the document's text, which is what BM25 ranks them by.
                assertThat(staging.forEachEntityText((id, snippet) -> { })).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("a document with no text is ignored")
        void aDocumentWithNoTextIsIgnored(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                StagingGraphIngestSink sink =
                    new StagingGraphIngestSink(staging, RelationExtractor.none());

                sink.onDocumentPublished("docs:ada", "   ");
                sink.onDocumentPublished("docs:ada", null);
                sink.onDocumentPublished(null, "text");

                assertThat(sink.documentsSeen()).isZero();
            }
        }

        @Test
        @DisplayName("facts can be staged directly, without an extractor")
        void factsCanBeStagedDirectly(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                StagingGraphIngestSink sink =
                    new StagingGraphIngestSink(staging, RelationExtractor.none());

                int staged = sink.stage(List.of(
                    ExtractedTriple.alwaysValid("Ada", "knows", "Babbage",
                        com.eonmux.excalibur.graph.provenance.Provenance.UNKNOWN)));

                assertThat(staged).isEqualTo(1);
                assertThat(sink.staging()).isSameAs(staging);
                assertThat(sink.stage(List.of())).isZero();
                assertThat(sink.stage(null)).isZero();
            }
        }

        @Test
        @DisplayName("a sink with nothing to do reports nothing")
        void theNoneSinkDoesNothing() {
            GraphIngestSink.none().onDocumentPublished("docs:ada", "text");
        }
    }
}
