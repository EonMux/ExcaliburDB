package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.ingest.source.SourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a background reindex publishes about itself while it runs, and how it ends.
 *
 * <p>Two things were wrong in the same readback. A run stopped on purpose by
 * {@code DELETE /v1/reindex} was recorded as a crash, so the route's own documented follow-up -
 * poll the status to see that it stopped - could not tell a deliberate stop from a failure. And
 * the counters beside it were constants: 0 of 0 documents for the whole run, and then a total
 * taken from a chunk count.</p>
 */
@DisplayName("ReindexRun reporting")
class ReindexRunReportingTest {

    private static final String SOURCE_ID = "docs";

    private DocumentReindexer reindexer;
    private SourceResolver sourceResolver;
    private Source source;
    private ReindexEndpoint.InMemoryReindexStatusRegistry statusRegistry;
    private IngestStatusEndpoint.InMemoryIngestionStatusRegistry ingestionStatus;

    @BeforeEach
    void setUp() throws Exception {
        reindexer = mock(DocumentReindexer.class);
        sourceResolver = mock(SourceResolver.class);
        source = mock(Source.class);
        statusRegistry = new ReindexEndpoint.InMemoryReindexStatusRegistry();
        statusRegistry.startReindex(SOURCE_ID);
        ingestionStatus = new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();
        when(sourceResolver.resolve(SOURCE_ID)).thenReturn(source);
    }

    private ReindexRun run() {
        return new ReindexRun(reindexer, sourceResolver, null, null, statusRegistry, ingestionStatus);
    }

    private ReindexEndpoint.ReindexStatus status() {
        return statusRegistry.getStatus(SOURCE_ID);
    }

    private static DocumentReindexer.ReindexResult result(int indexed) {
        return new DocumentReindexer.ReindexResult.Builder(SOURCE_ID)
            .documentsIndexed(indexed)
            .chunksCreated(indexed * 7)
            .build();
    }

    /**
     * A result in which some of the documents listed turned out not to be text.
     *
     * <p>Built through {@code addDocumentResult}, the way the reindexer builds it, because
     * whether a document counts as skipped rather than failed is decided there.
     */
    private static DocumentReindexer.ReindexResult resultSkipping(int indexed, int skipped) {
        DocumentReindexer.ReindexResult.Builder builder =
            new DocumentReindexer.ReindexResult.Builder(SOURCE_ID)
                .documentsIndexed(indexed)
                .chunksCreated(indexed * 7);
        for (int i = 0; i < skipped; i++) {
            builder.addDocumentResult(new DocumentReindexer.DocumentResult(
                "photo-" + i, 0, 0, 0, 0L, "Not text"));
        }
        return builder.build();
    }

    @Nested
    @DisplayName("a run that was asked to stop")
    class Cancelled {

        @Test
        @DisplayName("is reported as cancelled when the interrupt arrives wrapped")
        void wrappedInterruptIsACancellation() throws Exception {
            when(reindexer.reindexAll(eq(source), any()))
                .thenThrow(new SourceException("fetch abandoned", new InterruptedException()));

            run().execute(SOURCE_ID);

            assertThat(status().state())
                .as("DELETE /v1/reindex tells the caller to poll here for the stop; a stop "
                    + "reported as a crash sends them looking for a fault that is not there")
                .isEqualTo(ReindexEndpoint.ReindexStatus.STATE_CANCELLED);
            assertThat(status().cancelled()).isTrue();
        }

        @Test
        @DisplayName("is reported as cancelled when only the interrupt flag survives")
        void aSetInterruptFlagIsACancellation() throws Exception {
            when(reindexer.reindexAll(eq(source), any())).thenAnswer(invocation -> {
                Thread.currentThread().interrupt();
                throw new SourceException("the crawl was cut short");
            });

            run().execute(SOURCE_ID);

            assertThat(status().cancelled())
                .as("an interruptible call can report the interrupt through the flag rather "
                    + "than through the exception it happened to throw")
                .isTrue();
            Thread.interrupted();
        }

        @Test
        @DisplayName("carries no error text, because there is no error")
        void aCancellationHasNoError() throws Exception {
            when(reindexer.reindexAll(eq(source), any()))
                .thenThrow(new SourceException("abandoned", new InterruptedException()));

            run().execute(SOURCE_ID);

            assertThat(status().lastError()).isNull();
            assertThat(status().finishedAtMs()).isPositive();
        }
    }

    @Nested
    @DisplayName("a run that genuinely failed")
    class Failed {

        @Test
        @DisplayName("is still a failure, not a cancellation")
        void anOrdinaryFailureIsNotACancellation() throws Exception {
            when(reindexer.reindexAll(eq(source), any()))
                .thenThrow(new SourceException("the root no longer exists"));

            run().execute(SOURCE_ID);

            assertThat(status().state())
                .isEqualTo(ReindexEndpoint.ReindexStatus.STATE_FAILED);
            assertThat(status().cancelled()).isFalse();
        }
    }

    @Nested
    @DisplayName("the counters a caller polls")
    class Counters {

        @Test
        @DisplayName("follow the documents the run has actually finished with")
        void countersFollowTheRun() throws Exception {
            when(reindexer.reindexAll(eq(source), any())).thenAnswer(invocation -> {
                DocumentReindexer.ReindexProgress progress = invocation.getArgument(1);
                progress.documentsDone(0, 4);
                assertThat(status().entriesTotal())
                    .as("the size of the run has to be published before the run, not after it")
                    .isEqualTo(4);
                progress.documentsDone(2, 4);
                assertThat(status().entriesProcessed()).isEqualTo(2);
                assertThat(status().progress()).isEqualTo(0.5);
                progress.documentsDone(4, 4);
                return result(4);
            });

            run().execute(SOURCE_ID);

            assertThat(status().entriesProcessed()).isEqualTo(4);
            assertThat(status().entriesTotal())
                .as("documents, not chunks: this run created 28 chunks out of 4 documents")
                .isEqualTo(4);
            assertThat(status().state()).isEqualTo("idle");
            assertThat(status().progress()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("name the phase the node is actually in")
        void thePhaseSaysWhetherTheNodeEmbeds() throws Exception {
            when(reindexer.embedsChunks()).thenReturn(false);
            when(reindexer.reindexAll(eq(source), any())).thenAnswer(invocation -> {
                DocumentReindexer.ReindexProgress progress = invocation.getArgument(1);
                progress.documentsDone(1, 2);
                assertThat(status().state())
                    .as("a node with no embedding endpoint never enters an embedding phase, "
                        + "and reporting one would be reporting work it does not do")
                    .isEqualTo("indexing");
                return result(2);
            });

            run().execute(SOURCE_ID);
        }

        @Test
        @DisplayName("name the embedding phase on a node that embeds")
        void anEmbeddingNodeSaysEmbedding() throws Exception {
            when(reindexer.embedsChunks()).thenReturn(true);
            when(reindexer.reindexAll(eq(source), any())).thenAnswer(invocation -> {
                DocumentReindexer.ReindexProgress progress = invocation.getArgument(1);
                progress.documentsDone(1, 2);
                assertThat(status().state()).isEqualTo("embedding");
                return result(2);
            });

            run().execute(SOURCE_ID);
        }
    }

    @Nested
    @DisplayName("what the source's ingestion status is told")
    class SkippedDocuments {

        @Test
        @DisplayName("a re-crawl reports what it passed over")
        void skippedDocumentsReachTheIngestionStatus() throws Exception {
            // The same test that decides a file is not text is applied on every re-crawl, and
            // entries_skipped is where the node says so. Counted into the reindex result and
            // left there, a source re-crawled over a directory that had gained three hundred
            // photographs went on reporting whatever the first crawl happened to find.
            ingestionStatus.getOrCreate(SOURCE_ID).setSkipped(4);
            when(reindexer.reindexAll(eq(source), any())).thenReturn(resultSkipping(2, 3));

            run().execute(SOURCE_ID);

            assertThat(ingestionStatus.getStatus(SOURCE_ID).entriesSkipped()).isEqualTo(3);
        }

        @Test
        @DisplayName("a re-crawl that skips nothing clears what the last one reported")
        void aCleanRunClearsTheFigure() throws Exception {
            ingestionStatus.getOrCreate(SOURCE_ID).setSkipped(12);
            when(reindexer.reindexAll(eq(source), any())).thenReturn(result(2));

            run().execute(SOURCE_ID);

            assertThat(ingestionStatus.getStatus(SOURCE_ID).entriesSkipped())
                .as("a figure carried from the run before it describes a corpus that has moved")
                .isZero();
        }

        @Test
        @DisplayName("a source with no ingestion status is not invented one")
        void anUnknownSourceIsLeftAlone() throws Exception {
            when(reindexer.reindexAll(eq(source), any())).thenReturn(resultSkipping(2, 3));

            run().execute(SOURCE_ID);

            assertThat(ingestionStatus.getStatus(SOURCE_ID))
                .as("a reindex of a source this node never ingested has no run to describe")
                .isNull();
        }
    }
}
