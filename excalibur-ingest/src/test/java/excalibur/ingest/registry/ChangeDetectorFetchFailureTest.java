package excalibur.ingest.registry;

import excalibur.ingest.source.DocumentNotFoundException;
import excalibur.ingest.source.FetchedDocument;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies how {@link ChangeDetector} classifies a document it crawled but could not fetch.
 *
 * <p>The two failure kinds must not be conflated. A 404 means the document is genuinely gone and
 * has to be tombstoned; any other failure is transient and must leave the indexed version alone,
 * because tombstoning on a timeout would delete a document that still exists.</p>
 */
class ChangeDetectorFetchFailureTest {

    private static final String SOURCE_ID = "failing-source";
    private static final String DOCUMENT_ID = "doc-1";

    private DocumentRegistry registry;
    private ChangeDetector detector;

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry();
        registry.register(DocumentMetadata.builder()
            .sourceId(SOURCE_ID)
            .documentId(DOCUMENT_ID)
            .contentHash(123L)
            .contentLength(10)
            .addChunkId(SOURCE_ID + ":" + DOCUMENT_ID + ":chunk:0")
            .build());
        detector = new ChangeDetector(registry);
    }

    @Test
    @DisplayName("a document that 404s during change detection is reported as deleted")
    void notFoundIsReportedAsDeleted() throws SourceException {
        ChangeSet changeSet = detector.detectChanges(
            new StubSource(() -> new DocumentNotFoundException(DOCUMENT_ID, SOURCE_ID)));

        assertThat(changeSet.getDeletedCount()).isEqualTo(1);
        assertThat(changeSet.getDeletedDocuments().get(0).getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(changeSet.getChangedCount()).isZero();
    }

    @Test
    @DisplayName("a transient fetch failure leaves the indexed version in place")
    void transientFailureKeepsIndexedVersion() throws SourceException {
        ChangeSet changeSet = detector.detectChanges(
            new StubSource(() -> new SourceException("connection reset", SOURCE_ID, true)));

        assertThat(changeSet.getDeletedCount())
            .as("a document that merely failed to fetch must not be tombstoned")
            .isZero();
        assertThat(changeSet.getChangedCount()).isZero();
        assertThat(changeSet.getNewCount()).isZero();
    }

    @Test
    @DisplayName("a document that fetches cleanly is compared as usual")
    void successfulFetchIsCompared() throws SourceException {
        ChangeSet changeSet = detector.detectChanges(new StubSource(null));

        assertThat(changeSet.getChangedCount()).isEqualTo(1);
        assertThat(changeSet.getDeletedCount()).isZero();
    }

    /**
     * Crawls exactly one document and fails its fetch with a caller-supplied exception.
     */
    private static final class StubSource implements Source {

        private final java.util.function.Supplier<SourceException> failure;

        StubSource(java.util.function.Supplier<SourceException> failure) {
            this.failure = failure;
        }

        @Override
        public String getSourceId() {
            return SOURCE_ID;
        }

        @Override
        public String getSourceType() {
            return "stub";
        }

        @Override
        public String getDescription() {
            return "stub";
        }

        @Override
        public Stream<String> crawl() {
            return List.of(DOCUMENT_ID).stream();
        }

        @Override
        public boolean hasChanged(Instant since) {
            return true;
        }

        @Override
        public FetchedDocument fetch(String documentId) throws SourceException {
            if (failure != null) {
                throw failure.get();
            }
            return new FetchedDocument(documentId,
                "brand new content".getBytes(StandardCharsets.UTF_8),
                "text/plain",
                System.currentTimeMillis());
        }

        @Override
        public void close() {
        }
    }
}
