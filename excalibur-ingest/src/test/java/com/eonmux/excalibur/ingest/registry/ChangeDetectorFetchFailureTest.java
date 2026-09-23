package com.eonmux.excalibur.ingest.registry;

import com.eonmux.excalibur.ingest.source.DocumentNotFoundException;
import com.eonmux.excalibur.ingest.source.FetchDisallowedException;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
    @DisplayName("a document its publisher now forbids fetching is reported as deleted")
    void disallowedIsReportedAsDeleted() throws SourceException {
        // The same outcome as a crawl that no longer lists it: a page robots.txt disallows
        // before the walk reaches it is never listed, and one it disallows only through a
        // redirect must not be kept serving for the lack of a listing to leave it out of.
        ChangeSet changeSet = detector.detectChanges(new StubSource(() ->
            new FetchDisallowedException(DOCUMENT_ID, SOURCE_ID, "robots.txt disallows it")));

        assertThat(changeSet.getDeletedCount()).isEqualTo(1);
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

    @Test
    @DisplayName("a crawl that fails partway through deletes nothing")
    void anAbortedCrawlDeletesNothing() {
        // The deletion pass takes every registered document the crawl did not list as gone, so
        // a crawl that stopped early would tombstone almost the whole corpus if it were allowed
        // to produce a ChangeSet at all. It has to fail instead of returning a short list, and
        // a source must never answer a read error by quietly omitting the file: a file this
        // node cannot read is not a file that has been deleted.
        Source aborting = new Source() {
            @Override
            public String getSourceId() {
                return SOURCE_ID;
            }

            @Override
            public String getSourceType() {
                return "aborting";
            }

            @Override
            public Stream<String> crawl() {
                return Stream.of("other-doc").peek(id -> {
                    throw new java.io.UncheckedIOException(
                        new java.io.IOException("network went away mid-walk"));
                });
            }

            @Override
            public FetchedDocument fetch(String documentId) {
                return null;
            }
        };

        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> detector.detectChanges(aborting))
            .isInstanceOf(java.io.UncheckedIOException.class);

        assertThat(registry.getDocumentIds(SOURCE_ID))
            .as("the registered document is still registered: nothing was tombstoned")
            .contains(DOCUMENT_ID);
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
