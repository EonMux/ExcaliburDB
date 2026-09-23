package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.ingest.pipeline.DocumentBatchBuffer;
import com.eonmux.excalibur.ingest.processor.TextChunk;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.QueryResultItem;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.SegmentCatalog.SegmentMetadata;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.store.WriteLog;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Phase 10 Feature 85: Live Indexing.
 *
 * <h2>Phase 10 Requirement</h2>
 * <blockquote>
 *   Live indexing: Issue queries concurrently with ongoing ingestion on separate threads.
 *   Verify: (a) no exceptions, (b) results progressively improve as indexing completes,
 *   (c) epoch isolation — no torn reads, (d) document atomicity — no partial documents in results.
 * </blockquote>
 *
 * <h2>Test Coverage</h2>
 * <p>These tests verify all four live indexing guarantees:</p>
 * <ul>
 *   <li><strong>(a) No Exceptions:</strong> All concurrent operations complete without throwing</li>
 *   <li><strong>(b) Progressive Improvement:</strong> Results increase (or stay same) as more data is indexed</li>
 *   <li><strong>(c) Epoch Isolation:</strong> No torn reads - queries see consistent segment snapshots</li>
 *   <li><strong>(d) Document Atomicity:</strong> No partial documents - all chunks of a document are visible together</li>
 * </ul>
 *
 * <h2>Domain Correctness Criteria</h2>
 * <ul>
 *   <li>Exception count must be exactly 0</li>
 *   <li>Result counts must be monotonically non-decreasing during ingestion</li>
 *   <li>All chunks of a document must have the same visibility epoch</li>
 *   <li>Torn read count must be exactly 0</li>
 *   <li>Partial document count must be exactly 0</li>
 *   <li>Coverage values must be in range [0.0, 1.0]</li>
 *   <li>Epoch values must monotonically increase</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
class LiveIndexingIntegrationTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private DistanceFunction distanceFunction;
    private int dimension;
    private Random random;

    // Infrastructure
    private TestStorageEngine storageEngine;
    private TestVisibilityTracker visibilityTracker;
    private WriteLog writeLog;
    private AtomicDocumentWriter documentWriter;
    private DocumentRegistry documentRegistry;

    @BeforeEach
    void setUp() throws IOException {
        arena = Arena.ofShared();
        distanceFunction = CosineSimilarity.getInstance();
        dimension = 128;
        random = new Random(42);

        // Create storage infrastructure
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        storageEngine = new TestStorageEngine(dataDir, arena);

        // Create visibility trackers
        visibilityTracker = new TestVisibilityTracker();

        // Create write log
        Path logPath = tempDir.resolve("live-indexing.log");
        writeLog = new WriteLog(logPath);

        // Create ingestion infrastructure
        documentWriter = new AtomicDocumentWriter(writeLog);
        documentRegistry = new DocumentRegistry();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (writeLog != null) {
            writeLog.close();
        }
        if (storageEngine != null) {
            storageEngine.close();
        }
        if (arena != null) {
            arena.close();
        }
    }

    // =====================================================================================
    // FEATURE 85: LIVE INDEXING INTEGRATION TEST
    // =====================================================================================

    /**
     * Test: Full live indexing verification - all four requirements.
     *
     * <p>This is the PRIMARY Feature 85 test that verifies all four Phase 10 requirements:</p>
     * <ul>
     *   <li>(a) no exceptions during concurrent ingestion and querying</li>
     *   <li>(b) results progressively improve as indexing completes</li>
     *   <li>(c) epoch isolation - no torn reads</li>
     *   <li>(d) document atomicity - no partial documents in results</li>
     * </ul>
     *
     * <p><strong>Domain Correctness:</strong></p>
     * <ul>
     *   <li>exceptionCount must equal 0</li>
     *   <li>tornReadCount must equal 0</li>
     *   <li>partialDocumentCount must equal 0</li>
     *   <li>Result counts must be non-decreasing across checkpoints</li>
     * </ul>
     */
    @Test
    @DisplayName("Feature 85: Live indexing - all four requirements verified")
    void feature85_LiveIndexing_AllRequirementsVerified() throws Exception {
        // Create HNSW graph
        HnswConfig config = HnswConfig.create(dimension);
        Path graphPath = tempDir.resolve("live-indexing.graph");
        HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 50000);

        // CRITICAL: Create and register a segment
        Segment segment = storageEngine.createSegment(LifecycleState.INDEXING);
        long segmentId = segment.segmentId();
        visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());
        visibilityTracker.advanceEpoch();

        // The document visibility system under test. Requirements (c) and (d) are
        // properties of THIS object, so it is what the readers interrogate and what the
        // pipeline filters with - a map the writer filled would agree with itself no
        // matter how the tracker behaved.
        DocumentVisibilityTracker atomicityTracker = new DocumentVisibilityTracker();

        // Query pipeline, wired with the document atomicity filter. The four-argument
        // constructor leaves the filter disabled, which would let requirement (d) pass
        // with the component it names never executing.
        QueryPipeline.HnswGraphProvider graphProvider = segId -> {
            if (segId == segmentId) return graph;
            return null;
        };
        QueryPipeline pipeline = new QueryPipeline(
            storageEngine,
            visibilityTracker,
            new DocumentVisibilityTrackerAdapter(atomicityTracker),
            graphProvider,
            distanceFunction,
            null,
            QueryPipeline.DEFAULT_COVERAGE_THRESHOLD);
        assertThat(pipeline.getVisibilityChecker())
            .as("The document atomicity filter must be configured for requirement (d)")
            .isNotNull();

        // Test parameters (optimized for faster execution)
        int numWriterThreads = 2;
        int numReaderThreads = 4;
        int documentsPerWriter = 25;
        int chunksPerDocument = 5;

        // Tracking for all four requirements
        // (a) No exceptions
        AtomicInteger exceptionCount = new AtomicInteger(0);
        List<String> exceptionMessages = Collections.synchronizedList(new ArrayList<>());

        // (b) Progressive improvement. Each reader's own queries run one after another, so its
        // first and last result counts are ordered in time; counts from different readers are
        // not, whatever order they reach a shared list in.
        AtomicInteger totalQueries = new AtomicInteger(0);
        AtomicInteger queriesWithResults = new AtomicInteger(0);
        List<int[]> firstAndLastResultCounts = Collections.synchronizedList(new ArrayList<>());

        // (c) Epoch isolation - no torn reads. The chunk ids of each published document are
        // the writer's own knowledge; whether they are VISIBLE is the tracker's answer.
        ConcurrentHashMap<String, List<String>> documentChunkIds = new ConcurrentHashMap<>();
        AtomicInteger tornReadCount = new AtomicInteger(0);
        AtomicInteger totalChunkChecks = new AtomicInteger(0);

        // (d) Document atomicity - no partial documents
        AtomicInteger partialDocumentCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(numWriterThreads + numReaderThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(numWriterThreads);
        AtomicBoolean writersFinished = new AtomicBoolean(false);

        // Raised by the readers the first time they check a published document's chunks and
        // the first time a query returns results. Each writer holds after its first document
        // until both have happened, so the readers make those observations while ingestion is
        // still under way, not only when the scheduler happens to run them in time.
        CountDownLatch readersCheckedChunks = new CountDownLatch(1);
        CountDownLatch readersSawResults = new CountDownLatch(1);

        // ==================== WRITER TASKS ====================
        Runnable writerTask = () -> {
            try {
                startLatch.await();
                int threadId = (int) Thread.currentThread().threadId();

                for (int d = 0; d < documentsPerWriter; d++) {
                    String documentId = "doc-live-" + threadId + "-" + d;

                    // Create document with multiple chunks
                    List<TextChunk> chunks = createChunks(documentId, chunksPerDocument);
                    DocumentBatchBuffer batch = documentWriter.write(documentId, chunks);

                    // Insert vector into HNSW graph
                    float[] vector = createRandomVector(dimension);
                    long nodeId = graph.insert(vector);
                    graph.registerDocumentId(nodeId, documentId);
                    graph.setLastIntegratedOffset(writeLog.writeOffset());

                    long commitEpoch = visibilityTracker.advanceEpoch();

                    // Announce the chunk ids first, then publish: a reader that sees the
                    // document as visible must always be able to enumerate its chunks.
                    documentChunkIds.put(documentId, List.copyOf(batch.getChunkIds()));
                    atomicityTracker.registerDocument(documentId, batch.getChunkIds(), commitEpoch);

                    if (d == 0) {
                        awaitReaders(readersCheckedChunks, "check a published document's chunks");
                        awaitReaders(readersSawResults, "get results from a query");
                    }

                    Thread.sleep(2); // Small delay to allow readers
                }
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
                exceptionMessages.add("Writer: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } finally {
                writersDone.countDown();
            }
        };

        // ==================== READER TASKS ====================
        Runnable readerTask = () -> {
            int firstResultCount = -1;
            int lastResultCount = -1;
            try {
                startLatch.await();

                // One more pass after the writers finish, so every reader queries the whole corpus
                boolean finalPass;
                do {
                    finalPass = writersFinished.get();
                    try {
                        long queryEpoch = visibilityTracker.captureEpoch();

                        QueryRequest request = QueryRequest.builder()
                            .queryText("live indexing query")
                            .queryVector(createRandomVector(dimension))
                            .topK(50)
                            .timeoutMs(5000)
                            .includePartialSegments(true)
                            .enableLlmScoring(false)
                            .correlationId(UUID.randomUUID().toString())
                            .build();

                        CancellationToken token = new CancellationToken();
                        QueryResponse response = pipeline.executeSync(request, token);

                        totalQueries.incrementAndGet();
                        int resultCount = response.results().size();

                        if (resultCount > 0) {
                            queriesWithResults.incrementAndGet();
                            readersSawResults.countDown();
                            if (firstResultCount < 0) {
                                firstResultCount = resultCount;
                            }
                            lastResultCount = resultCount;
                        }

                        // ==================== (c) EPOCH ISOLATION - TORN READ CHECK ====================
                        // Ask the tracker which documents it considers visible, then ask it
                        // about each of their chunks. A tracker that published chunks one at
                        // a time would report a document visible with some chunks invisible.
                        for (String documentId : atomicityTracker.getVisibleDocuments(queryEpoch)) {
                            List<String> chunkIds = documentChunkIds.get(documentId);
                            if (chunkIds == null) continue;

                            totalChunkChecks.incrementAndGet();

                            int visibleChunks = 0;
                            for (String chunkId : chunkIds) {
                                if (atomicityTracker.isChunkVisible(chunkId, queryEpoch)) {
                                    visibleChunks++;
                                }
                            }

                            // TORN READ DETECTION: a visible document must have every chunk visible
                            if (visibleChunks != chunkIds.size()) {
                                tornReadCount.incrementAndGet();
                            }
                            readersCheckedChunks.countDown();
                        }

                        // ==================== (d) DOCUMENT ATOMICITY CHECK ====================
                        // Every result that survived the atomicity filter must belong to a
                        // document the tracker published whole.
                        for (QueryResultItem result : response.results()) {
                            String docIdStr = result.documentIdStr();
                            if (docIdStr == null) continue;

                            long commitEpoch = atomicityTracker.getCommitEpoch(docIdStr);
                            if (commitEpoch < 0) continue;

                            List<String> chunkIds = documentChunkIds.get(docIdStr);
                            if (chunkIds == null) continue;

                            int visibleAtCommit = 0;
                            for (String chunkId : chunkIds) {
                                if (atomicityTracker.isChunkVisible(chunkId, commitEpoch)) {
                                    visibleAtCommit++;
                                }
                            }

                            // PARTIAL DOCUMENT DETECTION: at the document's own commit epoch
                            // either all of its chunks are visible or none of them are
                            if (visibleAtCommit != chunkIds.size()) {
                                partialDocumentCount.incrementAndGet();
                            }
                        }

                        // Verify coverage is in valid range
                        IndexCoverage coverage = response.coverage();
                        if (coverage != null) {
                            double cov = coverage.estimatedCorpusCoverage();
                            if (cov < 0.0 || cov > 1.0) {
                                exceptionCount.incrementAndGet();
                                exceptionMessages.add("Invalid coverage: " + cov);
                            }
                        }

                        Thread.sleep(5);
                    } catch (Exception e) {
                        exceptionCount.incrementAndGet();
                        exceptionMessages.add("Reader: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    }
                } while (!finalPass);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (firstResultCount >= 0) {
                    firstAndLastResultCounts.add(new int[] {firstResultCount, lastResultCount});
                }
            }
        };

        // Start all threads
        for (int i = 0; i < numWriterThreads; i++) {
            executor.submit(writerTask);
        }
        for (int i = 0; i < numReaderThreads; i++) {
            executor.submit(readerTask);
        }

        startLatch.countDown();

        boolean writersCompleted = writersDone.await(120, TimeUnit.SECONDS);
        writersFinished.set(true);

        // Each reader finishes the pass it is in and makes one more over the whole corpus
        executor.shutdown();
        boolean readersCompleted = executor.awaitTermination(60, TimeUnit.SECONDS);

        // ==================== VERIFICATION ====================

        System.out.println("\n=== FEATURE 85: LIVE INDEXING TEST RESULTS ===\n");

        System.out.println("(a) NO EXCEPTIONS:");
        System.out.println("    Exceptions: " + exceptionCount.get());
        if (!exceptionMessages.isEmpty()) {
            System.out.println("    Exception messages:");
            for (String msg : exceptionMessages) {
                System.out.println("      - " + msg);
            }
        }

        System.out.println("\n(b) PROGRESSIVE IMPROVEMENT:");
        System.out.println("    Total queries: " + totalQueries.get());
        System.out.println("    Queries with results: " + queriesWithResults.get());
        synchronized (firstAndLastResultCounts) {
            for (int[] counts : firstAndLastResultCounts) {
                System.out.println("    Reader result counts: first " + counts[0] + ", last " + counts[1]);
            }
        }

        System.out.println("\n(c) EPOCH ISOLATION (No Torn Reads):");
        System.out.println("    Torn reads detected: " + tornReadCount.get());
        System.out.println("    Total chunk checks: " + totalChunkChecks.get());
        System.out.println("    Documents tracked: " + atomicityTracker.getDocumentCount());

        System.out.println("\n(d) DOCUMENT ATOMICITY (No Partial Documents):");
        System.out.println("    Partial documents detected: " + partialDocumentCount.get());
        System.out.println("    Document visibility entries: " + atomicityTracker.getDocumentCount());

        System.out.println("\n=== END OF RESULTS ===\n");

        // ==================== ASSERTIONS ====================

        assertThat(writersCompleted).as("The writers must finish within the timeout").isTrue();
        assertThat(readersCompleted).as("The readers must finish within the timeout").isTrue();

        // (a) No exceptions
        assertThat(exceptionCount.get())
            .as("(a) No exceptions: All concurrent operations must complete without throwing: %s",
                exceptionMessages)
            .isEqualTo(0);

        // (b) Progressive improvement - verify queries were executed
        assertThat(totalQueries.get())
            .as("(b) Progressive improvement: Queries should have been executed during ingestion")
            .isGreaterThan(0);

        // (b) Progressive improvement - verify some queries returned results
        assertThat(queriesWithResults.get())
            .as("(b) Progressive improvement: Some queries should have returned results")
            .isGreaterThan(0);

        // (b) Progressive improvement - each reader's last query, made over the whole corpus,
        // returns at least as much as its first did
        assertThat(firstAndLastResultCounts)
            .as("(b) Progressive improvement: every reader must have had results")
            .hasSize(numReaderThreads);
        for (int[] counts : firstAndLastResultCounts) {
            assertThat(counts[1])
                .as("(b) Progressive improvement: Final result count (%d) should be >= initial count (%d)",
                    counts[1], counts[0])
                .isGreaterThanOrEqualTo(counts[0]);
        }

        // (c) Epoch isolation - no torn reads
        assertThat(tornReadCount.get())
            .as("(c) Epoch isolation: No torn reads should occur - all chunks must be visible at same epoch")
            .isEqualTo(0);

        // (d) Document atomicity - no partial documents
        assertThat(partialDocumentCount.get())
            .as("(d) Document atomicity: No partial documents should be visible - all chunks of a document must be visible together")
            .isEqualTo(0);

        // Verify documents were actually ingested
        assertThat(atomicityTracker.getDocumentCount())
            .as("Documents should have been ingested during the test")
            .isEqualTo(numWriterThreads * documentsPerWriter);

        // Verify the epoch isolation check was not vacuous
        assertThat(totalChunkChecks.get())
            .as("(c) Epoch isolation: readers must have inspected published documents")
            .isGreaterThan(0);

        graph.close();
    }

    // =====================================================================================
    // NESTED TEST CLASSES FOR INDIVIDUAL REQUIREMENTS
    // =====================================================================================

    @Nested
    @DisplayName("(a) No Exceptions")
    class NoExceptions {

        /**
         * Test: All concurrent operations complete without throwing exceptions.
         *
         * <p><strong>Domain Correctness:</strong> Exception count must be exactly 0.</p>
         */
        @Test
        @DisplayName("concurrent ingestion and querying produces no exceptions")
        void concurrentIngestionAndQueryingProducesNoExceptions() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("no-exceptions.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 20000);

            // Create and register segment
            Segment segment = storageEngine.createSegment(LifecycleState.INDEXING);
            long segmentId = segment.segmentId();
            visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());
            visibilityTracker.advanceEpoch();

            // Query pipeline
            QueryPipeline.HnswGraphProvider graphProvider = segId -> {
                if (segId == segmentId) return graph;
                return null;
            };
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            int numThreads = 6;
            int operationsPerThread = 50;
            AtomicInteger exceptionCount = new AtomicInteger(0);
            List<String> exceptions = Collections.synchronizedList(new ArrayList<>());

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);

            Runnable mixedTask = () -> {
                try {
                    startLatch.await();
                    int threadId = (int) Thread.currentThread().threadId();

                    for (int i = 0; i < operationsPerThread; i++) {
                        try {
                            if (i % 3 == 0) {
                                // Ingest operation
                                String documentId = "doc-exception-" + threadId + "-" + i;
                                List<TextChunk> chunks = createChunks(documentId, 3);
                                documentWriter.write(documentId, chunks);

                                float[] vector = createRandomVector(dimension);
                                graph.insert(vector);
                                graph.registerDocumentId(graph.nodeCount() - 1, documentId);
                                graph.setLastIntegratedOffset(writeLog.writeOffset());

                                visibilityTracker.advanceEpoch();
                            } else {
                                // Query operation
                                QueryRequest request = QueryRequest.builder()
                                    .queryText("exception test query")
                                    .queryVector(createRandomVector(dimension))
                                    .topK(20)
                                    .timeoutMs(3000)
                                    .includePartialSegments(true)
                                    .enableLlmScoring(false)
                                    .correlationId(UUID.randomUUID().toString())
                                    .build();

                                pipeline.executeSync(request, new CancellationToken());
                            }
                        } catch (Exception e) {
                            exceptionCount.incrementAndGet();
                            exceptions.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                        Thread.yield();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };

            for (int i = 0; i < numThreads; i++) {
                executor.submit(mixedTask);
            }

            startLatch.countDown();

            executor.shutdown();
            boolean completed = executor.awaitTermination(120, TimeUnit.SECONDS);

            System.out.println("Exception test: " + exceptionCount.get() + " exceptions out of " +
                (numThreads * operationsPerThread) + " operations");
            if (!exceptions.isEmpty()) {
                System.out.println("Exceptions: " + exceptions);
            }

            assertThat(completed).as("Every operation must finish within the timeout").isTrue();
            assertThat(exceptionCount.get())
                .as("No exceptions should occur during concurrent operations. Exceptions: " + exceptions)
                .isEqualTo(0);

            graph.close();
        }
    }

    @Nested
    @DisplayName("(b) Progressive Improvement")
    class ProgressiveImprovement {

        /**
         * Test: Results progressively improve as indexing completes.
         *
         * <p><strong>Domain Correctness:</strong> Result counts should be non-decreasing
         * as more documents are ingested.</p>
         */
        @Test
        @DisplayName("results increase as more documents are indexed")
        void resultsIncreaseAsMoreDocumentsAreIndexed() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("progressive.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 20000);

            // Create and register segment
            Segment segment = storageEngine.createSegment(LifecycleState.INDEXING);
            long segmentId = segment.segmentId();
            visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());
            visibilityTracker.advanceEpoch();

            // Query pipeline
            QueryPipeline.HnswGraphProvider graphProvider = segId -> {
                if (segId == segmentId) return graph;
                return null;
            };
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            int[] checkpoints = {0, 20, 40, 60, 80, 100};
            int[] resultCounts = new int[checkpoints.length];
            float[] queryVector = createRandomVector(dimension);

            for (int c = 0; c < checkpoints.length; c++) {
                int target = checkpoints[c];
                int current = c > 0 ? checkpoints[c - 1] : 0;

                // Ingest more documents
                for (int d = current; d < target; d++) {
                    String documentId = "doc-progressive-" + d;
                    List<TextChunk> chunks = createChunks(documentId, 3);
                    documentWriter.write(documentId, chunks);

                    float[] vector = createRandomVector(dimension);
                    long nodeId = graph.insert(vector);
                    graph.registerDocumentId(nodeId, documentId);
                    graph.setLastIntegratedOffset(writeLog.writeOffset());

                    visibilityTracker.advanceEpoch();
                }

                // Query at this checkpoint
                QueryRequest request = QueryRequest.builder()
                    .queryText("progressive query")
                    .queryVector(queryVector)
                    .topK(100)
                    .timeoutMs(5000)
                    .includePartialSegments(true)
                    .enableLlmScoring(false)
                    .correlationId(UUID.randomUUID().toString())
                    .build();

                QueryResponse response = pipeline.executeSync(request, new CancellationToken());
                resultCounts[c] = response.results().size();

                System.out.println("Checkpoint " + c + " (" + target + " docs): " +
                    resultCounts[c] + " results");
            }

            // Verify non-decreasing result counts
            for (int c = 1; c < resultCounts.length; c++) {
                assertThat(resultCounts[c])
                    .as("Result count at checkpoint %d (%d docs) should be >= checkpoint %d (%d docs)",
                        c, checkpoints[c], c - 1, checkpoints[c - 1])
                    .isGreaterThanOrEqualTo(resultCounts[c - 1]);
            }

            // Verify final checkpoint has results
            assertThat(resultCounts[resultCounts.length - 1])
                .as("Final checkpoint should have results after ingesting %d documents",
                    checkpoints[checkpoints.length - 1])
                .isGreaterThan(0);

            graph.close();
        }
    }

    @Nested
    @DisplayName("(c) Epoch Isolation")
    class EpochIsolation {

        /**
         * Test: No torn reads - all chunks of a document are visible at the same epoch.
         *
         * <p><strong>Domain Correctness:</strong> Torn read count must be exactly 0.</p>
         */
        @Test
        @DisplayName("no torn reads - all chunks visible at same epoch")
        void noTornReads_AllChunksVisibleAtSameEpoch() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("torn-reads.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 20000);

            // Create and register segment
            Segment segment = storageEngine.createSegment(LifecycleState.INDEXING);
            long segmentId = segment.segmentId();
            visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());
            visibilityTracker.advanceEpoch();

            int numDocuments = 30;
            int chunksPerDocument = 5;

            // The tracker is the system under test; the readers ask it, not a map of their own
            DocumentVisibilityTracker atomicityTracker = new DocumentVisibilityTracker();
            ConcurrentHashMap<String, List<String>> documentChunkIds = new ConcurrentHashMap<>();
            AtomicInteger tornReadCount = new AtomicInteger(0);
            AtomicInteger totalChecks = new AtomicInteger(0);
            List<Throwable> workerFailures = Collections.synchronizedList(new ArrayList<>());

            ExecutorService executor = Executors.newFixedThreadPool(5);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch writerDone = new CountDownLatch(1);
            AtomicBoolean writersFinished = new AtomicBoolean(false);
            // The writer holds after its first document until a reader has checked one, so
            // checks happen while ingestion is still under way
            CountDownLatch readersChecked = new CountDownLatch(1);

            // Writer
            Runnable writer = () -> {
                try {
                    startLatch.await();

                    for (int d = 0; d < numDocuments; d++) {
                        String documentId = "doc-torn-" + d;
                        List<TextChunk> chunks = createChunks(documentId, chunksPerDocument);
                        DocumentBatchBuffer batch = documentWriter.write(documentId, chunks);

                        float[] vector = createRandomVector(dimension);
                        graph.insert(vector);
                        graph.setLastIntegratedOffset(writeLog.writeOffset());

                        long commitEpoch = visibilityTracker.advanceEpoch();

                        documentChunkIds.put(documentId, List.copyOf(batch.getChunkIds()));
                        atomicityTracker.registerDocument(documentId, batch.getChunkIds(), commitEpoch);

                        if (d == 0) {
                            awaitReaders(readersChecked, "check a published document's chunks");
                        }

                        Thread.sleep(3);
                    }
                } catch (Exception e) {
                    workerFailures.add(e);
                } finally {
                    // Must happen even on failure, or the readers spin until the timeout
                    writersFinished.set(true);
                    writerDone.countDown();
                }
            };

            // Reader
            Runnable reader = () -> {
                try {
                    startLatch.await();

                    boolean finalPass;
                    do {
                        finalPass = writersFinished.get();
                        long queryEpoch = visibilityTracker.captureEpoch();

                        for (String documentId : atomicityTracker.getVisibleDocuments(queryEpoch)) {
                            List<String> chunkIds = documentChunkIds.get(documentId);
                            if (chunkIds == null) continue;

                            totalChecks.incrementAndGet();

                            int visibleChunks = 0;
                            for (String chunkId : chunkIds) {
                                if (atomicityTracker.isChunkVisible(chunkId, queryEpoch)) {
                                    visibleChunks++;
                                }
                            }

                            // TORN READ: the document is visible but not all of its chunks are
                            if (visibleChunks != chunkIds.size()) {
                                tornReadCount.incrementAndGet();
                            }
                            readersChecked.countDown();
                        }

                        Thread.sleep(2);
                    } while (!finalPass);
                } catch (Exception e) {
                    workerFailures.add(e);
                }
            };

            executor.submit(writer);
            for (int i = 0; i < 4; i++) {
                executor.submit(reader);
            }

            startLatch.countDown();
            boolean writerCompleted = writerDone.await(60, TimeUnit.SECONDS);

            // Each reader finishes the pass it is in and makes one more after the last document
            executor.shutdown();
            boolean readersCompleted = executor.awaitTermination(60, TimeUnit.SECONDS);

            System.out.println("Torn read test: " + tornReadCount.get() + " torn reads out of " +
                totalChecks.get() + " checks");

            assertThat(writerCompleted).as("The writer must finish within the timeout").isTrue();
            assertThat(readersCompleted).as("The readers must finish within the timeout").isTrue();
            assertThat(workerFailures).as("No worker may fail: " + workerFailures).isEmpty();

            assertThat(tornReadCount.get())
                .as("No torn reads should occur - all chunks must be visible at the same epoch")
                .isEqualTo(0);

            assertThat(totalChecks.get())
                .as("Should have performed visibility checks")
                .isGreaterThan(0);
            assertThat(atomicityTracker.getDocumentCount()).isEqualTo(numDocuments);

            graph.close();
        }
    }

    @Nested
    @DisplayName("(d) Document Atomicity")
    class DocumentAtomicity {

        /**
         * Test: No partial documents - all chunks of a document are visible together.
         *
         * <p><strong>Domain Correctness:</strong> Partial document count must be exactly 0.</p>
         */
        @Test
        @DisplayName("no partial documents - all chunks visible together")
        void noPartialDocuments_AllChunksVisibleTogether() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("atomic.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 20000);

            // Create and register segment
            Segment segment = storageEngine.createSegment(LifecycleState.INDEXING);
            long segmentId = segment.segmentId();
            visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());
            visibilityTracker.advanceEpoch();

            // The document atomicity filter is the component this test is named for, so the
            // pipeline is built with it. The four-argument constructor leaves it disabled.
            DocumentVisibilityTracker atomicityTracker = new DocumentVisibilityTracker();

            QueryPipeline.HnswGraphProvider graphProvider = segId -> {
                if (segId == segmentId) return graph;
                return null;
            };
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                new DocumentVisibilityTrackerAdapter(atomicityTracker),
                graphProvider,
                distanceFunction,
                null,
                QueryPipeline.DEFAULT_COVERAGE_THRESHOLD);
            assertThat(pipeline.getVisibilityChecker())
                .as("The document atomicity filter must be configured")
                .isNotNull();

            int numDocuments = 25;
            int chunksPerDocument = 6;

            ConcurrentHashMap<String, List<String>> documentChunkIds = new ConcurrentHashMap<>();
            AtomicInteger partialDocumentCount = new AtomicInteger(0);
            AtomicInteger totalQueries = new AtomicInteger(0);
            AtomicInteger resultsInspected = new AtomicInteger(0);
            AtomicInteger atomicityChecks = new AtomicInteger(0);
            List<Throwable> workerFailures = Collections.synchronizedList(new ArrayList<>());

            ExecutorService executor = Executors.newFixedThreadPool(4);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch writerDone = new CountDownLatch(1);
            AtomicBoolean writersFinished = new AtomicBoolean(false);
            // The writer holds after its first document until a reader has checked a query
            // result for atomicity, so results are checked while ingestion is still under way
            CountDownLatch readersChecked = new CountDownLatch(1);

            // Writer
            Runnable writer = () -> {
                try {
                    startLatch.await();

                    for (int d = 0; d < numDocuments; d++) {
                        String documentId = "doc-atomic-" + d;

                        // Write the document's chunks the way ingestion does: the batch that
                        // comes back carries the chunk ids and the write-log offsets that
                        // atomic publication is defined over.
                        List<TextChunk> chunks = createChunks(documentId, chunksPerDocument);
                        DocumentBatchBuffer batch = documentWriter.write(documentId, chunks);

                        float[] vector = createRandomVector(dimension);
                        long nodeId = graph.insert(vector);
                        graph.registerDocumentId(nodeId, documentId);
                        graph.setLastIntegratedOffset(writeLog.writeOffset());

                        long commitEpoch = visibilityTracker.advanceEpoch();

                        documentChunkIds.put(documentId, List.copyOf(batch.getChunkIds()));
                        atomicityTracker.registerDocument(documentId, batch.getChunkIds(), commitEpoch);

                        if (d == 0) {
                            awaitReaders(readersChecked, "check a query result for atomicity");
                        }

                        Thread.sleep(5);
                    }
                } catch (Exception e) {
                    workerFailures.add(e);
                } finally {
                    writersFinished.set(true);
                    writerDone.countDown();
                }
            };

            // Reader
            Runnable reader = () -> {
                try {
                    startLatch.await();

                    boolean finalPass;
                    do {
                        finalPass = writersFinished.get();
                        long queryEpoch = visibilityTracker.captureEpoch();

                        QueryRequest request = QueryRequest.builder()
                            .queryText("atomicity query")
                            .queryVector(createRandomVector(dimension))
                            .topK(50)
                            .timeoutMs(5000)
                            .includePartialSegments(true)
                            .enableLlmScoring(false)
                            .correlationId(UUID.randomUUID().toString())
                            .build();

                        QueryResponse response = pipeline.executeSync(request, new CancellationToken());
                        totalQueries.incrementAndGet();

                        // Every result that survived the atomicity filter must belong to a
                        // document the tracker itself reports as whole at the query epoch.
                        for (QueryResultItem result : response.results()) {
                            String docIdStr = result.documentIdStr();
                            if (docIdStr == null) continue;

                            List<String> chunkIds = documentChunkIds.get(docIdStr);
                            if (chunkIds == null) continue;

                            resultsInspected.incrementAndGet();

                            if (!atomicityTracker.isDocumentFullyVisible(docIdStr, queryEpoch)) {
                                // Committed after this query's epoch: not this test's subject
                                continue;
                            }

                            int visibleChunks = 0;
                            for (String chunkId : chunkIds) {
                                if (atomicityTracker.isChunkVisible(chunkId, queryEpoch)) {
                                    visibleChunks++;
                                }
                            }

                            if (visibleChunks != chunkIds.size()) {
                                partialDocumentCount.incrementAndGet();
                            }
                            atomicityChecks.incrementAndGet();
                            readersChecked.countDown();
                        }

                        Thread.sleep(10);
                    } while (!finalPass);
                } catch (Exception e) {
                    workerFailures.add(e);
                }
            };

            executor.submit(writer);
            for (int i = 0; i < 3; i++) {
                executor.submit(reader);
            }

            startLatch.countDown();
            boolean writerCompleted = writerDone.await(60, TimeUnit.SECONDS);

            // Each reader finishes the pass it is in and makes one more after the last document
            executor.shutdown();
            boolean readersCompleted = executor.awaitTermination(60, TimeUnit.SECONDS);

            System.out.println("Document atomicity test: " + partialDocumentCount.get() +
                " partial documents out of " + totalQueries.get() + " queries (" +
                resultsInspected.get() + " results inspected, " + atomicityChecks.get() +
                " checked for atomicity)");

            assertThat(writerCompleted).as("The writer must finish within the timeout").isTrue();
            assertThat(readersCompleted).as("The readers must finish within the timeout").isTrue();
            assertThat(workerFailures).as("No worker may fail: " + workerFailures).isEmpty();

            assertThat(partialDocumentCount.get())
                .as("No partial documents should be visible - all chunks of a document must be visible together")
                .isEqualTo(0);

            assertThat(totalQueries.get())
                .as("Should have executed queries")
                .isGreaterThan(0);

            // The check above is only meaningful if published documents actually reached the
            // query results
            assertThat(resultsInspected.get())
                .as("Queries must have returned published documents for atomicity to be checked")
                .isGreaterThan(0);
            assertThat(atomicityChecks.get())
                .as("Results committed by their query's epoch must have been checked for atomicity")
                .isGreaterThan(0);

            graph.close();
        }
    }

    // =====================================================================================
    // HELPER METHODS
    // =====================================================================================

    /**
     * How long a writer holds for the readers' first observation of a published document.
     * Generous, because it is only ever reached when that observation never comes.
     */
    private static final long READER_OBSERVATION_DEADLINE_SECONDS = 30;

    /**
     * Holds a writer until the readers have made the observation {@code observed} stands for.
     *
     * <p>The tests prove their guarantees by what concurrent readers observe while documents
     * are being published. Left to scheduling, a writer can publish everything before a reader
     * has looked, and the proof is empty; holding the writer until a reader has seen a
     * published document makes the observation happen mid-ingestion every run.</p>
     *
     * @param observed Counted down by a reader when it makes the observation
     * @param what What the readers were to do, for the failure message
     * @throws IllegalStateException if the readers do not make it within the deadline
     */
    private static void awaitReaders(CountDownLatch observed, String what)
            throws InterruptedException {
        if (!observed.await(READER_OBSERVATION_DEADLINE_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("no reader managed to " + what + " within "
                + READER_OBSERVATION_DEADLINE_SECONDS + "s of the first document being published");
        }
    }

    private float[] createRandomVector(int dimension) {
        float[] vector = new float[dimension];
        float sum = 0;
        for (int i = 0; i < dimension; i++) {
            vector[i] = random.nextFloat() * 2 - 1;
            sum += vector[i] * vector[i];
        }
        // Normalize
        float norm = (float) Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private List<TextChunk> createChunks(String documentId, int count) {
        List<TextChunk> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String text = "This is chunk " + i + " of document " + documentId +
                ". It contains enough text to be meaningful for testing purposes.";
            chunks.add(new TextChunk(
                documentId,
                text,
                text.length() / 4,
                i * 100,
                (i + 1) * 100,
                i
            ));
        }
        return chunks;
    }

    // =====================================================================================
    // TEST INFRASTRUCTURE
    // =====================================================================================

    private static class TestStorageEngine extends StorageEngine {
        private final TestSegmentCatalog catalog;
        private final Map<Long, Segment> segments = new ConcurrentHashMap<>();
        private final Path dataDir;
        private final Arena arena;
        private int segmentCounter = 0;

        TestStorageEngine(Path dataDir, Arena arena) throws IOException {
            super(dataDir, arena);
            this.dataDir = dataDir;
            this.arena = arena;
            this.catalog = new TestSegmentCatalog();
        }

        public Segment createSegment(LifecycleState lifecycleState) throws IOException {
            Path segmentPath = dataDir.resolve("segment-" + segmentCounter++ + ".seg");
            Segment segment = new Segment(segmentPath, 4 * 1024 * 1024, arena);
            long segmentId = segment.segmentId();
            catalog.addSegment(segmentId, lifecycleState);
            segments.put(segmentId, segment);
            return segment;
        }

        @Override
        public SegmentCatalog getCatalog() {
            return catalog;
        }

        @Override
        public Segment getSegment(long segmentId) {
            return segments.get(segmentId);
        }

        @Override
        public Map<Long, Segment> getSegments() {
            return Collections.unmodifiableMap(segments);
        }

        public void close() {
            for (Segment segment : segments.values()) {
                try {
                    segment.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }

    private static class TestSegmentCatalog extends SegmentCatalog {
        private final Map<Long, SegmentMetadata> segments = new ConcurrentHashMap<>();

        public SegmentMetadata addSegment(long segmentId, LifecycleState lifecycleState) {
            SegmentMetadata metadata = new SegmentMetadata(segmentId);
            if (lifecycleState != LifecycleState.BOOTSTRAPPING) {
                metadata.transitionToIndexing();
                if (lifecycleState == LifecycleState.WARMING
                    || lifecycleState == LifecycleState.ACTIVE
                    || lifecycleState == LifecycleState.COMPACTING) {
                    metadata.transitionToWarming();
                    if (lifecycleState == LifecycleState.ACTIVE || lifecycleState == LifecycleState.COMPACTING) {
                        metadata.transitionToActive();
                        if (lifecycleState == LifecycleState.COMPACTING) {
                            metadata.transitionToCompacting();
                        }
                    }
                }
            }
            segments.put(segmentId, metadata);
            return metadata;
        }

        @Override
        public SegmentMetadata get(long segmentId) {
            return segments.get(segmentId);
        }

        @Override
        public Map<Long, SegmentMetadata> getAll() {
            return Collections.unmodifiableMap(segments);
        }

        @Override
        public int size() {
            return segments.size();
        }
    }

    private static class TestVisibilityTracker extends VisibilityTracker {
        private final AtomicLong epoch = new AtomicLong(0);
        private final ConcurrentHashMap<Long, Long> creationEpochs = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Long> tombstoneEpochs = new ConcurrentHashMap<>();

        TestVisibilityTracker() {
            super(new TestSegmentCatalog());
        }

        @Override
        public long currentEpoch() {
            return epoch.get();
        }

        @Override
        public long captureEpoch() {
            return epoch.get();
        }

        @Override
        public long advanceEpoch() {
            return epoch.incrementAndGet();
        }

        @Override
        public void registerSegment(long segmentId, long creationEpoch) {
            creationEpochs.put(segmentId, creationEpoch);
            tombstoneEpochs.putIfAbsent(segmentId, Long.MAX_VALUE);
        }

        @Override
        public void markTombstoned(long segmentId, long tombEpoch) {
            tombstoneEpochs.put(segmentId, tombEpoch);
        }

        @Override
        public Set<Long> visibleSegments(long queryEpoch) {
            Set<Long> visible = new HashSet<>();
            for (Map.Entry<Long, Long> entry : creationEpochs.entrySet()) {
                long segmentId = entry.getKey();
                long creationEpoch = entry.getValue();
                long tombstoneEpoch = tombstoneEpochs.getOrDefault(segmentId, Long.MAX_VALUE);
                if (creationEpoch <= queryEpoch && queryEpoch < tombstoneEpoch) {
                    visible.add(segmentId);
                }
            }
            return Collections.unmodifiableSet(visible);
        }

        @Override
        public boolean isSegmentVisible(long segmentId, long queryEpoch) {
            Long creationEpoch = creationEpochs.get(segmentId);
            if (creationEpoch == null) return false;
            long tombstoneEpoch = tombstoneEpochs.getOrDefault(segmentId, Long.MAX_VALUE);
            return creationEpoch <= queryEpoch && queryEpoch < tombstoneEpoch;
        }

        @Override
        public long getCreationEpoch(long segmentId) {
            return creationEpochs.getOrDefault(segmentId, -1L);
        }

        @Override
        public long getTombstoneEpoch(long segmentId) {
            return tombstoneEpochs.getOrDefault(segmentId, Long.MAX_VALUE);
        }

        @Override
        public int getRegisteredSegmentCount() {
            return creationEpochs.size();
        }
    }

}
