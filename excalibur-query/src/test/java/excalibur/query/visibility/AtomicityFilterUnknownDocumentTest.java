package excalibur.query.visibility;

import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.visibility.DocumentVisibilityTracker;
import excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import excalibur.query.QueryResultItem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the three states the atomicity filter has to tell apart.
 *
 * <p>It told apart two. A document the checker had never seen was treated exactly like one
 * known to be half-written, so after a restart -- when the in-memory tracker is empty and every
 * previously ingested document is unknown -- the first newly ingested document switched the
 * filter on and every result from the older ones vanished. Silently, and permanently for that
 * process.
 */
@DisplayName("Atomicity filter, unknown documents")
class AtomicityFilterUnknownDocumentTest {

    private static final long UNBOUNDED = Long.MAX_VALUE;

    private static QueryResultItem resultFor(String documentKey, long nodeId) {
        return new QueryResultItem(documentKey, 0.9f, null, 1L, nodeId);
    }

    private static DocumentMetadata metadata(String documentId, String... chunkIds) {
        return DocumentMetadata.builder()
            .sourceId("src")
            .documentId(documentId)
            .contentHash(documentId.hashCode())
            .contentLength(10)
            .chunkIds(List.of(chunkIds))
            .indexTimeMillis(1)
            .build();
    }

    @Test
    @DisplayName("a document from a previous process survives the filter")
    void unknownDocumentPassesThrough() {
        // The restart case, in miniature: one document this process ingested, one it never saw.
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(new DocumentRegistry());
        long epoch = tracker.advanceEpoch();
        tracker.registerFromMetadata(metadata("new", "src:new:chunk:0"), epoch);

        DocumentAtomicityFilter filter =
            new DocumentAtomicityFilter(new DocumentVisibilityTrackerAdapter(tracker));

        List<QueryResultItem> filtered = filter.filterPartialDocuments(
            List.of(resultFor("src:new", 1), resultFor("src:from-before-restart", 2)), UNBOUNDED);

        assertThat(filtered).hasSize(2);
        assertThat(filtered).extracting(QueryResultItem::documentIdStr)
            .containsExactly("src:new", "src:from-before-restart");
    }

    @Test
    @DisplayName("a document still being written is excluded")
    void inFlightDocumentIsExcluded() {
        // The state the filter exists for, and which nothing used to enter: a document whose
        // chunks are in storage and reachable by a tail scan, but which has not been published.
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(new DocumentRegistry());
        long epoch = tracker.advanceEpoch();
        tracker.registerFromMetadata(metadata("done", "src:done:chunk:0"), epoch);

        tracker.beginIngestion("src:in-flight", 2);
        tracker.addPendingChunk("src:in-flight", "src:in-flight:chunk:0");

        DocumentAtomicityFilter filter =
            new DocumentAtomicityFilter(new DocumentVisibilityTrackerAdapter(tracker));

        List<QueryResultItem> filtered = filter.filterPartialDocuments(
            List.of(resultFor("src:done", 1), resultFor("src:in-flight", 2)), UNBOUNDED);

        assertThat(filtered).extracting(QueryResultItem::documentIdStr).containsExactly("src:done");
    }

    @Test
    @DisplayName("publishing an in-flight document makes it visible")
    void publishingClearsTheInFlightMarker() {
        // The marker has to be cleared by the publish, or the document is invisible forever --
        // which is the same failure as before, just reached from the other direction.
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(new DocumentRegistry());
        tracker.beginIngestion("src:doc", 1);
        tracker.addPendingChunk("src:doc", "src:doc:chunk:0");

        DocumentAtomicityFilter filter =
            new DocumentAtomicityFilter(new DocumentVisibilityTrackerAdapter(tracker));
        assertThat(filter.filterPartialDocuments(List.of(resultFor("src:doc", 1)), UNBOUNDED))
            .isEmpty();

        long epoch = tracker.advanceEpoch();
        tracker.registerFromMetadata(metadata("doc", "src:doc:chunk:0"), epoch);

        assertThat(filter.filterPartialDocuments(List.of(resultFor("src:doc", 1)), UNBOUNDED))
            .hasSize(1);
        assertThat(tracker.isDocumentTracked("src:doc")).isTrue();
    }

    @Test
    @DisplayName("an aborted document stops being excluded and stops being held")
    void abortingClearsTheInFlightMarker() {
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(new DocumentRegistry());
        tracker.beginIngestion("src:doomed", 1);

        assertThat(tracker.isDocumentTracked("src:doomed")).isTrue();
        tracker.abortIngestion("src:doomed");
        assertThat(tracker.isDocumentTracked("src:doomed")).isFalse();
    }

    @Test
    @DisplayName("a result with no document identity is still passed through")
    void resultsWithoutIdentityPassThrough() {
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(new DocumentRegistry());
        long epoch = tracker.advanceEpoch();
        tracker.registerFromMetadata(metadata("known", "src:known:chunk:0"), epoch);

        DocumentAtomicityFilter filter =
            new DocumentAtomicityFilter(new DocumentVisibilityTrackerAdapter(tracker));

        QueryResultItem anonymous = new QueryResultItem((String) null, 0.5f, null, 1L, 7L);

        assertThat(filter.filterPartialDocuments(List.of(anonymous), UNBOUNDED)).hasSize(1);
    }
}
