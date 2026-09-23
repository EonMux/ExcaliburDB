package com.eonmux.excalibur.ingest.registry;

import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.IncompleteCrawlException;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a crawl that stopped early is allowed to mean.
 *
 * <p>Change detection infers deletions by subtraction: every document the registry holds that the
 * crawl did not list is taken to have been removed from the source, and the reindex tombstones it.
 * That inference is only sound over a complete listing. A crawl cut short - because the source was
 * deregistered while it was running, or the walk hit something it could not read - lists a prefix
 * of the corpus, and reading it as the whole corpus deletes everything the crawl never reached.</p>
 */
@DisplayName("A crawl that ended early")
class TruncatedCrawlTest {

    /** A source whose listing stops partway, the way a deregistration mid-crawl ends one. */
    private static final class TruncatingSource implements Source {

        private final List<String> listed;

        TruncatingSource(List<String> listed) {
            this.listed = listed;
        }

        @Override
        public String getSourceId() {
            return "docs";
        }

        @Override
        public String getSourceType() {
            return "truncating";
        }

        @Override
        public Stream<String> crawl() {
            Stream<String> truncated = Stream.<String>generate(() -> {
                throw new IncompleteCrawlException(
                    "Crawl of source docs ended because the source was closed. What it listed is "
                        + "not the full contents of the source and must not be read as one.");
            }).limit(1);
            return Stream.concat(listed.stream(), truncated);
        }

        @Override
        public FetchedDocument fetch(String documentId) {
            return new FetchedDocument(documentId,
                ("content of " + documentId).getBytes(StandardCharsets.UTF_8), "text/plain");
        }

        @Override
        public void close() {
        }
    }

    private static DocumentRegistry registryHolding(String... documentIds) {
        DocumentRegistry registry = new DocumentRegistry();
        for (String documentId : documentIds) {
            registry.register(DocumentMetadata.builder()
                .sourceId("docs")
                .documentId(documentId)
                .contentHash(documentId.hashCode())
                .contentLength(10)
                .addChunkId("docs:" + documentId + ":chunk:0")
                .build());
        }
        return registry;
    }

    @Test
    @DisplayName("is reported as a failure rather than as a listing of the whole source")
    void truncationIsReportedAsAFailure() {
        DocumentRegistry registry = registryHolding("a.txt", "b.txt", "c.txt");
        ChangeDetector detector = new ChangeDetector(registry);

        assertThatThrownBy(() -> detector.detectChanges(new TruncatingSource(List.of("a.txt"))))
            .isInstanceOf(SourceException.class)
            .hasMessageContaining("not the full contents");
    }

    @Test
    @DisplayName("deletes nothing, because what it did not list may simply not have been reached")
    void truncationDeletesNothing() {
        DocumentRegistry registry = registryHolding("a.txt", "b.txt", "c.txt");
        ChangeDetector detector = new ChangeDetector(registry);

        assertThatThrownBy(() -> detector.detectChanges(new TruncatingSource(List.of("a.txt"))))
            .isInstanceOf(SourceException.class);

        assertThat(registry.getDocumentIds("docs"))
            .as("a truncated crawl must not be able to empty the corpus")
            .containsExactlyInAnyOrder("a.txt", "b.txt", "c.txt");
    }

    @Test
    @DisplayName("an incremental detection is cut short the same way")
    void incrementalDetectionAlsoFails() {
        DocumentRegistry registry = registryHolding("a.txt", "b.txt", "c.txt");
        ChangeDetector detector = new ChangeDetector(registry);

        assertThatThrownBy(() -> detector.detectChangesIncremental(
            new TruncatingSource(List.of("a.txt")), 10, batch -> { }))
            .isInstanceOf(SourceException.class)
            .hasMessageContaining("not the full contents");
    }
}
