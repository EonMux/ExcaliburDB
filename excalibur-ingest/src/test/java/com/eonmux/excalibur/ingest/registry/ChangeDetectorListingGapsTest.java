package com.eonmux.excalibur.ingest.registry;

import com.eonmux.excalibur.ingest.source.DocumentNotFoundException;
import com.eonmux.excalibur.ingest.source.FetchDisallowedException;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.ListingGaps;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A registered document a listing with gaps did not include is kept, not deleted; one a complete
 * listing did not include is deleted as before.
 */
class ChangeDetectorListingGapsTest {

    private static final String SOURCE = "site";
    private static final byte[] CONTENT = "same content".getBytes(StandardCharsets.UTF_8);

    private DocumentRegistry registry;
    private ChangeDetector detector;

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry();
        detector = new ChangeDetector(registry);
        for (String id : List.of("listed", "behind-hub-a", "behind-hub-b", "vanished",
                "forbidden")) {
            registry.register(DocumentMetadata.builder()
                .sourceId(SOURCE)
                .documentId(id)
                .contentHash(DocumentRegistry.computeContentHash(CONTENT))
                .contentLength(CONTENT.length)
                .lastModifiedMillis(1000L)
                .addChunkId(id + "#0")
                .build());
        }
    }

    @Test
    void aListingWithGapsKeepsWhatItMissedAndReportsIt() throws SourceException {
        ListingGaps.Recorder recorder = new ListingGaps.Recorder();
        recorder.record("https://site/hub", "links not read: HTTP 503");
        Site site = new Site(List.of("listed", "vanished", "forbidden", "added"),
            recorder.snapshot());

        ChangeSet changes = detector.detectChanges(site);

        assertThat(changes.getKeptUnlistedDocuments())
            .containsExactlyInAnyOrder("behind-hub-a", "behind-hub-b");
        assertThat(changes.getKeptUnlistedCount()).isEqualTo(2);
        assertThat(changes.getDeletedDocuments()).extracting(DocumentMetadata::getDocumentId)
            .as("a listed document the source answered for as gone or forbidden still goes")
            .containsExactlyInAnyOrder("vanished", "forbidden");
        assertThat(changes.getListingGaps()).isEqualTo(recorder.snapshot());
        assertThat(changes.getNewDocuments()).containsExactly("added");
        assertThat(changes.getTotalCrawled()).isEqualTo(2);
        assertThat(changes.getUnchangedCount())
            .as("the kept documents are not counted as confirmed current")
            .isEqualTo(1);
        assertThat(changes.getUncheckedCount()).isZero();
        assertThat(changes.hasChanges()).isTrue();
        assertThat(changes.toString()).contains("keptUnlisted=2", "gaps=1");
        assertThat(changes.getChunksToTombstone()).containsExactlyInAnyOrder(
            "vanished#0", "forbidden#0");
    }

    @Test
    void aCompleteListingStillDeletesWhatItMissed() throws SourceException {
        Site site = new Site(List.of("listed", "vanished", "forbidden", "added"),
            ListingGaps.NONE);

        ChangeSet changes = detector.detectChanges(site);

        assertThat(changes.getKeptUnlistedDocuments()).isEmpty();
        assertThat(changes.getDeletedDocuments()).extracting(DocumentMetadata::getDocumentId)
            .containsExactlyInAnyOrder("behind-hub-a", "behind-hub-b", "vanished", "forbidden");
        assertThat(changes.getListingGaps().isComplete()).isTrue();
        assertThat(changes.getUnchangedCount()).isEqualTo(1);
    }

    @Test
    void onlyKeptDocumentsIsStillSomethingToReport() throws SourceException {
        ListingGaps.Recorder recorder = new ListingGaps.Recorder();
        recorder.record("https://site/", "links not read: HTTP 503");
        registry.remove(SOURCE, "vanished");
        registry.remove(SOURCE, "forbidden");

        ChangeSet changes = detector.detectChanges(new Site(List.of("listed"),
            recorder.snapshot()));

        assertThat(changes.getDeletedCount()).isZero();
        assertThat(changes.getKeptUnlistedCount()).isEqualTo(2);
        assertThat(changes.getUnchangedCount()).isEqualTo(1);
        assertThat(changes.hasChanges()).isTrue();
    }

    /**
     * Lists what it is given and reports the given gaps; "vanished" is gone when fetched and
     * "forbidden" disallowed, the rest answer with the registered bytes.
     */
    private static final class Site implements Source {

        private final List<String> listing;
        private final ListingGaps gaps;

        Site(List<String> listing, ListingGaps gaps) {
            this.listing = listing;
            this.gaps = gaps;
        }

        @Override
        public String getSourceId() {
            return SOURCE;
        }

        @Override
        public String getSourceType() {
            return "test";
        }

        @Override
        public Stream<String> crawl() {
            return listing.stream();
        }

        @Override
        public ListingGaps getListingGaps() {
            return gaps;
        }

        @Override
        public FetchedDocument fetch(String documentId) throws SourceException {
            if (documentId.equals("vanished")) {
                throw new DocumentNotFoundException(documentId, SOURCE);
            }
            if (documentId.equals("forbidden")) {
                throw new FetchDisallowedException(documentId, SOURCE, "robots.txt says no");
            }
            return new FetchedDocument(documentId, CONTENT, "text/plain", 1000L);
        }
    }
}
