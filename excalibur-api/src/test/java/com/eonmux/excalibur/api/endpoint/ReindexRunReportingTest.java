package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.pipeline.ReindexCancelledException;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.ingest.source.SourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

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
            builder.addDocumentResult(DocumentReindexer.DocumentResult.skipped(
                "photo-" + i, DocumentReindexer.DocumentResult.SkipReason.NOT_TEXT, 0L));
        }
        return builder.build();
    }

    @Nested
    @DisplayName("a run that was asked to stop")
    class Cancelled {

        @Test
        @DisplayName("is reported as cancelled when the interrupt arrives wrapped")
        void wrappedInterruptIsACancellation() throws Exception {
            when(reindexer.reindexAll(eq(source), any(), any()))
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
            when(reindexer.reindexAll(eq(source), any(), any())).thenAnswer(invocation -> {
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
            when(reindexer.reindexAll(eq(source), any(), any()))
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
            when(reindexer.reindexAll(eq(source), any(), any()))
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
            when(reindexer.reindexAll(eq(source), any(), any())).thenAnswer(invocation -> {
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
            when(reindexer.reindexAll(eq(source), any(), any())).thenAnswer(invocation -> {
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
            when(reindexer.reindexAll(eq(source), any(), any())).thenAnswer(invocation -> {
                DocumentReindexer.ReindexProgress progress = invocation.getArgument(1);
                progress.documentsDone(1, 2);
                assertThat(status().state()).isEqualTo("embedding");
                return result(2);
            });

            run().execute(SOURCE_ID);
        }
    }

    @Nested
    @DisplayName("the documents a run failed on")
    class FailedDocuments {

        @Test
        @DisplayName("are counted while the run goes, and by the result it ends with")
        void failuresAreCountedAsTheRunGoes() throws Exception {
            when(reindexer.reindexAll(eq(source), any(), any())).thenAnswer(invocation -> {
                DocumentReindexer.ReindexProgress progress = invocation.getArgument(1);
                progress.documentsDone(0, 3);
                progress.documentsCounted(1, 0);
                assertThat(status().documentsFailed())
                    .as("a run that is failing on every document has to be seen doing it")
                    .isEqualTo(1);
                return new DocumentReindexer.ReindexResult.Builder(SOURCE_ID)
                    .documentsIndexed(1)
                    .addDocumentFailure("fetch", "a.txt", "timed out")
                    .addDocumentFailure("document", "b.txt", "could not be embedded")
                    .build();
            });

            run().execute(SOURCE_ID);

            assertThat(status().documentsFailed()).isEqualTo(2);
            assertThat(status().state()).isEqualTo("idle");
            assertThat(status().lastError()).contains("2 errors");
        }

        @Test
        @DisplayName("name the documents that could not be checked, and how many there were")
        void uncheckedDocumentsAreReported() throws Exception {
            DocumentReindexer.ReindexResult.Builder result =
                new DocumentReindexer.ReindexResult.Builder(SOURCE_ID).documentsUnchanged(40);
            for (int i = 0; i < DocumentReindexer.ReindexResult.UNCHECKED_SAMPLE_SIZE + 2; i++) {
                result.addUnchecked("https://example.com/page-" + i);
            }
            when(reindexer.reindexAll(eq(source), any(), any())).thenReturn(result.build());

            run().execute(SOURCE_ID);

            int unchecked = DocumentReindexer.ReindexResult.UNCHECKED_SAMPLE_SIZE + 2;
            assertThat(status().documentsUnchecked()).isEqualTo(unchecked);
            assertThat(status().documentsFailed()).isEqualTo(unchecked);
            assertThat(status().uncheckedSample())
                .hasSize(DocumentReindexer.ReindexResult.UNCHECKED_SAMPLE_SIZE)
                .startsWith("https://example.com/page-0");
            assertThat(status().state())
                .as("forty documents were confirmed current: the run did its work, and says "
                    + "what it could not")
                .isEqualTo("idle");
            assertThat(status().lastError()).isNotNull();
        }

        @Test
        @DisplayName("fail a run that indexed nothing and confirmed nothing")
        void aRunThatCouldNotCheckAnythingFailed() throws Exception {
            when(reindexer.reindexAll(eq(source), any(), any())).thenReturn(
                new DocumentReindexer.ReindexResult.Builder(SOURCE_ID)
                    .addUnchecked("https://example.com/")
                    .build());

            run().execute(SOURCE_ID);

            assertThat(status().state()).isEqualTo(ReindexEndpoint.ReindexStatus.STATE_FAILED);
            assertThat(status().documentsUnchecked()).isEqualTo(1);
        }

        @Test
        @DisplayName("are reported the moment the run knows them, so a stopped run keeps them")
        void uncheckedDocumentsSurviveACancellation() throws Exception {
            when(reindexer.reindexAll(eq(source), any(), any())).thenAnswer(invocation -> {
                DocumentReindexer.ReindexProgress progress = invocation.getArgument(1);
                progress.documentsUnchecked(2, List.of("a.txt", "b.txt"));
                progress.documentsDone(0, 5);
                progress.documentsCounted(2, 0);
                throw new ReindexCancelledException("stopped after 0 of 5");
            });

            run().execute(SOURCE_ID);

            assertThat(status().cancelled()).isTrue();
            assertThat(status().documentsFailed()).isEqualTo(2);
            assertThat(status().documentsUnchecked()).isEqualTo(2);
            assertThat(status().uncheckedSample()).containsExactly("a.txt", "b.txt");
        }
    }

    @Nested
    @DisplayName("when the source was last crawled to its end")
    class CompletedCrawl {

        @Test
        @DisplayName("moves when a re-crawl runs to its end")
        void aFinishedRunRecordsIt() throws Exception {
            IngestStatusEndpoint.IngestionStatus ingest = ingestionStatus.getOrCreate(SOURCE_ID);
            long before = System.currentTimeMillis();
            when(reindexer.reindexAll(eq(source), any(), any())).thenReturn(result(3));

            run().execute(SOURCE_ID);

            assertThat(ingest.lastCompletedMs())
                .as("a restarted node counts this source's re-crawl interval from it")
                .isBetween(before, System.currentTimeMillis());
        }

        @Test
        @DisplayName("moves when a re-crawl finished with some documents failed")
        void aFinishedRunWithFailuresRecordsIt() throws Exception {
            IngestStatusEndpoint.IngestionStatus ingest = ingestionStatus.getOrCreate(SOURCE_ID);
            when(reindexer.reindexAll(eq(source), any(), any())).thenReturn(
                new DocumentReindexer.ReindexResult.Builder(SOURCE_ID)
                    .documentsIndexed(4)
                    .addUnchecked("https://example.com/slow")
                    .build());

            run().execute(SOURCE_ID);

            assertThat(ingest.lastCompletedMs()).isPositive();
        }

        @Test
        @DisplayName("stays put when a re-crawl is stopped, or fails")
        void anUnfinishedRunDoesNot() throws Exception {
            IngestStatusEndpoint.IngestionStatus ingest = ingestionStatus.getOrCreate(SOURCE_ID);
            when(reindexer.reindexAll(eq(source), any(), any())).thenAnswer(invocation -> {
                DocumentReindexer.ReindexProgress progress = invocation.getArgument(1);
                progress.documentsDone(0, 10);
                progress.documentsCounted(0, 1);
                throw new ReindexCancelledException("stopped after 0 of 10");
            });
            run().execute(SOURCE_ID);

            when(reindexer.reindexAll(eq(source), any(), any())).thenReturn(
                new DocumentReindexer.ReindexResult.Builder(SOURCE_ID)
                    .addUnchecked("https://example.com/")
                    .build());
            statusRegistry.startReindex(SOURCE_ID);
            run().execute(SOURCE_ID);

            assertThat(ingest.lastCompletedMs()).isZero();
        }
    }

    @Nested
    @DisplayName("a run that stopped partway")
    class StoppedPartway {

        @Test
        @DisplayName("reports what it passed over before it was stopped")
        void aCancelledRunReportsItsSkips() throws Exception {
            ingestionStatus.getOrCreate(SOURCE_ID).setSkipped(40);
            when(source.getDisallowedCount()).thenReturn(2L);
            when(reindexer.reindexAll(eq(source), any(), any())).thenAnswer(invocation -> {
                DocumentReindexer.ReindexProgress progress = invocation.getArgument(1);
                progress.documentsDone(0, 10);
                progress.documentsDone(4, 10);
                progress.documentsCounted(0, 3);
                throw new ReindexCancelledException("stopped after 4 of 10");
            });

            run().execute(SOURCE_ID);

            assertThat(status().cancelled()).isTrue();
            assertThat(ingestionStatus.getStatus(SOURCE_ID).entriesSkipped())
                .as("what the run found before it stopped, not the figure of the run before it")
                .isEqualTo(3);
            assertThat(ingestionStatus.getStatus(SOURCE_ID).entriesDisallowed()).isEqualTo(2);
        }

        @Test
        @DisplayName("reports what it passed over before it broke")
        void aFailedRunReportsItsSkips() throws Exception {
            ingestionStatus.getOrCreate(SOURCE_ID).setSkipped(40);
            when(reindexer.reindexAll(eq(source), any(), any())).thenAnswer(invocation -> {
                DocumentReindexer.ReindexProgress progress = invocation.getArgument(1);
                progress.documentsDone(0, 10);
                progress.documentsCounted(1, 5);
                throw new IllegalStateException("the lexical index is closed");
            });

            run().execute(SOURCE_ID);

            assertThat(status().state()).isEqualTo(ReindexEndpoint.ReindexStatus.STATE_FAILED);
            assertThat(status().documentsFailed()).isEqualTo(1);
            assertThat(ingestionStatus.getStatus(SOURCE_ID).entriesSkipped()).isEqualTo(5);
        }

        @Test
        @DisplayName("leaves the figures alone when it stopped before judging any document")
        void aRunStoppedBeforeItStartedReportsNothing() throws Exception {
            ingestionStatus.getOrCreate(SOURCE_ID).setSkipped(40);
            ingestionStatus.getStatus(SOURCE_ID).setDisallowed(6);
            when(reindexer.reindexAll(eq(source), any(), any()))
                .thenThrow(new ReindexCancelledException("stopped while listing"));

            run().execute(SOURCE_ID);

            assertThat(ingestionStatus.getStatus(SOURCE_ID).entriesSkipped())
                .as("a run that never got past its listing found nothing to replace them with")
                .isEqualTo(40);
            assertThat(ingestionStatus.getStatus(SOURCE_ID).entriesDisallowed()).isEqualTo(6);
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
            when(reindexer.reindexAll(eq(source), any(), any())).thenReturn(resultSkipping(2, 3));

            run().execute(SOURCE_ID);

            assertThat(ingestionStatus.getStatus(SOURCE_ID).entriesSkipped()).isEqualTo(3);
        }

        @Test
        @DisplayName("an empty document is not reported as passed over")
        void onlyDocumentsThatAreNotTextAreReported() throws Exception {
            // The first crawl counts only documents that are not text. An empty file, or one
            // the chunker finds nothing in, is skipped by a re-crawl too, but reporting it
            // there would move the figure between two runs over the same unchanged source.
            ingestionStatus.getOrCreate(SOURCE_ID).setSkipped(1);
            DocumentReindexer.ReindexResult result =
                new DocumentReindexer.ReindexResult.Builder(SOURCE_ID)
                    .addDocumentResult(DocumentReindexer.DocumentResult.skipped(
                        "photo.jpg", DocumentReindexer.DocumentResult.SkipReason.NOT_TEXT, 0L))
                    .addDocumentResult(DocumentReindexer.DocumentResult.skipped(
                        "empty.txt", DocumentReindexer.DocumentResult.SkipReason.EMPTY_CONTENT, 0L))
                    .addDocumentResult(DocumentReindexer.DocumentResult.skipped(
                        "blank.txt", DocumentReindexer.DocumentResult.SkipReason.NO_CHUNKS, 0L))
                    .addDocumentResult(new DocumentReindexer.DocumentResult(
                        "doc.txt", 7, 7, 0, 0L, null))
                    .build();
            when(reindexer.reindexAll(eq(source), any(), any())).thenReturn(result);

            run().execute(SOURCE_ID);

            assertThat(ingestionStatus.getStatus(SOURCE_ID).entriesSkipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("a re-crawl that skips nothing clears what the last one reported")
        void aCleanRunClearsTheFigure() throws Exception {
            ingestionStatus.getOrCreate(SOURCE_ID).setSkipped(12);
            when(reindexer.reindexAll(eq(source), any(), any())).thenReturn(result(2));

            run().execute(SOURCE_ID);

            assertThat(ingestionStatus.getStatus(SOURCE_ID).entriesSkipped())
                .as("a figure carried from the run before it describes a corpus that has moved")
                .isZero();
        }

        @Test
        @DisplayName("a re-crawl reports what the site's robots.txt kept it out of")
        void disallowedDocumentsReachTheIngestionStatus() throws Exception {
            // Read from the source, which was refused: a URL robots.txt disallows is never
            // listed, so the reindexer only ever sees it as a document that has gone.
            ingestionStatus.getOrCreate(SOURCE_ID).setDisallowed(9);
            when(source.getDisallowedCount()).thenReturn(4L);
            when(reindexer.reindexAll(eq(source), any(), any())).thenReturn(result(2));

            run().execute(SOURCE_ID);

            assertThat(ingestionStatus.getStatus(SOURCE_ID).entriesDisallowed()).isEqualTo(4);
        }

        @Test
        @DisplayName("a re-crawl nothing was disallowed from clears what the last one reported")
        void aRunWithNothingDisallowedClearsTheFigure() throws Exception {
            ingestionStatus.getOrCreate(SOURCE_ID).setDisallowed(9);
            when(reindexer.reindexAll(eq(source), any(), any())).thenReturn(result(2));

            run().execute(SOURCE_ID);

            assertThat(ingestionStatus.getStatus(SOURCE_ID).entriesDisallowed()).isZero();
        }

        @Test
        @DisplayName("a source with no ingestion status is not invented one")
        void anUnknownSourceIsLeftAlone() throws Exception {
            when(reindexer.reindexAll(eq(source), any(), any())).thenReturn(resultSkipping(2, 3));

            run().execute(SOURCE_ID);

            assertThat(ingestionStatus.getStatus(SOURCE_ID))
                .as("a reindex of a source this node never ingested has no run to describe")
                .isNull();
        }
    }
}
