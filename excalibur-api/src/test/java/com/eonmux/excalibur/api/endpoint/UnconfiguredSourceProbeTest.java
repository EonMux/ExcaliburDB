package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.ingest.source.DocumentNotFoundException;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a node with no ingestion pipeline says about a source it has accepted.
 *
 * <p>The defect these exist to prevent is a silent one: registration succeeds, a crawl counts the
 * files, and the status settles on {@code idle} at {@code progress: 1.0} with the crawled count
 * reported as {@code entries_ingested} - a node that indexed nothing, describing itself exactly as
 * a node that indexed everything. The single configuration mistake that stops the product working
 * is then invisible from the outside.</p>
 */
@DisplayName("Unconfigured source probe")
class UnconfiguredSourceProbeTest {

    /** A source whose crawl yields the ids it was given, or fails. */
    private static class FakeSource implements Source {
        private final String[] ids;
        private final SourceException failure;

        private FakeSource(String[] ids, SourceException failure) {
            this.ids = ids;
            this.failure = failure;
        }

        static FakeSource holding(String... ids) {
            return new FakeSource(ids, null);
        }

        static FakeSource failing(String message) {
            return new FakeSource(null, new SourceException(message));
        }

        @Override
        public String getSourceId() {
            return "docs";
        }

        @Override
        public String getSourceType() {
            return "filesystem";
        }

        @Override
        public Stream<String> crawl() throws SourceException {
            if (failure != null) {
                throw failure;
            }
            return Stream.of(ids);
        }

        @Override
        public FetchedDocument fetch(String documentId)
                throws SourceException, DocumentNotFoundException {
            throw new DocumentNotFoundException(documentId);
        }
    }

    private static IngestStatusEndpoint.IngestionStatus probe(Source source) {
        IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry =
            new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();
        new UnconfiguredSourceProbe(source, registry.getOrCreate(source.getSourceId())).run();
        return registry.getStatus(source.getSourceId());
    }

    @Test
    @DisplayName("a probe that outlives its source reports nothing")
    void aProbeCannotResurrectARemovedStatus() {
        // The probe runs on its own thread and can finish long after the source was removed. It
        // used to look its status up by id when it started, which put back what removal had
        // cleared - in a registry that would never be asked to remove it again, since the source
        // itself was already gone.
        Source source = FakeSource.holding("a.md", "b.md");
        IngestStatusEndpoint.InMemoryIngestionStatusRegistry registry =
            new IngestStatusEndpoint.InMemoryIngestionStatusRegistry();
        UnconfiguredSourceProbe probe =
            new UnconfiguredSourceProbe(source, registry.getOrCreate(source.getSourceId()));

        registry.remove(source.getSourceId());
        probe.run();

        assertThat(registry.getStatus(source.getSourceId()))
            .as("a status entry nothing can clear outlives the source forever")
            .isNull();
        assertThat(registry.getAllStatuses()).isEmpty();
    }

    @Test
    @DisplayName("nothing indexed is not reported as everything indexed")
    void nothingIngestedIsReportedAsNothingIngested() {
        IngestStatusEndpoint.IngestionStatus status =
            probe(FakeSource.holding("a.md", "b.md"));

        assertThat(status.entriesIngested())
            .as("no document was chunked, embedded or indexed")
            .isZero();
        assertThat(status.progress())
            .as("a completed run and a run that never happened must not both read 1.0")
            .isZero();
        assertThat(status.state())
            .isEqualTo(IngestStatusEndpoint.IngestionStatus.STATE_UNCONFIGURED)
            .isNotEqualTo("idle");
    }

    @Test
    @DisplayName("the documents it found are reported as pending, because they are")
    void discoveredDocumentsArePending() {
        assertThat(probe(FakeSource.holding("a.md", "b.md", "c.md")).entriesPending())
            .isEqualTo(3);
    }

    @Test
    @DisplayName("the status says where the reason is, and does not guess at one")
    void theReasonIsActionable() {
        String reason = probe(FakeSource.holding("a.md")).lastError();

        assertThat(reason).isNotNull();
        assertThat(reason)
            .as("an operator has to be able to act on this without reading the source")
            .contains("no ingestion pipeline")
            .contains("startup log");
        assertThat(reason)
            .as("a missing API key is lexical-only ingestion, not this: naming it here sends an "
                + "operator to set a key that would not have changed anything")
            .doesNotContain("excalibur.llm.api-key")
            .doesNotContain("EXCALIBUR_LLM_API_KEY");
    }

    @Test
    @DisplayName("an empty source is still reported as unconfigured, not as done")
    void anEmptySourceIsStillUnconfigured() {
        IngestStatusEndpoint.IngestionStatus status = probe(FakeSource.holding());

        assertThat(status.state())
            .isEqualTo(IngestStatusEndpoint.IngestionStatus.STATE_UNCONFIGURED);
        assertThat(status.entriesPending()).isZero();
        assertThat(status.lastError()).isNotNull();
    }

    @Test
    @DisplayName("a source that cannot be crawled is a different failure with a different fix")
    void aCrawlFailureIsReportedAsItself() {
        IngestStatusEndpoint.IngestionStatus status =
            probe(FakeSource.failing("permission denied: /srv/docs"));

        // Conflating the two would send an operator to fix an API key when the path is wrong.
        assertThat(status.lastError())
            .contains("permission denied")
            .doesNotContain("no ingestion pipeline");
        assertThat(status.state())
            .isNotEqualTo(IngestStatusEndpoint.IngestionStatus.STATE_UNCONFIGURED);
    }

    @Test
    @DisplayName("the probe leaves the source open for the registry that owns it")
    void theSourceIsNotClosed() {
        java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        Source source = new FakeSource(new String[] { "a.md" }, null) {
            @Override
            public void close() {
                closed.set(true);
            }
        };

        probe(source);

        assertThat(closed)
            .as("the registry reuses this source for every later reindex")
            .isFalse();
    }
}
