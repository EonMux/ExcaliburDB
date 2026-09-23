package com.eonmux.excalibur.ingest.graph;

import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.staging.StagedErasure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * What the sink counts when staging a fact does not work.
 *
 * <p>The class's own contract is that failures are counted rather than only logged, "because the
 * interesting failure is the steady one". A staging failure was the one that only got logged, so
 * a store refusing every append left a graph that quietly stopped growing with nothing to read
 * it off.</p>
 */
@DisplayName("StagingGraphIngestSink")
class StagingGraphIngestSinkTest {

    private static final ExtractedTriple FACT =
        ExtractedTriple.alwaysValid("kafka", "runs-on", "kubernetes", Provenance.UNKNOWN);

    @Test
    @DisplayName("counts a fact it could not stage")
    void countsAFactItCouldNotStage() throws IOException {
        GraphStagingStore store = mock(GraphStagingStore.class);
        doThrow(new IOException("disk full")).when(store).append(any());
        StagingGraphIngestSink sink =
            new StagingGraphIngestSink(store, RelationExtractor.none());

        int staged = sink.stage(List.of(FACT, FACT));

        assertThat(staged).isZero();
        assertThat(sink.stagingFailures()).isEqualTo(2);
        assertThat(sink.factsStaged()).isZero();
    }

    @Test
    @DisplayName("a clean run counts no staging failures")
    void aCleanRunCountsNoStagingFailures() {
        GraphStagingStore store = mock(GraphStagingStore.class);
        StagingGraphIngestSink sink =
            new StagingGraphIngestSink(store, RelationExtractor.none());

        assertThat(sink.stage(List.of(FACT))).isEqualTo(1);
        assertThat(sink.stagingFailures()).isZero();
    }

    @Test
    @DisplayName("reports the count where the other failure counts are reported")
    void reportsTheCountAlongsideTheOthers() throws IOException {
        GraphStagingStore store = mock(GraphStagingStore.class);
        doThrow(new IOException("disk full")).when(store).append(any());
        StagingGraphIngestSink sink =
            new StagingGraphIngestSink(store, RelationExtractor.none());

        sink.stage(List.of(FACT));

        assertThat(sink.toString()).contains("1 staging failures");
    }

    /** Extracts one fact per line of "subject relation object", as the LLM extractor dates it. */
    private static RelationExtractor lineExtractor() {
        return (documentKey, text) -> {
            Provenance origin = new Provenance(SourceType.DOCUMENT,
                LlmRelationExtractor.sourceIdOf(documentKey), 80, 1);
            List<ExtractedTriple> triples = new ArrayList<>();
            for (String line : text.split("\n")) {
                String[] parts = line.trim().split(" ");
                triples.add(ExtractedTriple.alwaysValid(parts[0], parts[1], parts[2], origin));
            }
            return triples;
        };
    }

    /** The staged facts as "subject relation object" lines, in staging order. */
    private static List<String> stagedFacts(GraphStagingStore staging) throws IOException {
        Map<Integer, String> names = new java.util.HashMap<>();
        for (String label : List.of("Ada", "Acme", "Globex", "Babbage", "Initech")) {
            names.put(staging.entityIdOf(label), label);
        }
        List<String> facts = new ArrayList<>();
        staging.forEach((subject, relation, object, validFrom, validTo, provenance) ->
            facts.add(names.get(subject) + " " + names.get(object)));
        return facts;
    }

    @Test
    @DisplayName("a replaced version's facts and snippets are withdrawn before the new ones are "
        + "staged, and other documents' are left alone")
    void aReplacementWithdrawsTheEarlierVersion(@TempDir Path dir) throws IOException {
        try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
            StagingGraphIngestSink sink = new StagingGraphIngestSink(staging, lineExtractor());
            sink.onDocumentPublished("docs:ada.md", "Ada runs Acme", 1L);
            sink.onDocumentPublished("docs:babbage.md", "Babbage runs Initech", 1L);

            sink.onDocumentReplaced("docs:ada.md", "Ada runs Globex", 2L);

            assertThat(stagedFacts(staging))
                .as("what the document used to say is gone, what it says now is staged")
                .containsExactly("Babbage Initech", "Ada Globex");
            List<String> snippets = new ArrayList<>();
            staging.forEachEntityText((entityId, snippet) -> snippets.add(snippet));
            assertThat(snippets).doesNotContain("Ada runs Acme")
                .contains("Babbage runs Initech", "Ada runs Globex");
            assertThat(sink.recordsWithdrawn())
                .as("one fact and the snippet recorded against each of its two entities")
                .isEqualTo(3);
            assertThat(sink.withdrawalFailures()).isZero();
            assertThat(sink.toString()).contains("3 withdrawn");
        }
    }

    @Test
    @DisplayName("a replacement with nothing to extract still withdraws the earlier version")
    void aReplacementWithNoFactsStillWithdraws(@TempDir Path dir) throws IOException {
        try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
            StagingGraphIngestSink sink = new StagingGraphIngestSink(staging, lineExtractor());
            sink.onDocumentPublished("docs:ada.md", "Ada runs Acme", 1L);

            new StagingGraphIngestSink(staging, RelationExtractor.none())
                .onDocumentReplaced("docs:ada.md", "nothing to say", 2L);

            assertThat(stagedFacts(staging)).isEmpty();
        }
    }

    @Test
    @DisplayName("a removed document's facts and snippets are withdrawn, and nothing is staged")
    void aRemovalWithdrawsTheDocument(@TempDir Path dir) throws IOException {
        try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
            StagingGraphIngestSink sink = new StagingGraphIngestSink(staging, lineExtractor());
            sink.onDocumentPublished("docs:ada.md", "Ada runs Acme", 1L);
            sink.onDocumentPublished("docs:babbage.md", "Babbage runs Initech", 1L);

            sink.onDocumentRemoved("docs:ada.md");

            assertThat(stagedFacts(staging)).containsExactly("Babbage Initech");
            List<String> snippets = new ArrayList<>();
            staging.forEachEntityText((entityId, snippet) -> snippets.add(snippet));
            assertThat(snippets).doesNotContain("Ada runs Acme").contains("Babbage runs Initech");
            assertThat(sink.recordsWithdrawn()).isEqualTo(3);
            assertThat(sink.factsStaged()).as("the two publications only").isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a withdrawal that fails is counted and owed, and the new version waits for it")
    void aFailedWithdrawalIsCounted() throws IOException {
        GraphStagingStore store = mock(GraphStagingStore.class);
        doThrow(new IOException("log unreadable")).when(store).erase(any(StagedErasure.class));
        StagingGraphIngestSink sink = new StagingGraphIngestSink(store, lineExtractor());

        sink.onDocumentReplaced("docs:ada.md", "Ada runs Globex", 2L);
        sink.onDocumentReplaced(null, "Ada runs Globex", 2L);
        sink.onDocumentReplaced(" ", "Ada runs Globex", 2L);
        sink.onDocumentRemoved("docs:babbage.md");

        assertThat(sink.withdrawalFailures()).isEqualTo(2);
        assertThat(sink.factsStaged())
            .as("the owed withdrawal is by document, so a retry would take these with the old")
            .isZero();
        assertThat(sink.withdraw("docs:ada.md")).isEqualTo(-1);

        sink.onDocumentPublished("docs:babbage.md", "Babbage runs Initech", 3L);
        assertThat(sink.factsStaged())
            .as("published again while its removal is still owed: nothing is staged beside it")
            .isZero();
        assertThat(sink.withdrawalFailures()).isEqualTo(4);
    }

    @Test
    @DisplayName("a refused withdrawal is made once the store takes it, and the next version "
        + "stages")
    void aRefusedWithdrawalIsMadeLater(@TempDir Path dir) throws IOException {
        try (GraphStagingStore staging = GraphStagingStore.open(dir);
             StagedWithdrawals withdrawals =
                 StagedWithdrawals.open(staging, dir.resolve(StagedWithdrawals.LOG_FILE))) {
            StagingGraphIngestSink sink = new StagingGraphIngestSink(withdrawals, lineExtractor());
            sink.onDocumentPublished("docs:ada.md", "Ada runs Acme", 1L);
            Path blocker = dir.resolve(GraphStagingStore.TRIPLE_LOG + ".erasing");
            java.nio.file.Files.createDirectories(blocker.resolve("held"));

            sink.onDocumentRemoved("docs:ada.md");
            assertThat(stagedFacts(staging)).containsExactly("Ada Acme");

            java.nio.file.Files.delete(blocker.resolve("held"));
            java.nio.file.Files.delete(blocker);
            sink.onDocumentPublished("docs:ada.md", "Ada runs Globex", 2L);

            assertThat(stagedFacts(staging))
                .as("the removed version withdrawn first, then the new one staged")
                .containsExactly("Ada Globex");
            assertThat(sink.withdrawalFailures()).isEqualTo(1);
            assertThat(sink.recordsWithdrawn()).isPositive();
        }
    }
}
