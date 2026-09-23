package com.eonmux.excalibur.query.executor;

import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Searching one segment, as a unit of work that must not throw.
 *
 * <p>These tasks run in parallel across every segment a query touches, and the dispatcher collects
 * partial results. That only works if a task reports failure in its result rather than raising:
 * an exception escaping {@code call()} takes down the whole fan-out over one bad segment. So the
 * error paths below assert on the returned result, not on a thrown exception.</p>
 *
 * <p>The other property here is that the graph search and the tail scan together cover every
 * entry. The watermark is captured <em>before</em> the search, which can only re-scan entries the
 * search already returned - and those duplicates have to be collapsed, or one entry occupies two
 * of the k slots the caller asked for.</p>
 */
@DisplayName("Segment query task")
class SegmentQueryTaskTest {

    private static final int DIMENSION = 8;
    private static final long SEGMENT_ID = 3L;

    @TempDir
    Path tempDir;

    private Arena arena;
    private Segment segment;
    private HnswGraph graph;
    private DistanceFunction distance;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        distance = CosineSimilarity.getInstance();
        segment = new Segment(tempDir.resolve("segment.dat"), 4L * 1024 * 1024, arena);
        graph = new HnswGraph(tempDir.resolve("graph.hnsw"),
            HnswConfig.create(DIMENSION), distance, 10_000);
    }

    @AfterEach
    void tearDown() {
        if (graph != null) {
            try {
                graph.close();
            } catch (Exception ignored) {
                // The task under test never owns these; closing is only tidy-up.
            }
        }
        if (segment != null) {
            try {
                segment.close();
            } catch (Exception ignored) {
                // As above.
            }
        }
        if (arena != null) {
            arena.close();
        }
    }

    private static float[] vector(float seed) {
        float[] values = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            values[i] = seed + i * 0.125f;
        }
        return values;
    }

    private SegmentQueryTask task(int k) {
        return new SegmentQueryTask(SEGMENT_ID, segment, graph, vector(1.0f), k, 1, distance);
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("required collaborators are refused when null")
        void nullsAreRefused() {
            assertThatThrownBy(() -> new SegmentQueryTask(
                SEGMENT_ID, null, graph, vector(1f), 1, 1, distance))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SegmentQueryTask(
                SEGMENT_ID, segment, null, vector(1f), 1, 1, distance))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SegmentQueryTask(
                SEGMENT_ID, segment, graph, null, 1, 1, distance))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SegmentQueryTask(
                SEGMENT_ID, segment, graph, vector(1f), 1, 1, null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("k must be positive and oversampling at least one")
        void boundsAreChecked() {
            assertThatThrownBy(() -> new SegmentQueryTask(
                SEGMENT_ID, segment, graph, vector(1f), 0, 1, distance))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k must be positive");
            assertThatThrownBy(() -> new SegmentQueryTask(
                SEGMENT_ID, segment, graph, vector(1f), -1, 1, distance))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SegmentQueryTask(
                SEGMENT_ID, segment, graph, vector(1f), 1, 0, distance))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Oversampling");
        }

        @Test
        @DisplayName("a null candidate set is treated as no pre-filter, not as an empty one")
        void nullCandidatesMeanFullSearch() throws Exception {
            // An empty candidate set makes HnswGraph return nothing. Conflating "no LSH
            // pre-filter ran" with "the pre-filter selected nothing" would empty every result.
            for (int i = 0; i < 5; i++) {
                graph.insert(vector(i));
            }

            SegmentQueryTask.SegmentQueryResult result = new SegmentQueryTask(
                SEGMENT_ID, segment, graph, vector(1f), 3, 1, distance, null).call();

            assertThat(result.success()).isTrue();
            assertThat(result.results()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("searching")
    class Searching {

        @Test
        @DisplayName("an empty graph returns an empty successful result")
        void emptyGraphSucceedsWithNothing() throws Exception {
            SegmentQueryTask.SegmentQueryResult result = task(5).call();

            assertThat(result.success())
                .as("nothing to find is not a failure")
                .isTrue();
            assertThat(result.results()).isEmpty();
            assertThat(result.errorMessage()).isNull();
            assertThat(result.hasError()).isFalse();
            assertThat(result.segmentId()).isEqualTo(SEGMENT_ID);
        }

        @Test
        @DisplayName("results never exceed the k that was asked for")
        void resultsAreLimitedToK() throws Exception {
            for (int i = 0; i < 40; i++) {
                graph.insert(vector(i * 0.5f));
            }

            assertThat(task(5).call().results()).hasSizeLessThanOrEqualTo(5);
            assertThat(task(1).call().results()).hasSizeLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("results are ordered by increasing distance")
        void resultsAreRanked() throws Exception {
            for (int i = 0; i < 20; i++) {
                graph.insert(vector(i * 0.5f));
            }

            List<HnswSearchResult> results = task(10).call().results();

            assertThat(results).isNotEmpty();
            for (int i = 1; i < results.size(); i++) {
                assertThat(results.get(i).distance())
                    .as("result %d is no closer than result %d", i, i - 1)
                    .isGreaterThanOrEqualTo(results.get(i - 1).distance());
            }
        }

        @Test
        @DisplayName("no node appears twice in one result set")
        void resultsAreDeduplicated() throws Exception {
            // The tail range is captured before the search, so an entry can legitimately be
            // returned by both. Keeping both would spend two of the k slots on one entry.
            for (int i = 0; i < 20; i++) {
                graph.insert(vector(i * 0.5f));
            }

            List<Long> nodeIds = task(10).call().results().stream()
                .map(HnswSearchResult::nodeId).toList();

            assertThat(nodeIds).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("an oversampling factor widens the search without widening the answer")
        void oversamplingDoesNotChangeTheResultSize() throws Exception {
            for (int i = 0; i < 30; i++) {
                graph.insert(vector(i * 0.5f));
            }

            SegmentQueryTask.SegmentQueryResult wide = new SegmentQueryTask(
                SEGMENT_ID, segment, graph, vector(1f), 5, 8, distance).call();

            assertThat(wide.success()).isTrue();
            assertThat(wide.results()).hasSizeLessThanOrEqualTo(5);
        }

        @Test
        @DisplayName("a huge k and oversampling factor saturate rather than wrapping negative")
        void searchWidthSaturates() throws Exception {
            // k is client-controlled and the factor is configurable; their product overflows int
            // long before either is unreasonable on its own, and a negative search width is not
            // a smaller search, it is nonsense.
            graph.insert(vector(1f));

            SegmentQueryTask.SegmentQueryResult result = new SegmentQueryTask(
                SEGMENT_ID, segment, graph, vector(1f),
                Integer.MAX_VALUE, 1000, distance).call();

            assertThat(result.success()).isTrue();
            assertThat(result.results()).hasSizeLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("an empty candidate set adds nothing and restricts nothing")
        void emptyCandidateSetIsAPlainSearch() throws Exception {
            // HnswGraph reads an empty candidate set as "nothing is admissible". An LSH index
            // that named nothing has not said the segment holds nothing, so the task must not
            // pass that reading on.
            for (int i = 0; i < 5; i++) {
                graph.insert(vector(i));
            }

            SegmentQueryTask.SegmentQueryResult withEmpty = new SegmentQueryTask(
                SEGMENT_ID, segment, graph, vector(1f), 3, 1, distance, Set.of()).call();
            SegmentQueryTask.SegmentQueryResult without = new SegmentQueryTask(
                SEGMENT_ID, segment, graph, vector(1f), 3, 1, distance, null).call();

            assertThat(withEmpty.success()).isTrue();
            assertThat(withEmpty.results()).extracting(HnswSearchResult::nodeId)
                .hasSize(3)
                .containsExactlyElementsOf(without.results().stream()
                    .map(HnswSearchResult::nodeId).toList());
        }

        @Test
        @DisplayName("a candidate set never confines the search to its members")
        void candidatesDoNotConstrainTheSearch() throws Exception {
            // An LSH bucket bounds nothing about the distance of what it leaves out, so a
            // segment holding ten nodes answers a top-10 with ten, whoever the index named.
            List<Long> inserted = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                inserted.add(graph.insert(vector(i)));
            }
            Set<Long> candidates = Set.of(inserted.get(8), inserted.get(9));

            SegmentQueryTask.SegmentQueryResult result = new SegmentQueryTask(
                SEGMENT_ID, segment, graph, vector(0f), 10, 1, distance, candidates).call();

            assertThat(result.success()).isTrue();
            assertThat(result.results()).extracting(HnswSearchResult::nodeId)
                .containsExactlyInAnyOrderElementsOf(inserted);
        }

        @Test
        @DisplayName("a named node the graph search passed over is ranked in by its distance")
        void aMissedNeighbourNamedByTheIndexIsRecovered() throws Exception {
            // The graph search is approximate. Stand in for a beam that walked past the true
            // nearest node, and check the candidate the index named is measured and ranked in
            // ahead of the farther results the search did return.
            MissingNearestGraph lossy = new MissingNearestGraph(tempDir.resolve("lossy.hnsw"));
            try {
                List<Long> inserted = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    inserted.add(lossy.insert(vector(i)));
                }
                long nearest = inserted.get(0);
                lossy.hide(nearest);

                SegmentQueryTask.SegmentQueryResult blind = new SegmentQueryTask(
                    SEGMENT_ID, segment, lossy, vector(0f), 3, 1, distance, null).call();
                SegmentQueryTask.SegmentQueryResult named = new SegmentQueryTask(
                    SEGMENT_ID, segment, lossy, vector(0f), 3, 1, distance, Set.of(nearest)).call();

                assertThat(blind.results()).extracting(HnswSearchResult::nodeId)
                    .as("the fixture's graph search misses the nearest node")
                    .doesNotContain(nearest);
                assertThat(named.results()).hasSize(3);
                assertThat(named.results().get(0).nodeId())
                    .as("the named node is the closest, so it leads the answer")
                    .isEqualTo(nearest);
                assertThat(named.results().subList(1, 3))
                    .extracting(HnswSearchResult::nodeId)
                    .as("and it displaces only the farthest of what the search found")
                    .containsExactlyElementsOf(blind.results().subList(0, 2).stream()
                        .map(HnswSearchResult::nodeId).toList());
            } finally {
                lossy.close();
            }
        }

        @Test
        @DisplayName("the task reports how long it took")
        void durationIsReported() throws Exception {
            assertThat(task(1).call().durationMs()).isNotNegative();
        }
    }

    @Nested
    @DisplayName("failure is a result, not an exception")
    class FailuresAreResults {

        @Test
        @DisplayName("a search against a closed graph fails the task without throwing")
        void closedGraphIsReportedNotThrown() throws Exception {
            // One unusable segment must not take down the fan-out across every other segment.
            graph.insert(vector(1f));
            SegmentQueryTask task = task(5);
            graph.close();

            SegmentQueryTask.SegmentQueryResult result = task.call();

            assertThat(result.success()).isFalse();
            assertThat(result.hasError()).isTrue();
            assertThat(result.results())
                .as("a failed segment contributes nothing rather than partial nonsense")
                .isEmpty();
            assertThat(result.segmentId()).isEqualTo(SEGMENT_ID);
        }

        @Test
        @DisplayName("a task interrupted before it starts reports the interruption")
        void interruptionBeforeStartIsReported() throws Exception {
            // The dispatcher cancels tasks on timeout; a cancelled task should stop rather than
            // search a segment nobody is waiting for.
            SegmentQueryTask task = task(5);

            Thread.currentThread().interrupt();
            try {
                SegmentQueryTask.SegmentQueryResult result = task.call();

                assertThat(result.success()).isFalse();
                assertThat(result.errorMessage()).isEqualTo("Interrupted");
                assertThat(Thread.currentThread().isInterrupted())
                    .as("the interrupt is restored for whoever is unwinding the fan-out")
                    .isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Nested
    @DisplayName("the result value")
    class ResultValue {

        @Test
        @DisplayName("a result copies its list, so the caller cannot alter it afterwards")
        void resultsAreDefensivelyCopied() {
            List<HnswSearchResult> source = new ArrayList<>();
            source.add(new HnswSearchResult(1L, 0.5f));

            SegmentQueryTask.SegmentQueryResult result =
                new SegmentQueryTask.SegmentQueryResult(1L, source, 0, 0, true, null);
            source.add(new HnswSearchResult(2L, 0.6f));

            assertThat(result.results()).hasSize(1);
            assertThat(result.resultCount()).isEqualTo(1);
            assertThatThrownBy(() -> result.results().add(new HnswSearchResult(3L, 0.7f)))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a null result list becomes an empty one rather than a null field")
        void nullListsBecomeEmpty() {
            SegmentQueryTask.SegmentQueryResult result =
                new SegmentQueryTask.SegmentQueryResult(1L, null, 0, 0, true, null);

            assertThat(result.results()).isEmpty();
            assertThat(result.resultCount()).isZero();
            assertThat(result.payloads()).isEmpty();
            assertThat(result.payload(1L)).isNull();
        }

        @Test
        @DisplayName("tail-scan payloads travel with the results that carry them")
        void payloadsAreCarried() {
            byte[] text = "chunk text".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            SegmentQueryTask.SegmentQueryResult result =
                new SegmentQueryTask.SegmentQueryResult(1L,
                    List.of(new HnswSearchResult(7L, 0.1f)), 3, 1, true, null,
                    Map.of(7L, text));

            assertThat(result.payload(7L)).isEqualTo(text);
            assertThat(result.payload(8L))
                .as("a graph hit carries no payload; its text is resolved later")
                .isNull();
            assertThat(result.tailScanEntries())
                .as("the work performed, not the number of results kept")
                .isEqualTo(3);
        }

        @Test
        @DisplayName("an error result reports its message and counts as a failure")
        void errorResultsReadAsFailures() {
            SegmentQueryTask.SegmentQueryResult result =
                new SegmentQueryTask.SegmentQueryResult(9L, List.of(), 0, 5, false, "boom");

            assertThat(result.success()).isFalse();
            assertThat(result.hasError()).isTrue();
            assertThat(result.errorMessage()).isEqualTo("boom");
            assertThat(result.durationMs()).isEqualTo(5);
            assertThat(result.toString())
                .contains("9").contains("success=false");
        }
    }

    @Nested
    @DisplayName("Completeness")
    class Completeness {

        @Test
        @DisplayName("a segment that answered in full says so")
        void completeByDefault() {
            SegmentQueryTask.SegmentQueryResult result = new SegmentQueryTask.SegmentQueryResult(
                1L, java.util.List.of(), 0L, 5L, true, null);

            assertThat(result.complete())
                .as("a result built without the flag is a whole answer, as every existing "
                    + "caller means it to be")
                .isTrue();
        }

        @Test
        @DisplayName("a segment whose tail scan stopped early is not a whole answer")
        void partialIsReported() {
            // The write-log tail scan stops at the backpressure limit or on cancellation. The
            // results it did gather are real and are kept, so the segment is not a failure -
            // a failed segment's results are discarded wholesale. But it searched part of the
            // segment, and reporting that as a complete answer tells a caller the whole corpus
            // was covered when it was not.
            SegmentQueryTask.SegmentQueryResult result = new SegmentQueryTask.SegmentQueryResult(
                1L, java.util.List.of(), 120L, 5L, true, null, java.util.Map.of(), false);

            assertThat(result.complete()).isFalse();
            assertThat(result.success())
                .as("still a success: its results are kept and merged")
                .isTrue();
        }
    }

    /**
     * A graph whose unrestricted search walks past one node, as an approximate beam can.
     */
    private static final class MissingNearestGraph extends HnswGraph {

        private long hidden = -1;

        MissingNearestGraph(Path path) throws IOException {
            super(path, HnswConfig.create(DIMENSION), CosineSimilarity.getInstance(), 1_000);
        }

        void hide(long nodeId) {
            hidden = nodeId;
        }

        @Override
        public List<HnswSearchResult> search(float[] query, int k) {
            return super.search(query, k + 1).stream()
                .filter(result -> result.nodeId() != hidden)
                .limit(k)
                .toList();
        }
    }

    @Nested
    @DisplayName("graph and tail over the same chunk")
    class TailSupersession {

        private static final String REWRITTEN = "src:doc:chunk:0";
        private static final String ERASED = "src:doc:chunk:1";
        private static final String UNTOUCHED = "src:doc:chunk:2";

        private void put(String chunkId, float seed) {
            segment.put(chunkId.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                com.eonmux.excalibur.core.codec.ChunkPayloadCodec.encode("text", vector(seed)));
        }

        /** Stands in for the integration worker: every entry so far is in the graph. */
        private void integrateEverythingWrittenSoFar(Map<String, Float> chunks) {
            chunks.forEach((chunkId, seed) ->
                graph.attributeChunk(graph.insert(vector(seed)), chunkId));
            graph.compareAndSetLastIntegratedOffset(segment.getWriteLogHead());
        }

        @Test
        @DisplayName("the tail's later value or deletion displaces the graph's node for a chunk")
        void theTailSupersedesTheGraph() throws Exception {
            try (com.eonmux.excalibur.store.WriteLog log =
                     new com.eonmux.excalibur.store.WriteLog(tempDir.resolve("segment.wal"))) {
                segment.attachWriteLog(log);
                put(REWRITTEN, 9f);
                put(ERASED, 1.1f);
                put(UNTOUCHED, 1.2f);
                integrateEverythingWrittenSoFar(
                    Map.of(REWRITTEN, 9f, ERASED, 1.1f, UNTOUCHED, 1.2f));

                put(REWRITTEN, 1.0f);   // now the query's own vector
                segment.delete(ERASED.getBytes(java.nio.charset.StandardCharsets.UTF_8));

                SegmentQueryTask.SegmentQueryResult result = task(10).call();

                assertThat(result.results()).extracting(HnswSearchResult::documentId)
                    .as("the erased chunk's node is not ranked, the rewritten one ranks once")
                    .containsExactly(REWRITTEN, UNTOUCHED);
                HnswSearchResult rewritten = result.results().get(0);
                assertThat(TailScanner.isTailScanResult(rewritten.nodeId()))
                    .as("ranked by the value storage holds now, not the graph's old node")
                    .isTrue();
            }
        }

        @Test
        @DisplayName("a chunk found by both the graph and the tail is ranked once")
        void aChunkInBothIsRankedOnce() throws Exception {
            try (com.eonmux.excalibur.store.WriteLog log =
                     new com.eonmux.excalibur.store.WriteLog(tempDir.resolve("segment.wal"))) {
                segment.attachWriteLog(log);
                put(UNTOUCHED, 1.0f);
                // The worker absorbed the entry after the tail range was captured: the graph
                // holds its node, and the tail still reads the entry.
                graph.attributeChunk(graph.insert(vector(1.0f)), UNTOUCHED);

                assertThat(task(10).call().results()).extracting(HnswSearchResult::documentId)
                    .containsExactly(UNTOUCHED);
            }
        }
    }
}
