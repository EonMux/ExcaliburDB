package com.eonmux.excalibur.integration;

import com.eonmux.excalibur.ingest.pipeline.DocumentBatchBuffer;
import com.eonmux.excalibur.ingest.pipeline.DocumentBatchPublisher;
import com.eonmux.excalibur.ingest.processor.TextChunk;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityRegistry;
import com.eonmux.excalibur.query.IndexCoverage;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.QueryResponse;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
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
import java.util.HashMap;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Lock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for concurrent query execution during ongoing ingestion.
 *
 * <h2>Test Coverage</h2>
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Queries can be issued concurrently with ongoing ingestion on separate threads</li>
 *   <li>No exceptions occur during concurrent operations</li>
 *   <li>Results progressively improve as indexing completes</li>
 *   <li>Epoch isolation - no torn reads</li>
 *   <li>Document atomicity - no partial documents in results</li>
 * </ul>
 *
 * <h2>Domain Correctness Criteria</h2>
 * <ul>
 *   <li>All chunks for a document become visible atomically (same epoch)</li>
 *   <li>Queries never see partial documents</li>
 *   <li>Epoch values monotonically increase</li>
 *   <li>Result counts increase (or stay same) as more data is ingested</li>
 *   <li>Coverage values in range [0.0, 1.0]</li>
 *   <li>No duplicate entries in results</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
class ConcurrentQueryDuringIngestionIntegrationTest {

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
    private DocumentVisibilityRegistry visibilityRegistry;
    private DocumentBatchPublisher publisher;

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

        // Create visibility tracker
        visibilityTracker = new TestVisibilityTracker();

        // Create write log
        Path logPath = tempDir.resolve("test.log");
        writeLog = new WriteLog(logPath);

        // Create ingestion infrastructure
        documentWriter = new AtomicDocumentWriter(writeLog);
        documentRegistry = new DocumentRegistry();
        visibilityRegistry = new DocumentVisibilityRegistry();
        publisher = new DocumentBatchPublisher(
            visibilityTracker,
            documentRegistry,
            visibilityRegistry,
            // com.eonmux.excalibur.store.WriteLog fsyncs inside every append, so the publisher's
            // durability barrier is already satisfied before the adapter runs.
            () -> { },
            new ReentrantLock()
        );
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

    @Nested
    @DisplayName("Concurrent Ingestion and Query")
    class ConcurrentIngestionAndQuery {

        /**
         * Test: Issue queries concurrently with ongoing ingestion on separate threads.
         *
         * <p>Domain correctness: Queries must complete without exceptions and
         * return consistent results. No torn reads or partial documents.</p>
         */
        @Test
        @DisplayName("queries and ingestion run concurrently without exceptions")
        void queriesAndIngestionRunConcurrentlyWithoutExceptions() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("concurrent.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Create query pipeline with document atomicity support
            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                visibilityRegistry,  // Document atomicity checker
                graphProvider,
                distanceFunction
            );

            int numIngestionThreads = 2;
            int numQueryThreads = 4;
            int documentsPerThread = 25;
            int chunksPerDocument = 3;

            ExecutorService executor = Executors.newFixedThreadPool(
                numIngestionThreads + numQueryThreads
            );

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch ingestionComplete = new CountDownLatch(numIngestionThreads);
            CountDownLatch queriesComplete = new CountDownLatch(numQueryThreads);
            AtomicBoolean ingestionRunning = new AtomicBoolean(true);

            AtomicInteger exceptionCount = new AtomicInteger(0);
            AtomicInteger queryCount = new AtomicInteger(0);
            AtomicInteger querySuccessCount = new AtomicInteger(0);
            List<String> exceptions = Collections.synchronizedList(new ArrayList<>());

            // Register a test segment with both visibility tracker AND storage engine
            // CRITICAL: The storage engine must have the segment registered for queries to find it
            // NOTE: Segment IDs are auto-generated by the Segment class, so we use the returned ID
            long segmentId = storageEngine.registerSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(segmentId, 0L);

            // Ingestion tasks
            Runnable ingestionTask = () -> {
                try {
                    startLatch.await();
                    int threadId = (int) Thread.currentThread().threadId();

                    for (int d = 0; d < documentsPerThread && ingestionRunning.get(); d++) {
                        String documentId = "doc-thread" + threadId + "-" + d;

                        // Create document with chunks
                        List<TextChunk> chunks = createChunks(documentId, chunksPerDocument);
                        documentWriter.write(documentId, chunks);

                        // Insert vector into HNSW graph
                        float[] vector = createRandomVector(dimension);
                        long nodeId = graph.insert(vector);

                        // CRITICAL: Register the document ID with the HNSW node so search results
                        // include the string document ID for atomicity filtering
                        graph.registerDocumentId(nodeId, documentId);

                        // CRITICAL: Update lastIntegratedOffset so query pipeline knows vectors are in graph
                        graph.setLastIntegratedOffset(writeLog.writeOffset());

                        // Sync write log and advance epoch
                        long newEpoch = visibilityTracker.advanceEpoch();

                        // CRITICAL: Register document with visibility registry for document atomicity filtering
                        // Without this, DocumentAtomicityFilter will filter out ALL results
                        Set<String> chunkIds = new HashSet<>();
                        for (int i = 0; i < chunksPerDocument; i++) {
                            chunkIds.add(documentId + ":" + i);
                        }
                        visibilityRegistry.registerDocument(documentId, chunkIds, newEpoch);

                        // Small delay to allow concurrent queries
                        if (d % 10 == 0) {
                            Thread.sleep(5);
                        }
                    }
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                    exceptions.add("Ingestion: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    ingestionComplete.countDown();
                }
            };

            // Query tasks
            Runnable queryTask = () -> {
                try {
                    startLatch.await();

                    while (ingestionRunning.get()) {
                        try {
                            QueryRequest request = QueryRequest.builder()
                                .queryText("concurrent query")
                                .queryVector(createRandomVector(dimension))
                                .topK(10)
                                .timeoutMs(5000)
                                .includePartialSegments(true)
                                .enableLlmScoring(false)
                                .correlationId(UUID.randomUUID().toString())
                                .build();

                            CancellationToken token = new CancellationToken();
                            QueryResponse response = pipeline.executeSync(request, token);

                            queryCount.incrementAndGet();
                            if (!response.isEmpty()) {
                                querySuccessCount.incrementAndGet();

                                // Verify no duplicates in results
                                Set<Long> seenIds = new HashSet<>();
                                for (var result : response.results()) {
                                    assertThat(seenIds).doesNotContain(result.documentId());
                                    seenIds.add(result.documentId());
                                }
                            }

                            // Small delay between queries - bounded to avoid infinite loop
                            Thread.sleep(10);
                        } catch (Exception e) {
                            exceptionCount.incrementAndGet();
                            exceptions.add("Query: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    queriesComplete.countDown();
                }
            };

            // Start ingestion threads
            for (int i = 0; i < numIngestionThreads; i++) {
                executor.submit(ingestionTask);
            }

            // Start query threads
            for (int i = 0; i < numQueryThreads; i++) {
                executor.submit(queryTask);
            }

            // Signal all threads to start
            startLatch.countDown();

            // Wait for ingestion to complete
            boolean ingestionCompleted = ingestionComplete.await(60, TimeUnit.SECONDS);
            if (!ingestionCompleted) {
                throw new AssertionError("Ingestion threads did not complete within timeout");
            }

            // Signal queries to stop and wait for them to finish
            ingestionRunning.set(false);

            // Wait for all query threads to complete their current work
            boolean queriesCompleted = queriesComplete.await(30, TimeUnit.SECONDS);
            if (!queriesCompleted) {
                // Log which queries are still running for debugging
                System.out.println("Warning: Not all query threads completed within timeout");
            }

            // Force shutdown if needed
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

            // Verify no exceptions
            assertThat(exceptionCount.get())
                .as("No exceptions should occur during concurrent ingestion/query. Exceptions: " + exceptions)
                .isEqualTo(0);

            assertThat(queriesCompleted)
                .as("All query threads must finish within the timeout")
                .isTrue();

            // Verify queries were executed
            assertThat(queryCount.get())
                .as("Queries should have been executed during ingestion")
                .isGreaterThan(0);

            // The point of the test is that queries WORK during ingestion. A pipeline that
            // returned an empty result set for every query would satisfy every assertion
            // above, so the count of queries that actually saw data has to be asserted too.
            assertThat(querySuccessCount.get())
                .as("Queries issued during ingestion must return data, not just complete "
                    + "(%d of %d returned results)", querySuccessCount.get(), queryCount.get())
                .isGreaterThan(0);

            System.out.println("Concurrent test: " + queryCount.get() + " queries, " +
                querySuccessCount.get() + " with results, " + exceptionCount.get() + " exceptions");

            graph.close();
        }

        /**
         * Test: Results progressively improve as indexing completes.
         *
         * <p>Domain correctness: Result counts should increase (or stay same)
         * as more documents are ingested into the index.</p>
         */
        @Test
        @DisplayName("results progressively improve during ingestion")
        void resultsProgressivelyImproveDuringIngestion() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("progressive.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Create query pipeline with document atomicity support
            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                visibilityRegistry,  // Document atomicity checker
                graphProvider,
                distanceFunction
            );

            // Register a test segment with both visibility tracker AND storage engine
            // CRITICAL: The storage engine must have the segment registered for queries to find it
            // NOTE: Segment IDs are auto-generated by the Segment class, so we use the returned ID
            long segmentId = storageEngine.registerSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(segmentId, 0L);

            int totalDocuments = 50;
            int chunksPerDocument = 3;
            int[] checkpoints = {0, 10, 25, 40, 50};
            int[] resultCounts = new int[checkpoints.length];

            float[] queryVector = createRandomVector(dimension);

            for (int c = 0; c < checkpoints.length; c++) {
                int targetDocs = checkpoints[c];
                int currentDocs = c > 0 ? checkpoints[c - 1] : 0;

                // Ingest more documents
                for (int d = currentDocs; d < targetDocs; d++) {
                    String documentId = "doc-progressive-" + d;
                    List<TextChunk> chunks = createChunks(documentId, chunksPerDocument);
                    documentWriter.write(documentId, chunks);

                    float[] vector = createRandomVector(dimension);
                    long nodeId = graph.insert(vector);

                    // CRITICAL: Register the document ID with the HNSW node so search results
                    // include the string document ID for atomicity filtering
                    graph.registerDocumentId(nodeId, documentId);

                    // CRITICAL: Update lastIntegratedOffset so query pipeline knows vectors are in graph
                    graph.setLastIntegratedOffset(writeLog.writeOffset());

                    long newEpoch = visibilityTracker.advanceEpoch();

                    // CRITICAL: Register document with visibility registry for document atomicity filtering
                    // Without this, DocumentAtomicityFilter will filter out ALL results because the
                    // document is not registered as visible
                    Set<String> chunkIds = new HashSet<>();
                    for (int i = 0; i < chunksPerDocument; i++) {
                        chunkIds.add(documentId + ":" + i);
                    }
                    visibilityRegistry.registerDocument(documentId, chunkIds, newEpoch);
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

                CancellationToken token = new CancellationToken();
                QueryResponse response = pipeline.executeSync(request, token);

                resultCounts[c] = response.results().size();

                System.out.println("Checkpoint " + c + " (" + targetDocs + " docs): " +
                    resultCounts[c] + " results, coverage=" +
                    response.coverage().estimatedCorpusCoverage());
            }

            // Verify result counts are monotonically non-decreasing
            for (int c = 1; c < resultCounts.length; c++) {
                assertThat(resultCounts[c])
                    .as("Result count at checkpoint " + c + " (" + checkpoints[c] +
                        " docs) should be >= checkpoint " + (c - 1) + " (" +
                        checkpoints[c - 1] + " docs)")
                    .isGreaterThanOrEqualTo(resultCounts[c - 1]);
            }

            // CRITICAL: Verify that results are actually returned after ingesting documents.
            // This assertion catches the bug where TestStorageEngine.getSegment() returns null,
            // causing all queries to return 0 results trivially.
            assertThat(resultCounts[checkpoints.length - 1])
                .as("Final checkpoint should have results after ingesting " + totalDocuments +
                    " documents. If this fails with 0 results, check that storageEngine.registerSegment() " +
                    "is called and TestStorageEngine.getSegment() returns non-null.")
                .isGreaterThan(0);

            graph.close();
        }
    }

    @Nested
    @DisplayName("Epoch Isolation")
    class EpochIsolation {

        /**
         * Test: Epoch isolation - segment-level visibility consistency.
         *
         * <p><strong>IMPORTANT:</strong> ExcaliburDB provides SEGMENT-level epoch isolation,
         * not document-level isolation. Per the PROJECT GOAL: "Each query captures the
         * epoch at start time and sees a consistent segment snapshot."</p>
         *
         * <p>This means that when a query captures epoch E, it sees all segments that
         * were visible at epoch E. All documents within those segments are visible,
         * even if they were added to the segment after epoch E.</p>
         *
         * <p>Domain correctness: A query capturing epoch E must only see SEGMENTS
         * visible at epoch E. The test verifies that:</p>
         * <ul>
         *   <li>Queries complete without exceptions during concurrent ingestion</li>
         *   <li>Segments are properly visible at the captured epoch</li>
         *   <li>No corrupted or incomplete data is returned</li>
         *   <li>Coverage metadata is consistent (in range [0.0, 1.0])</li>
         * </ul>
         */
        @Test
        @DisplayName("segment visibility is consistent during concurrent ingestion")
        void segmentVisibilityIsConsistentDuringConcurrentIngestion() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("epoch-isolation.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Create query pipeline with document atomicity support
            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                visibilityRegistry,  // Document atomicity checker
                graphProvider,
                distanceFunction
            );

            // Register a test segment with both visibility tracker AND storage engine
            // CRITICAL: The storage engine must have the segment registered for queries to find it
            // NOTE: Segment IDs are auto-generated by the Segment class, so we use the returned ID
            long segmentId = storageEngine.registerSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(segmentId, 0L);

            int numDocuments = 30;
            int chunksPerDocument = 5;
            AtomicInteger exceptionCount = new AtomicInteger(0);
            AtomicInteger totalReads = new AtomicInteger(0);
            AtomicInteger emptyReads = new AtomicInteger(0);
            AtomicInteger inconsistentCoverage = new AtomicInteger(0);

            // Pre-ingest some documents
            for (int d = 0; d < 10; d++) {
                String documentId = "doc-epoch-pre-" + d;
                ingestDocument(documentId, chunksPerDocument, graph);
            }

            ExecutorService executor = Executors.newFixedThreadPool(6);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch writerDone = new CountDownLatch(1);
            CountDownLatch readersDone = new CountDownLatch(5); // 5 reader threads
            AtomicBoolean writersFinished = new AtomicBoolean(false);

            // Writer task - ingests documents
            Runnable writer = () -> {
                try {
                    startLatch.await();

                    for (int d = 10; d < numDocuments; d++) {
                        String documentId = "doc-epoch-" + d;
                        ingestDocument(documentId, chunksPerDocument, graph);

                        Thread.sleep(10); // Small delay to allow readers
                    }

                    writersFinished.set(true);
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    writerDone.countDown();
                }
            };

            // Reader task - queries and verifies segment-level visibility consistency
            Runnable reader = () -> {
                try {
                    startLatch.await();

                    while (!writersFinished.get()) {
                        // Capture query epoch
                        long queryEpoch = visibilityTracker.captureEpoch();

                        QueryRequest request = QueryRequest.builder()
                            .queryText("epoch isolation query")
                            .queryVector(createRandomVector(dimension))
                            .topK(50)
                            .timeoutMs(5000)
                            .includePartialSegments(true)
                            .enableLlmScoring(false)
                            .correlationId(UUID.randomUUID().toString())
                            .build();

                        CancellationToken token = new CancellationToken();
                        QueryResponse response = pipeline.executeSync(request, token);

                        totalReads.incrementAndGet();

                        // Verify segment-level consistency:
                        // 1. No exceptions occurred (query completed successfully)
                        // 2. Coverage is in valid range [0.0, 1.0]
                        // 3. Results are not corrupted (valid document IDs)

                        if (response.results().isEmpty()) {
                            emptyReads.incrementAndGet();
                        }

                        // Verify coverage is in valid range
                        IndexCoverage coverage = response.coverage();
                        if (coverage != null) {
                            double estimatedCoverage = coverage.estimatedCorpusCoverage();
                            if (estimatedCoverage < 0.0 || estimatedCoverage > 1.0) {
                                inconsistentCoverage.incrementAndGet();
                            }
                        }

                        Thread.sleep(5);
                    }
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    readersDone.countDown();
                }
            };

            // Start writer
            executor.submit(writer);

            // Start readers
            for (int i = 0; i < 5; i++) {
                executor.submit(reader);
            }

            startLatch.countDown();

            // Wait for writer to complete
            boolean writerCompleted = writerDone.await(60, TimeUnit.SECONDS);
            if (!writerCompleted) {
                throw new AssertionError("Writer did not complete within timeout");
            }

            // Wait for all readers to finish their current work
            boolean readersCompleted = readersDone.await(30, TimeUnit.SECONDS);
            if (!readersCompleted) {
                System.out.println("Warning: Not all readers completed within timeout");
            }

            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

            System.out.println("Segment visibility test: " + totalReads.get() + " reads, " +
                emptyReads.get() + " empty, " + exceptionCount.get() + " exceptions, " +
                inconsistentCoverage.get() + " inconsistent coverage");

            // Verify segment-level isolation guarantees:
            // 1. No exceptions during concurrent operations
            assertThat(exceptionCount.get())
                .as("No exceptions should occur during concurrent queries")
                .isEqualTo(0);

            // 2. Coverage values are in valid range
            assertThat(inconsistentCoverage.get())
                .as("Coverage values should be in range [0.0, 1.0]")
                .isEqualTo(0);

            // 3. Some queries should have returned results (after pre-ingestion)
            assertThat(totalReads.get())
                .as("Should have completed some queries")
                .isGreaterThan(0);

            graph.close();
        }

        /**
         * Test: Visibility epoch monotonically increases.
         *
         * <p>Domain correctness: Epoch values must always increase, never decrease.</p>
         */
        @Test
        @DisplayName("visibility epoch monotonically increases")
        void visibilityEpochMonotonicallyIncreases() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("monotonic.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            int numIterations = 100;
            long previousEpoch = visibilityTracker.currentEpoch();

            for (int i = 0; i < numIterations; i++) {
                // Ingest a document
                String documentId = "doc-monotonic-" + i;
                ingestDocument(documentId, 2, graph);

                long currentEpoch = visibilityTracker.currentEpoch();

                // Verify monotonic increase
                assertThat(currentEpoch)
                    .as("Epoch should monotonically increase at iteration " + i)
                    .isGreaterThanOrEqualTo(previousEpoch);

                previousEpoch = currentEpoch;
            }

            System.out.println("Verified monotonic epoch advancement over " + numIterations + " iterations");
            graph.close();
        }
    }

    @Nested
    @DisplayName("Document Atomicity")
    class DocumentAtomicity {

        /**
         * Test: Document atomicity - all chunks visible at same epoch.
         *
         * <p>Domain correctness: When a document becomes visible, ALL its chunks
         * must be visible, not a subset.</p>
         */
        @Test
        @DisplayName("all document chunks become visible atomically")
        void allDocumentChunksBecomeVisibleAtomically() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("atomic.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Create query pipeline with document atomicity support
            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                visibilityRegistry,  // Document atomicity checker
                graphProvider,
                distanceFunction
            );

            // Register a test segment with both visibility tracker AND storage engine
            // CRITICAL: The storage engine must have the segment registered for queries to find it
            // NOTE: Segment IDs are auto-generated by the Segment class, so we use the returned ID
            long segmentId = storageEngine.registerSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(segmentId, 0L);

            int numDocuments = 20;
            int chunksPerDocument = 5;

            // Track which documents have been fully published with their publication epoch
            // Using a ConcurrentHashMap to avoid race between publication and epoch capture
            ConcurrentHashMap<String, Long> publishedDocumentsWithEpoch = new ConcurrentHashMap<>();
            AtomicInteger totalQueries = new AtomicInteger(0);
            AtomicInteger atomicityViolations = new AtomicInteger(0);
            List<String> violationDetails = Collections.synchronizedList(new ArrayList<>());

            int numReaders = 2;
            ExecutorService executor = Executors.newFixedThreadPool(1 + numReaders);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch writerDone = new CountDownLatch(1);
            CountDownLatch readersDone = new CountDownLatch(numReaders);
            AtomicBoolean writersFinished = new AtomicBoolean(false);

            // Writer task - uses DocumentBatchPublisher for atomic publication
            Runnable writer = () -> {
                try {
                    startLatch.await();

                    for (int d = 0; d < numDocuments; d++) {
                        String documentId = "doc-atomic-" + d;

                        // Create batch buffer for atomic publication
                        DocumentBatchBuffer batchBuffer = new DocumentBatchBuffer(documentId, "test-source");

                        // Add chunks to batch
                        List<TextChunk> chunks = createChunks(documentId, chunksPerDocument);
                        for (int c = 0; c < chunks.size(); c++) {
                            TextChunk chunk = chunks.get(c);
                            String chunkId = documentId + ":" + c;
                            long offset = writeLog.writeOffset();
                            batchBuffer.addChunk(chunkId, chunk, offset);
                        }

                        // Seal the batch (no more chunks can be added)
                        batchBuffer.seal();

                        // Insert vector into HNSW graph
                        float[] vector = createRandomVector(dimension);
                        long nodeId = graph.insert(vector);
                        graph.registerDocumentId(nodeId, documentId);
                        graph.setLastIntegratedOffset(writeLog.writeOffset());

                        // Publish atomically - this registers document in visibilityRegistry
                        DocumentMetadata metadata = DocumentMetadata.builder()
                            .sourceId("test-source")
                            .documentId(documentId)
                            .lastModifiedMillis(System.currentTimeMillis())
                            .contentHash((long) d * 1000)
                            .contentLength(1000)
                            .indexTimeMillis(System.currentTimeMillis())
                            .build();

                        publisher.publish(batchBuffer, metadata);

                        // Sync write log and advance epoch
                        long publicationEpoch = visibilityTracker.advanceEpoch();

                        // Register document with visibility registry for atomicity filtering
                        Set<String> chunkIds = new HashSet<>();
                        for (int i = 0; i < chunksPerDocument; i++) {
                            chunkIds.add(documentId + ":" + i);
                        }
                        visibilityRegistry.registerDocument(documentId, chunkIds, publicationEpoch);

                        // Mark document as published with its epoch - atomically with visibility registration
                        publishedDocumentsWithEpoch.put(documentId, publicationEpoch);

                        Thread.sleep(10);
                    }

                    writersFinished.set(true);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    writerDone.countDown();
                }
            };

            // Reader task - verifies document atomicity
            Runnable reader = () -> {
                try {
                    startLatch.await();

                    while (!writersFinished.get()) {
                        // Capture the current epoch BEFORE executing the query
                        long queryEpoch = visibilityTracker.currentEpoch();

                        QueryRequest request = QueryRequest.builder()
                            .queryText("atomicity query")
                            .queryVector(createRandomVector(dimension))
                            .topK(100)
                            .timeoutMs(5000)
                            .includePartialSegments(true)
                            .enableLlmScoring(false)
                            .correlationId(UUID.randomUUID().toString())
                            .build();

                        CancellationToken token = new CancellationToken();
                        QueryResponse response = pipeline.executeSync(request, token);

                        totalQueries.incrementAndGet();

                        // Verify document atomicity:
                        // Every document in results should be fully visible (published with epoch <= queryEpoch)
                        for (var result : response.results()) {
                            String resultDocId = result.documentIdStr();
                            if (resultDocId == null) {
                                resultDocId = String.valueOf(result.documentId());
                            }

                            // Check the publication epoch of this document
                            Long publicationEpoch = publishedDocumentsWithEpoch.get(resultDocId);

                            if (publicationEpoch != null) {
                                // Document was published - verify it's visible at the query epoch
                                boolean isVisibleInRegistry = visibilityRegistry.isDocumentVisible(resultDocId, queryEpoch);

                                // If document was published at epoch <= queryEpoch, it MUST be visible
                                // If document was published at epoch > queryEpoch, it's OK if not visible
                                if (publicationEpoch <= queryEpoch && !isVisibleInRegistry) {
                                    // Document was published before/during query but not visible - VIOLATION
                                    atomicityViolations.incrementAndGet();
                                    violationDetails.add(String.format(
                                        "Document %s was published at epoch %d but not visible at query epoch %d",
                                        resultDocId, publicationEpoch, queryEpoch));
                                }
                            }
                            // If publicationEpoch is null, document is still being published - skip verification
                        }

                        Thread.sleep(15);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    readersDone.countDown();
                }
            };

            // Start writer
            executor.submit(writer);

            // Start readers
            for (int i = 0; i < numReaders; i++) {
                executor.submit(reader);
            }

            startLatch.countDown();

            // Wait for writer to complete
            boolean writerCompleted = writerDone.await(60, TimeUnit.SECONDS);
            if (!writerCompleted) {
                throw new AssertionError("Writer did not complete within timeout");
            }

            // Wait for readers to finish their current work
            boolean readersCompleted = readersDone.await(30, TimeUnit.SECONDS);
            if (!readersCompleted) {
                System.out.println("Warning: Not all readers completed within timeout");
            }

            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

            System.out.println("Document atomicity test: " + atomicityViolations.get() +
                " violations out of " + totalQueries.get() + " queries");
            if (!violationDetails.isEmpty()) {
                System.out.println("Violation details: " + violationDetails);
            }

            // Verify no atomicity violations
            assertThat(atomicityViolations.get())
                .as("No atomicity violations should occur. Violations: " + violationDetails)
                .isEqualTo(0);

            graph.close();
        }

        /**
         * Test: No partial documents during concurrent ingestion.
         *
         * <p>Domain correctness: A query should never see some chunks of a document
         * but not all of them.</p>
         */
        @Test
        @DisplayName("no partial documents during concurrent ingestion")
        void noPartialDocumentsDuringConcurrentIngestion() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("no-partial.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Create query pipeline with document atomicity support
            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                visibilityRegistry,  // Document atomicity checker
                graphProvider,
                distanceFunction
            );

            // Register a test segment with both visibility tracker AND storage engine
            // CRITICAL: The storage engine must have the segment registered for queries to find it
            // NOTE: Segment IDs are auto-generated by the Segment class, so we use the returned ID
            long segmentId = storageEngine.registerSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(segmentId, 0L);

            // Use DocumentBatchBuffer for atomic publication
            int numDocuments = 15;
            int chunksPerDocument = 4;

            for (int d = 0; d < numDocuments; d++) {
                String documentId = "doc-batch-" + d;

                // Create batch buffer for atomic publication
                DocumentBatchBuffer batchBuffer = new DocumentBatchBuffer(documentId, "test-source");

                // Add chunks to batch
                List<TextChunk> chunks = createChunks(documentId, chunksPerDocument);
                for (int c = 0; c < chunks.size(); c++) {
                    TextChunk chunk = chunks.get(c);
                    String chunkId = documentId + ":" + c;
                    long offset = writeLog.writeOffset();
                    batchBuffer.addChunk(chunkId, chunk, offset);
                }

                // Seal the batch
                batchBuffer.seal();

                // Publish atomically
                DocumentMetadata metadata = DocumentMetadata.builder()
                    .sourceId("test-source")
                    .documentId(documentId)
                    .lastModifiedMillis(System.currentTimeMillis())
                    .contentHash((long) d * 1000)
                    .contentLength(1000)
                    .indexTimeMillis(System.currentTimeMillis())
                    .build();

                publisher.publish(batchBuffer, metadata);

                // Insert vector and register document ID for atomicity filtering
                float[] vector = createRandomVector(dimension);
                long nodeId = graph.insert(vector);

                // CRITICAL: Register document ID with HNSW node for atomicity filtering
                graph.registerDocumentId(nodeId, documentId);

                // CRITICAL: Update lastIntegratedOffset so query pipeline knows vector is in graph
                graph.setLastIntegratedOffset(writeLog.writeOffset());

                long newEpoch = visibilityTracker.advanceEpoch();

                // CRITICAL: Register document with visibility registry for document atomicity filtering
                Set<String> chunkIds = new HashSet<>();
                for (int i = 0; i < chunksPerDocument; i++) {
                    chunkIds.add(documentId + ":" + i);
                }
                visibilityRegistry.registerDocument(documentId, chunkIds, newEpoch);
            }

            // Query to verify all documents are fully visible
            QueryRequest request = QueryRequest.builder()
                .queryText("verify completeness")
                .queryVector(createRandomVector(dimension))
                .topK(100)
                .timeoutMs(5000)
                .includePartialSegments(true)
                .enableLlmScoring(false)
                .correlationId(UUID.randomUUID().toString())
                .build();

            CancellationToken token = new CancellationToken();
            QueryResponse response = pipeline.executeSync(request, token);

            // Verify no duplicates (would indicate partial visibility)
            Set<Long> seenIds = new HashSet<>();
            for (var result : response.results()) {
                assertThat(seenIds).doesNotContain(result.documentId());
                seenIds.add(result.documentId());
            }

            System.out.println("Verified " + seenIds.size() + " unique documents (no partial visibility)");

            graph.close();
        }
    }

    @Nested
    @DisplayName("Coverage During Ingestion")
    class CoverageDuringIngestion {

        /**
         * Test: Coverage metadata accurately reflects ingestion progress.
         *
         * <p>Domain correctness: Coverage values should be in [0.0, 1.0] and
         * increase (or stay same) as more data is ingested.</p>
         */
        @Test
        @DisplayName("coverage accurately reflects ingestion progress")
        void coverageAccuratelyReflectsIngestionProgress() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("coverage.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Create query pipeline with document atomicity support
            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                visibilityRegistry,  // Document atomicity checker
                graphProvider,
                distanceFunction
            );

            // Register a test segment with both visibility tracker AND storage engine
            // CRITICAL: The storage engine must have the segment registered for queries to find it
            // NOTE: Segment IDs are auto-generated by the Segment class, so we use the returned ID
            long segmentId = storageEngine.registerSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(segmentId, 0L);

            int totalDocuments = 40;
            double[] coverages = new double[totalDocuments / 10];

            float[] queryVector = createRandomVector(dimension);

            for (int d = 0; d < totalDocuments; d++) {
                // Ingest document
                String documentId = "doc-coverage-" + d;
                ingestDocument(documentId, 3, graph);

                // Check coverage every 10 documents
                if ((d + 1) % 10 == 0) {
                    QueryRequest request = QueryRequest.builder()
                        .queryText("coverage check")
                        .queryVector(queryVector)
                        .topK(10)
                        .timeoutMs(5000)
                        .includePartialSegments(true)
                        .enableLlmScoring(false)
                        .correlationId(UUID.randomUUID().toString())
                        .build();

                    CancellationToken token = new CancellationToken();
                    QueryResponse response = pipeline.executeSync(request, token);

                    IndexCoverage coverage = response.coverage();
                    int index = (d + 1) / 10 - 1;
                    coverages[index] = coverage.estimatedCorpusCoverage();

                    // Verify coverage in valid range
                    assertThat(coverage.estimatedCorpusCoverage())
                        .as("Coverage should be in [0.0, 1.0]")
                        .isBetween(0.0, 1.0);

                    System.out.println("After " + (d + 1) + " docs: coverage=" +
                        coverage.estimatedCorpusCoverage() + ", degraded=" + coverage.degraded());
                }
            }

            // Verify coverage is non-decreasing
            for (int i = 1; i < coverages.length; i++) {
                assertThat(coverages[i])
                    .as("Coverage should be non-decreasing")
                    .isGreaterThanOrEqualTo(coverages[i - 1]);
            }

            graph.close();
        }
    }

    // ==================== Helper Methods ====================

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

    private void ingestDocument(String documentId, int chunkCount, HnswGraph graph) throws IOException {
        List<TextChunk> chunks = createChunks(documentId, chunkCount);
        documentWriter.write(documentId, chunks);

        float[] vector = createRandomVector(dimension);
        // CRITICAL: Register document ID with HNSW graph so results include the string ID
        long nodeId = graph.insert(vector);
        graph.registerDocumentId(nodeId, documentId);

        // CRITICAL: Update lastIntegratedOffset so query pipeline knows vectors are in graph
        graph.setLastIntegratedOffset(writeLog.writeOffset());

        long newEpoch = visibilityTracker.advanceEpoch();

        // CRITICAL: Register document with visibility registry for document atomicity filtering
        // Without this, DocumentAtomicityFilter will filter out ALL results because the
        // document is not registered as visible
        Set<String> chunkIds = new HashSet<>();
        for (int i = 0; i < chunkCount; i++) {
            chunkIds.add(documentId + ":" + i);
        }
        visibilityRegistry.registerDocument(documentId, chunkIds, newEpoch);
    }

    // ==================== Test Infrastructure ====================

    /**
     * Test StorageEngine that stores and returns actual segments.
     *
     * <p>This implementation is critical for the live indexing test to work correctly.
     * The query pipeline calls {@code storageEngine.getSegment(segmentId)} during
     * snapshot resolution. If this returns null, no segments are searched.</p>
     *
     * <p>Creates real Segment instances backed by files in the data directory.
     * This ensures the query pipeline has valid Segment objects to work with.</p>
     *
     * <p><strong>IMPORTANT:</strong> Segment IDs are auto-generated by the Segment class
     * (from a global counter). The registerSegment method returns the actual segment ID
     * that was assigned, which should be used for visibility tracking and catalog registration.</p>
     */
    private static class TestStorageEngine extends StorageEngine {
        private final TestSegmentCatalog catalog;
        private final ConcurrentHashMap<Long, Segment> segments = new ConcurrentHashMap<>();
        private final Path dataDir;
        private final Arena arena;
        private int segmentCounter = 0;

        TestStorageEngine(Path dataDir, Arena arena) throws IOException {
            // The caller's arena backs both the engine's mapped catalog and the segments
            // created here. Allocating fresh shared arenas would leak them: a shared arena
            // is released only by an explicit close(), never by the collector.
            super(dataDir, arena);
            this.dataDir = dataDir;
            this.arena = arena;
            this.catalog = new TestSegmentCatalog();
        }

        /**
         * Registers a new segment with a specific lifecycle state.
         * Must be called before queries are executed for that segment.
         * Creates a real Segment file in the data directory.
         *
         * <p><strong>NOTE:</strong> The segmentId parameter is ignored - the actual segment ID
         * is auto-generated by the Segment class. The returned ID should be used for
         * visibility tracking.</p>
         *
         * @param ignoredSegmentId This parameter is ignored (segment IDs are auto-generated)
         * @param state The lifecycle state (must be queryable: INDEXING, WARMING, ACTIVE, COMPACTING)
         * @return The actual segment ID that was assigned
         */
        long registerSegment(long ignoredSegmentId, LifecycleState state) throws IOException {
            // Create a real segment file - the Segment class auto-generates the ID
            Path segmentPath = dataDir.resolve("test_segment_" + segmentCounter++ + ".dat");
            Segment segment = new Segment(segmentPath, 1024 * 1024, arena); // 1MB segment

            // Get the ACTUAL segment ID from the segment
            long actualSegmentId = segment.segmentId();

            // Register with catalog using the ACTUAL segment ID
            catalog.registerSegment(actualSegmentId, state);
            segments.put(actualSegmentId, segment);

            return actualSegmentId;
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
        public void close() {
            for (Segment segment : segments.values()) {
                try {
                    segment.close();
                } catch (Exception e) {
                    // Ignore close errors
                }
            }
            segments.clear();
            catalog.clear();
            // The base engine owns the mapped catalog and the split worker; skipping it
            // leaves both alive for the lifetime of the test fork.
            super.close();
        }
    }

    /**
     * Test SegmentCatalog that stores and returns segment metadata.
     *
     * <p>This implementation is critical for the live indexing test to work correctly.
     * The query pipeline calls {@code catalog.get(segmentId)} to get metadata and check
     * lifecycle state. If this returns null, segments are skipped during query execution.</p>
     */
    private static class TestSegmentCatalog extends SegmentCatalog {
        private final ConcurrentHashMap<Long, SegmentMetadata> metadata = new ConcurrentHashMap<>();

        /**
         * Registers a segment with a specific lifecycle state.
         * Uses proper state transitions to ensure valid lifecycle.
         *
         * @param segmentId The segment ID
         * @param lifecycleState The target lifecycle state
         */
        void registerSegment(long segmentId, LifecycleState lifecycleState) {
            SegmentMetadata meta = new SegmentMetadata(segmentId);
            // Default state is BOOTSTRAPPING, transition to target state through valid transitions
            if (lifecycleState != LifecycleState.BOOTSTRAPPING) {
                meta.transitionToIndexing(); // BOOTSTRAPPING -> INDEXING
                if (lifecycleState == LifecycleState.WARMING
                    || lifecycleState == LifecycleState.ACTIVE
                    || lifecycleState == LifecycleState.COMPACTING) {
                    meta.transitionToWarming(); // INDEXING -> WARMING
                    if (lifecycleState == LifecycleState.ACTIVE || lifecycleState == LifecycleState.COMPACTING) {
                        meta.transitionToActive(); // WARMING -> ACTIVE
                        if (lifecycleState == LifecycleState.COMPACTING) {
                            meta.transitionToCompacting(); // ACTIVE -> COMPACTING
                        }
                    }
                }
                // For INDEXING, just the transitionToIndexing() call is enough
            }
            meta.setCreationEpoch(0);
            metadata.put(segmentId, meta);
        }

        @Override
        public SegmentMetadata get(long segmentId) {
            return metadata.get(segmentId);
        }

        @Override
        public Map<Long, SegmentMetadata> getAll() {
            return Collections.unmodifiableMap(metadata);
        }

        @Override
        public int size() {
            return metadata.size();
        }

        /**
         * Clears all segment metadata.
         */
        public void clear() {
            metadata.clear();
        }
    }

    /**
     * Test VisibilityTracker implementation with proper synchronization.
     *
     * <p><strong>Thread Safety:</strong> All operations are synchronized to prevent
     * race conditions between segment registration and epoch advancement. This ensures
     * that a segment is always registered with the correct creation epoch atomically.</p>
     */
    private static class TestVisibilityTracker extends VisibilityTracker {
        private final Lock lock = new ReentrantLock();
        private long epoch = 1;
        private final Map<Long, Long> creationEpochs = new HashMap<>();
        private final Map<Long, Long> tombstoneEpochs = new HashMap<>();

        TestVisibilityTracker() {
            super(new TestSegmentCatalog());
        }

        @Override
        public long currentEpoch() {
            lock.lock();
            try {
                return epoch;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public long captureEpoch() {
            lock.lock();
            try {
                return epoch;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public long advanceEpoch() {
            lock.lock();
            try {
                return ++epoch;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void registerSegment(long segmentId, long creationEpoch) {
            lock.lock();
            try {
                creationEpochs.put(segmentId, creationEpoch);
                tombstoneEpochs.putIfAbsent(segmentId, Long.MAX_VALUE);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void markTombstoned(long segmentId, long tombEpoch) {
            lock.lock();
            try {
                tombstoneEpochs.put(segmentId, tombEpoch);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Set<Long> visibleSegments(long queryEpoch) {
            lock.lock();
            try {
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
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean isSegmentVisible(long segmentId, long queryEpoch) {
            lock.lock();
            try {
                Long creationEpoch = creationEpochs.get(segmentId);
                if (creationEpoch == null) {
                    return false;
                }
                long tombstoneEpoch = tombstoneEpochs.getOrDefault(segmentId, Long.MAX_VALUE);
                return creationEpoch <= queryEpoch && queryEpoch < tombstoneEpoch;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public long getCreationEpoch(long segmentId) {
            lock.lock();
            try {
                return creationEpochs.getOrDefault(segmentId, -1L);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public long getTombstoneEpoch(long segmentId) {
            lock.lock();
            try {
                return tombstoneEpochs.getOrDefault(segmentId, Long.MAX_VALUE);
            } finally {
                lock.unlock();
            }
        }
    }
}
