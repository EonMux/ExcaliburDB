package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.worker.CuckooMetricsWorker;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.ChunkIndexStats;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;
import com.eonmux.excalibur.retrieval.rank.Ranker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StatsEndpoint.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Response format is correct JSON</li>
 *   <li>All segment counts are non-negative</li>
 *   <li>Coverage is in range [0.0, 1.0]</li>
 *   <li>Epoch is non-negative</li>
 *   <li>Volume bytes are non-negative</li>
 *   <li>HNSW queue depth is accurate</li>
 * </ul>
 */
class StatsEndpointTest {

    private VisibilityTracker visibilityTracker;
    private SegmentCatalog segmentCatalog;
    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;

    @BeforeEach
    void setUp() {
        segmentCatalog = new SegmentCatalog();
        visibilityTracker = new VisibilityTracker(segmentCatalog);
        indexOrchestrator = mock(IndexOrchestrator.class);
        storageEngine = mock(StorageEngine.class);

        when(indexOrchestrator.getVisibilityTracker()).thenReturn(visibilityTracker);
        when(storageEngine.getCatalog()).thenReturn(segmentCatalog);
        when(indexOrchestrator.getHnswQueueDepth()).thenReturn(0L);
        when(indexOrchestrator.getHnswQueueDepthLimit()).thenReturn(100000);
        when(indexOrchestrator.getTotalVectorsIntegrated()).thenReturn(0L);
        when(indexOrchestrator.getPendingCuckooDeletions()).thenReturn(0);
        when(indexOrchestrator.getTotalCuckooDeletions()).thenReturn(0L);
        when(indexOrchestrator.getCuckooFpRates()).thenReturn(CuckooMetricsWorker.FpRates.NONE);
    }

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorTests {

        @Test
        @DisplayName("rejects null orchestrator")
        void rejectsNullOrchestrator() {
            assertThatThrownBy(() -> new StatsEndpoint((IndexOrchestrator) null, storageEngine))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("IndexOrchestrator cannot be null");
        }

        @Test
        @DisplayName("rejects null storage engine")
        void rejectsNullStorageEngine() {
            assertThatThrownBy(() -> new StatsEndpoint(indexOrchestrator, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("StorageEngine cannot be null");
        }

        @Test
        @DisplayName("rejects null visibility tracker in explicit constructor")
        void rejectsNullVisibilityTracker() {
            assertThatThrownBy(() -> new StatsEndpoint(null, segmentCatalog, indexOrchestrator, storageEngine))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("VisibilityTracker cannot be null");
        }

        @Test
        @DisplayName("rejects null segment catalog in explicit constructor")
        void rejectsNullSegmentCatalog() {
            assertThatThrownBy(() -> new StatsEndpoint(visibilityTracker, null, indexOrchestrator, storageEngine))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("SegmentCatalog cannot be null");
        }

        @Test
        @DisplayName("accepts all required dependencies")
        void acceptsRequiredDependencies() {
            assertThatCode(() -> new StatsEndpoint(indexOrchestrator, storageEngine))
                .doesNotThrowAnyException();
        }
    }

    /**
     * A chunk index that reports whatever a test wants it to.
     *
     * <p>Only {@link #stats()} is exercised here: what the endpoint has to get right is reporting
     * an index's own account of itself, not indexing.</p>
     */
    private record ReportingIndex(ChunkIndexStats reported) implements ChunkIndex {

        @Override
        public void add(String chunkId, String text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addDocument(java.util.Map<String, String> chunks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void refresh() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int erase(Set<String> chunkIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String chunkIdOf(long id) {
            return null;
        }

        @Override
        public long indexedCount() {
            return reported.indexed();
        }

        @Override
        public CompactionResult compactIfDue(java.util.function.Function<String, String> textOf) {
            return CompactionResult.declined("test");
        }

        @Override
        public CompactionResult compactNow(java.util.function.Function<String, String> textOf) {
            return CompactionResult.declined("test");
        }

        @Override
        public Ranker ranker(QueryExpander expander) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String engine() {
            return reported.engine();
        }

        @Override
        public ChunkIndexStats stats() {
            if (reported == null) {
                throw new IllegalStateException("index unavailable");
            }
            return reported;
        }

        @Override
        public void close() {
        }
    }

    @Nested
    @DisplayName("Lexical index reporting")
    class LexicalReporting {

        private String responseOf(ChunkIndex index) throws IOException {
            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            endpoint.withLexicalIndex(index);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);
            return baos.toString(StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("a node with no lexical index reports no lexical block")
        void absentIndexIsAbsentFromTheResponse() throws IOException {
            // Not a block of zeroes: an index reporting nothing and no index at all are different
            // states, and a caller charting the erasure backlog needs to tell them apart.
            assertThat(responseOf(null)).doesNotContain("\"lexical\"");
        }

        @Test
        @DisplayName("the index's counts are reported")
        void countsAreReported() throws IOException {
            String response = responseOf(new ReportingIndex(new ChunkIndexStats(
                "native", 15_000_000L, 12, 14_980_000L, 20_000L, 3,
                Optional.of(Instant.now().minusSeconds(412)), false)));

            assertThat(response).contains("\"lexical\": {");
            assertThat(response).contains("\"engine\": \"native\"");
            assertThat(response).contains("\"indexed\": 15000000");
            assertThat(response).contains("\"segments\": 12");
            assertThat(response).contains("\"liveChunks\": 14980000");
            assertThat(response).contains("\"erasedChunks\": 20000");
            assertThat(response).contains("\"erasureRequests\": 3");
            assertThat(response).contains("\"compactionDue\": false");
            assertThat(response).contains("\"unreadableChunksDropped\": 0");
        }

        @Test
        @DisplayName("chunks dropped because their stored text was corrupt are reported")
        void unreadableChunksAreReported() throws IOException {
            // Each one is out of keyword search until its document is reindexed, and says
            // storage holds a damaged segment - neither is visible in any other count.
            String response = responseOf(new ReportingIndex(new ChunkIndexStats(
                "native", 10L, 1, 8L, 2L, 0, Optional.empty(), false, 2L)));

            assertThat(response).contains("\"unreadableChunksDropped\": 2");
        }

        @Test
        @DisplayName("a healthy index reports nothing unsearchable and lists no segment")
        void healthyIndexHasNothingUnsearchable() throws IOException {
            String response = responseOf(new ReportingIndex(new ChunkIndexStats(
                "native", 10L, 1, 10L, 0L, 0, Optional.empty(), false)));

            assertThat(response).contains("\"unsearchableChunks\": 0");
            assertThat(response).contains("\"unreadableSegments\": 0");
            assertThat(response).contains("\"outdatedSegments\": 0");
            assertThat(response).doesNotContain("\"unreadable\":");
        }

        @Test
        @DisplayName("segments that cannot be opened are named, with the chunks no query can reach")
        void unreadableSegmentsAreNamed() throws IOException {
            // Without this the node reports a healthy chunk count while keyword search misses
            // every chunk in those segments, and nothing says which or whether it will recover.
            String response = responseOf(new ReportingIndex(new ChunkIndexStats(
                "native", 0L, 3, 7L, 0L, 0, Optional.empty(), true, 0L, List.of(
                    new ChunkIndexStats.UnreadableSegment("seg-1", 2, true,
                        "post.dat fails its \"checksum\""),
                    new ChunkIndexStats.UnreadableSegment("seg-4", 1, false, "torn")), 1)));

            assertThat(response).contains("\"liveChunks\": 7");
            assertThat(response).contains("\"unsearchableChunks\": 3");
            assertThat(response).contains("\"unreadableSegments\": 2");
            assertThat(response).contains("\"outdatedSegments\": 1");
            assertThat(response).contains("\"unreadable\": [{\"segment\": \"seg-1\", \"chunks\": 2, "
                + "\"rebuildable\": true, \"reason\": \"post.dat fails its \\\"checksum\\\"\"}, "
                + "{\"segment\": \"seg-4\", \"chunks\": 1, \"rebuildable\": false, "
                + "\"reason\": \"torn\"}]");
        }

        @Test
        @DisplayName("a long list of unreadable segments is cut short, and says so")
        void unreadableListIsBounded() throws IOException {
            List<ChunkIndexStats.UnreadableSegment> many = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) {
                many.add(new ChunkIndexStats.UnreadableSegment("seg-" + i, 1, true, "damaged"));
            }
            String response = responseOf(new ReportingIndex(new ChunkIndexStats(
                "native", 0L, 40, 0L, 0L, 0, Optional.empty(), true, 0L, many, 0)));

            assertThat(response).contains("\"unreadableSegments\": 40");
            assertThat(response).contains("\"unsearchableChunks\": 40");
            assertThat(response).contains("\"seg-31\"").doesNotContain("\"seg-32\"");
            assertThat(response).contains("\"unreadableTruncated\": true");
        }

        @Test
        @DisplayName("an outstanding erasure is reported by how long it has been waiting")
        void erasureAgeIsReported() throws IOException {
            // The number an operator compares against the configured latency bound. An instant
            // would leave them doing the subtraction against a clock that is not theirs.
            String response = responseOf(new ReportingIndex(new ChunkIndexStats(
                "native", 10L, 1, 9L, 1L, 1,
                Optional.of(Instant.now().minusSeconds(600)), true)));

            assertThat(response).containsPattern("\"oldestErasureAgeMs\": 6[0-9]{5}");
            assertThat(response).contains("\"compactionDue\": true");
        }

        @Test
        @DisplayName("a node owing nothing reports no age at all")
        void nothingOwedIsNull() throws IOException {
            String response = responseOf(new ReportingIndex(new ChunkIndexStats(
                "native", 10L, 1, 10L, 0L, 0, Optional.empty(), false)));

            assertThat(response).contains("\"oldestErasureAgeMs\": null");
            assertThat(response).contains("\"erasedRatio\": 0.0000");
        }

        @Test
        @DisplayName("an index that cannot answer does not take the rest of the report with it")
        void aFailingIndexIsOmitted() throws IOException {
            String response = responseOf(new ReportingIndex(null));

            assertThat(response).doesNotContain("\"lexical\"");
            assertThat(response).contains("\"segments\":");
            assertThat(response).contains("\"coverage\":");
        }
    }

    @Nested
    @DisplayName("GET /v1/stats handling")
    class HandleTests {

        @Test
        @DisplayName("returns 200 for GET request")
        void returns200ForGet() throws IOException {
            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(200), anyLong());
        }

        @Test
        @DisplayName("returns 405 for non-GET request")
        void returns405ForNonGet() throws IOException {
            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("POST");

            endpoint.handle(exchange);

            verify(exchange).sendResponseHeaders(eq(405), anyLong());
        }

        @Test
        @DisplayName("returns JSON content type")
        void returnsJsonContentType() throws IOException {
            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            Headers headers = new Headers();
            when(exchange.getResponseHeaders()).thenReturn(headers);

            endpoint.handle(exchange);

            assertThat(headers.getFirst("Content-Type")).isEqualTo("application/json");
        }

        @Test
        @DisplayName("returns segment counts")
        void returnsSegmentCounts() throws IOException {
            addActiveSegment(segmentCatalog, 1);
            addIndexingSegment(segmentCatalog, 2);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"segments\":");
            assertThat(response).contains("\"total\": 2");
            assertThat(response).contains("\"active\": 1");
            assertThat(response).contains("\"indexing\": 1");
        }

        @Test
        @DisplayName("returns entry count")
        void returnsEntryCount() throws IOException {
            var metadata = segmentCatalog.add(1, Path.of("/tmp/segment1"));
            metadata.transitionToIndexing();
            metadata.setEntryCount(5000);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"entries\":");
            assertThat(response).contains("\"total\": 5000");
        }

        @Test
        @DisplayName("returns HNSW queue depth")
        void returnsHnswQueueDepth() throws IOException {
            when(indexOrchestrator.getHnswQueueDepth()).thenReturn(25000L);
            when(indexOrchestrator.getHnswQueueDepthLimit()).thenReturn(100000);
            when(indexOrchestrator.getTotalVectorsIntegrated()).thenReturn(1000000L);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"hnsw\":");
            assertThat(response).contains("\"queueDepth\": 25000");
            assertThat(response).contains("\"queueDepthLimit\": 100000");
            assertThat(response).contains("\"vectorsIntegrated\": 1000000");
        }

        @Test
        @DisplayName("returns Cuckoo filter stats")
        void returnsCuckooStats() throws IOException {
            when(indexOrchestrator.getPendingCuckooDeletions()).thenReturn(50);
            when(indexOrchestrator.getTotalCuckooDeletions()).thenReturn(500L);
            when(indexOrchestrator.getCuckooFpRates())
                .thenReturn(new CuckooMetricsWorker.FpRates(2, 2.4414e-6, 3.6621e-6));

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"cuckoo\":");
            assertThat(response).contains("\"pendingDeletions\": 50");
            assertThat(response).contains("\"totalDeletions\": 500");
            assertThat(response)
                .as("rates this small are the normal case and must not round to zero")
                .contains("\"avgFpRate\": 0.0000024414")
                .contains("\"maxFpRate\": 0.0000036621");
        }

        @Test
        @DisplayName("reports that a full membership filter can no longer speak for segments")
        void reportsFilterSaturation() throws IOException {
            // Saturation stops the filter ordering segments rather than turning queries wrong,
            // so it is invisible in the results of any query that finishes in time. Nothing else
            // in this response would tell an operator that the corpus capacity is now too small.
            com.eonmux.excalibur.filter.HierarchicalCuckooFilter filter =
                new com.eonmux.excalibur.filter.HierarchicalCuckooFilter(8L, 13, 8);
            // Spread over many segments, so the level holding every term fills before any one.
            for (int i = 0; i < 4096 && !filter.isSaturated(); i++) {
                String spread = String.valueOf(i % 64);
                filter.registerSegment(spread);
                filter.insert(("term-" + i).getBytes(StandardCharsets.UTF_8), spread);
            }
            assertThat(filter.isSaturated())
                .as("the corpus filter has to fill for this test to mean anything").isTrue();
            when(indexOrchestrator.getHierarchicalCuckooFilter()).thenReturn(filter);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"saturated\": true");
            assertThat(response).contains("\"saturatedSegments\": 0");
        }

        @Test
        @DisplayName("reports a healthy membership filter as unsaturated")
        void reportsNoSaturationWhenTheFilterHasRoom() throws IOException {
            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"saturated\": false");
        }

        @Test
        @DisplayName("returns visibility epoch")
        void returnsVisibilityEpoch() throws IOException {
            visibilityTracker.advanceEpoch();
            visibilityTracker.advanceEpoch();
            visibilityTracker.advanceEpoch();

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"visibility\":");
            assertThat(response).contains("\"epoch\": 3");
        }

        @Test
        @DisplayName("returns coverage for empty catalog")
        void returnsCoverageForEmptyCatalog() throws IOException {
            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"coverage\": 0.0000");
        }

        @Test
        @DisplayName("returns coverage for full catalog")
        void returnsCoverageForFullCatalog() throws IOException {
            addActiveSegment(segmentCatalog, 1);
            addActiveSegment(segmentCatalog, 2);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"coverage\": 1.0000");
        }

        @Test
        @DisplayName("returns coverage for partial catalog")
        void returnsCoverageForPartialCatalog() throws IOException {
            // Two of the three live segments are in a state a query reads; the third is
            // stranded before indexing, so a third of the corpus is unreachable.
            addActiveSegment(segmentCatalog, 1);
            addIndexingSegment(segmentCatalog, 2);
            addBootstrappingSegment(segmentCatalog, 3);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"coverage\": 0.6667");
        }

        @Test
        @DisplayName("a retired segment is not corpus this node is missing")
        void aTombstonedSegmentLeavesTheDenominator() throws IOException {
            // Compaction folds a segment into its successor and retires the original, and
            // reclamation may legitimately never come. Counting it against the node reports a
            // third of the corpus permanently gone on a node that has lost nothing.
            addActiveSegment(segmentCatalog, 1);
            addTombstonedSegment(segmentCatalog, 2);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"coverage\": 1.0000");
        }

        @Test
        @DisplayName("includes all queryable states in coverage")
        void includesAllQueryableStatesInCoverage() throws IOException {
            addActiveSegment(segmentCatalog, 1);
            addIndexingSegment(segmentCatalog, 2);
            addWarmingSegment(segmentCatalog, 3);
            addCompactingSegment(segmentCatalog, 4);

            StatsEndpoint endpoint = new StatsEndpoint(indexOrchestrator, storageEngine);
            HttpExchange exchange = createMockExchange("GET");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            when(exchange.getResponseBody()).thenReturn(baos);

            endpoint.handle(exchange);

            String response = baos.toString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"coverage\": 1.0000");
        }
    }

    @Nested
    @DisplayName("Record validation - combined")
    class RecordValidationTests {

        @Test
        @DisplayName("validates all record constraints in one test")
        void validatesAllRecordConstraints() {
            // SegmentStats - negative values rejected
            assertThatThrownBy(() -> new NodeStats.SegmentStats(-1, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats.SegmentStats(0, -1, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new NodeStats.SegmentStats(0, 0, 0, 0, 0, 0, 0))
                .doesNotThrowAnyException();

            // EntryStats - negative rejected
            assertThatThrownBy(() -> new NodeStats.EntryStats(-1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new NodeStats.EntryStats(0))
                .doesNotThrowAnyException();

            // VolumeStats - all fields validated
            assertThatThrownBy(() -> new NodeStats.VolumeStats(-1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats.VolumeStats(0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats.VolumeStats(0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new NodeStats.VolumeStats(0, 0, 0))
                .doesNotThrowAnyException();

            // HnswStats - all fields validated
            assertThatThrownBy(() -> new NodeStats.HnswStats(-1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats.HnswStats(0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats.HnswStats(0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new NodeStats.HnswStats(0, 0, 0))
                .doesNotThrowAnyException();

            // CuckooStats - all fields validated
            assertThatThrownBy(() -> new NodeStats.CuckooStats(-1, 0, 0.0, 0.0, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats.CuckooStats(0, -1, 0.0, 0.0, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats.CuckooStats(0, 0, -0.1, 0.0, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats.CuckooStats(0, 0, 0.0, -0.1, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats.CuckooStats(0, 0, 1.1, 0.0, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats.CuckooStats(0, 0, 0.0, 1.1, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats.CuckooStats(0, 0, Double.NaN, 0.0, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats.CuckooStats(0, 0, 0.0, Double.NaN, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new NodeStats.CuckooStats(0, 0, 0.0, 0.0, false, 0))
                .doesNotThrowAnyException();
            assertThatThrownBy(() -> new NodeStats.CuckooStats(0, 0, 0.0, 0.0, false, -1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new NodeStats.CuckooStats(0, 0, 1.0, 1.0, false, 0))
                .doesNotThrowAnyException();

            // VisibilityStats - all fields validated
            assertThatThrownBy(() -> new NodeStats.VisibilityStats(-1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats.VisibilityStats(0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats.VisibilityStats(0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new NodeStats.VisibilityStats(0, 0, 0))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("validates SystemStats constraints")
        void validatesSystemStatsConstraints() {
            NodeStats.SegmentStats segments = new NodeStats.SegmentStats(0, 0, 0, 0, 0, 0, 0);
            NodeStats.EntryStats entries = new NodeStats.EntryStats(0);
            NodeStats.VolumeStats volumes = new NodeStats.VolumeStats(0, 0, 0);
            NodeStats.HnswStats hnsw = new NodeStats.HnswStats(0, 0, 0);
            NodeStats.CuckooStats cuckoo = new NodeStats.CuckooStats(0, 0, 0.0, 0.0, false, 0);
            NodeStats.VisibilityStats visibility = new NodeStats.VisibilityStats(0, 0, 0);

            // Coverage validation
            assertThatThrownBy(() -> new NodeStats(
                segments, entries, volumes, hnsw, cuckoo, visibility, Double.NaN, null, null, true, null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats(
                segments, entries, volumes, hnsw, cuckoo, visibility, -0.1, null, null, true, null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NodeStats(
                segments, entries, volumes, hnsw, cuckoo, visibility, 1.1, null, null, true, null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> new NodeStats(
                segments, entries, volumes, hnsw, cuckoo, visibility, 0.0, null, null, true, null))
                .doesNotThrowAnyException();
            assertThatCode(() -> new NodeStats(
                segments, entries, volumes, hnsw, cuckoo, visibility, 1.0, null, null, true, null))
                .doesNotThrowAnyException();

            // Null validation
            assertThatThrownBy(() -> new NodeStats(
                null, entries, volumes, hnsw, cuckoo, visibility, 0.5, null, null, true, null))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new NodeStats(
                segments, null, volumes, hnsw, cuckoo, visibility, 0.5, null, null, true, null))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new NodeStats(
                segments, entries, null, hnsw, cuckoo, visibility, 0.5, null, null, true, null))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new NodeStats(
                segments, entries, volumes, null, cuckoo, visibility, 0.5, null, null, true, null))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new NodeStats(
                segments, entries, volumes, hnsw, null, visibility, 0.5, null, null, true, null))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new NodeStats(
                segments, entries, volumes, hnsw, cuckoo, null, 0.5, null, null, true, null))
                .isInstanceOf(NullPointerException.class);
            assertThatCode(() -> new NodeStats(
                segments, entries, volumes, hnsw, cuckoo, visibility, 0.5, null, null, true, null))
                .as("a node that keeps no lexical index reports no lexical block")
                .doesNotThrowAnyException();
        }
    }

    private HttpExchange createMockExchange(String method) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/stats"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        return exchange;
    }

    private void addActiveSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
    }

    private void addIndexingSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
    }

    private void addWarmingSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
    }

    private void addCompactingSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
        metadata.transitionToCompacting();
    }

    /** A segment whose file is registered but which no transition ever brought up. */
    private void addBootstrappingSegment(SegmentCatalog catalog, long segmentId) {
        catalog.registerFile(segmentId, Path.of("/tmp/segment" + segmentId));
    }

    private void addTombstonedSegment(SegmentCatalog catalog, long segmentId) {
        var metadata = catalog.add(segmentId, Path.of("/tmp/segment" + segmentId));
        metadata.transitionToIndexing();
        metadata.transitionToWarming();
        metadata.transitionToActive();
        metadata.transitionToTombstoned();
    }
}
