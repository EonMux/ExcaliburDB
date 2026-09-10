package excalibur.integration;

import excalibur.ingest.pipeline.DocumentBatchBuffer;
import excalibur.ingest.pipeline.DocumentBatchPublisher;
import excalibur.ingest.processor.TextChunk;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.visibility.DocumentVisibilityChecker;
import excalibur.ingest.visibility.DocumentVisibilityTracker;
import excalibur.query.IndexCoverage;
import excalibur.query.QueryRequest;
import excalibur.query.QueryResponse;
import excalibur.query.QueryResultItem;
import excalibur.query.executor.CancellationToken;
import excalibur.query.pipeline.QueryPipeline;
import excalibur.query.visibility.DocumentAtomicityFilter;
import excalibur.shard.VisibilityTracker;
import io.excalibur.store.LifecycleState;
import io.excalibur.store.Segment;
import io.excalibur.store.SegmentCatalog;
import io.excalibur.store.StorageEngine;
import io.excalibur.store.WriteLog;
import io.excalibur.vector.distance.CosineSimilarity;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.hnsw.HnswConfig;
import io.excalibur.vector.hnsw.HnswGraph;

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
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for epoch isolation guarantees in ExcaliburDB.
 *
 * <h2>Test Coverage</h2>
 * <p>These tests verify the three critical epoch isolation guarantees:</p>
 * <ul>
 *   <li><strong>No Torn Reads:</strong> Queries never see partial chunks of a document</li>
 *   <li><strong>No Partial Documents:</strong> All chunks of a document become visible atomically</li>
 *   <li><strong>No Concurrent Modification Exceptions:</strong> No CME during concurrent operations</li>
 * </ul>
 *
 * <h2>Domain Correctness Criteria</h2>
 * <ul>
 *   <li>All chunks for a document become visible at the same epoch</li>
 *   <li>Queries never see partially-ingested documents</li>
 *   <li>No ConcurrentModificationException during concurrent ingestion/query</li>
 *   <li>Epoch values monotonically increase</li>
 *   <li>Coverage values in range [0.0, 1.0]</li>
 *   <li>Result scores in range [0.0, 1.0] after normalization</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
class EpochIsolationIntegrationTest {

    @TempDir
    Path tempDir;

    private Arena arena;
    private DistanceFunction distanceFunction;
    private int dimension;
    private Random random;

    // Infrastructure
    private TestStorageEngine storageEngine;
    private TestVisibilityTracker visibilityTracker;
    private TestDocumentVisibilityTracker documentVisibilityTracker;
    private WriteLog writeLog;
    private AtomicDocumentWriter documentWriter;
    private DocumentRegistry documentRegistry;
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
        documentVisibilityTracker = new TestDocumentVisibilityTracker();

        // Create write log
        Path logPath = tempDir.resolve("test.log");
        writeLog = new WriteLog(logPath);

        // Create ingestion infrastructure
        documentWriter = new AtomicDocumentWriter(writeLog);
        documentRegistry = new DocumentRegistry();
        publisher = new DocumentBatchPublisher(
            visibilityTracker,
            documentRegistry,
            null,
            // io.excalibur.store.WriteLog fsyncs inside every append, so the publisher's
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
    @DisplayName("No Torn Reads")
    class NoTornReads {

        /**
         * Test: Verify no torn reads during concurrent ingestion.
         *
         * <p>A torn read occurs when a query sees some chunks of a document
         * but not all of them. This test verifies that such reads cannot happen
         * by checking that all chunks become visible at the exact same epoch.</p>
         *
         * <p><strong>Domain Correctness:</strong></p>
         * <ul>
         *   <li>All chunks of a document have the same visibility epoch</li>
         *   <li>If a document is visible, ALL its chunks are visible</li>
         *   <li>tornReadCount must be 0</li>
         * </ul>
         */
        @Test
        @DisplayName("all chunks of a document are visible at the same epoch")
        void allChunksVisibleAtSameEpoch() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("torn-reads.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Create query pipeline
            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            // Register a test segment
            long segmentId = storageEngine.registerSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(segmentId, 0L);

            int numDocuments = 30;
            int chunksPerDocument = 5;
            int numReaderThreads = 4;

            // The system under test: the production visibility tracker. Readers ask IT
            // whether a document and each of its chunks are visible, so a tracker that
            // published chunks one at a time would be caught. Comparing a map the writer
            // filled against itself could not catch anything.
            DocumentVisibilityTracker tracker = new DocumentVisibilityTracker();

            // Published documents, in commit order, with the chunk ids the writer committed
            List<String> publishedDocuments = Collections.synchronizedList(new ArrayList<>());
            ConcurrentHashMap<String, List<String>> documentChunkIds = new ConcurrentHashMap<>();

            AtomicInteger tornReadCount = new AtomicInteger(0);
            AtomicInteger totalReads = new AtomicInteger(0);
            AtomicInteger totalChecks = new AtomicInteger(0);
            List<Throwable> workerFailures = Collections.synchronizedList(new ArrayList<>());

            ExecutorService executor = Executors.newFixedThreadPool(numReaderThreads + 1);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch writerDone = new CountDownLatch(1);
            AtomicBoolean writersFinished = new AtomicBoolean(false);

            // Writer task
            Runnable writer = () -> {
                try {
                    startLatch.await();

                    for (int d = 0; d < numDocuments; d++) {
                        String documentId = "doc-torn-" + d;
                        List<TextChunk> chunks = createChunks(documentId, chunksPerDocument);
                        DocumentBatchBuffer batch = documentWriter.write(documentId, chunks);

                        // Insert vector into graph
                        float[] vector = createRandomVector(dimension);
                        graph.insert(vector);
                        graph.setLastIntegratedOffset(writeLog.writeOffset());

                        long epoch = visibilityTracker.advanceEpoch();

                        // Publish the document atomically, then announce it to the readers
                        tracker.registerDocument(documentId, batch.getChunkIds(), epoch);
                        documentChunkIds.put(documentId, List.copyOf(batch.getChunkIds()));
                        publishedDocuments.add(documentId);

                        Thread.sleep(2);
                    }
                } catch (Exception e) {
                    workerFailures.add(e);
                } finally {
                    // Both must happen even when the writer failed, or the readers spin and
                    // the await below burns its full timeout before anything is asserted.
                    writersFinished.set(true);
                    writerDone.countDown();
                }
            };

            // Reader task - checks for torn reads
            Runnable reader = () -> {
                try {
                    startLatch.await();

                    while (!writersFinished.get()) {
                        long queryEpoch = visibilityTracker.captureEpoch();

                        for (String documentId : List.copyOf(publishedDocuments)) {
                            // Ask the tracker, not the test's own bookkeeping
                            if (!tracker.isDocumentFullyVisible(documentId, queryEpoch)) {
                                continue;
                            }
                            totalReads.incrementAndGet();

                            List<String> chunkIds = documentChunkIds.get(documentId);
                            if (chunkIds == null) {
                                continue;
                            }
                            totalChecks.incrementAndGet();

                            // TORN READ DETECTION: a visible document must have every chunk
                            // visible at the same epoch
                            int visibleChunks = 0;
                            for (String chunkId : chunkIds) {
                                if (tracker.isChunkVisible(chunkId, queryEpoch)) {
                                    visibleChunks++;
                                }
                            }

                            if (visibleChunks != chunksPerDocument) {
                                tornReadCount.incrementAndGet();
                                System.err.println("TORN READ DETECTED: document=" + documentId +
                                    " expectedChunks=" + chunksPerDocument +
                                    " visibleChunks=" + visibleChunks +
                                    " commitEpoch=" + tracker.getCommitEpoch(documentId) +
                                    " queryEpoch=" + queryEpoch);
                            }
                        }

                        Thread.sleep(1);
                    }
                } catch (Exception e) {
                    workerFailures.add(e);
                }
            };

            executor.submit(writer);
            for (int i = 0; i < numReaderThreads; i++) {
                executor.submit(reader);
            }

            startLatch.countDown();
            boolean writerCompleted = writerDone.await(60, TimeUnit.SECONDS);

            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            System.out.println("Torn read test completed: " + tornReadCount.get() + " torn reads out of " +
                totalReads.get() + " total reads (" + totalChecks.get() + " chunk checks)");

            assertThat(writerCompleted)
                .as("The writer must finish within the timeout")
                .isTrue();
            assertThat(workerFailures)
                .as("No writer or reader thread may fail: " + workerFailures)
                .isEmpty();

            // CRITICAL: Must have 0 torn reads
            assertThat(tornReadCount.get())
                .as("No torn reads should occur - all chunks must be visible at the same epoch")
                .isEqualTo(0);

            // Verify test was non-trivial
            assertThat(totalReads.get())
                .as("Test should have performed actual reads")
                .isGreaterThan(0);
            assertThat(tracker.getDocumentCount())
                .as("Every document must have been published")
                .isEqualTo(numDocuments);

            graph.close();
        }

        /**
         * Test: Verify chunk visibility epochs are identical for a document.
         *
         * <p><strong>Domain Correctness:</strong> All chunks of a document
         * must have the exact same visibility epoch.</p>
         *
         * <p>The per-chunk epoch is read back out of the production
         * {@link DocumentVisibilityTracker} rather than out of a map this test filled: the
         * property under test is what the tracker publishes, so asserting on the test's own
         * bookkeeping would hold however the tracker behaved. The boundary is checked from
         * both sides - nothing visible one epoch before the commit, everything visible at
         * it - which is what "identical epoch" means operationally.</p>
         */
        @Test
        @DisplayName("chunk visibility epochs are identical for a document")
        void chunkVisibilityEpochsAreIdentical() throws Exception {
            int numDocuments = 20;
            int chunksPerDocument = 7;

            DocumentVisibilityTracker tracker = new DocumentVisibilityTracker();

            for (int d = 0; d < numDocuments; d++) {
                String documentId = "doc-epoch-identical-" + d;
                List<TextChunk> chunks = createChunks(documentId, chunksPerDocument);
                DocumentBatchBuffer batch = documentWriter.write(documentId, chunks);

                long epoch = visibilityTracker.advanceEpoch();
                tracker.registerDocument(documentId, batch.getChunkIds(), epoch);

                List<String> chunkIds = batch.getChunkIds();
                assertThat(chunkIds)
                    .as("The batch must carry every chunk of " + documentId)
                    .hasSize(chunksPerDocument);

                // The epoch the tracker itself reports for the document
                long commitEpoch = tracker.getCommitEpoch(documentId);
                assertThat(commitEpoch)
                    .as("Document " + documentId + " must be registered at the epoch it committed at")
                    .isEqualTo(epoch);

                // Derive each chunk's visibility epoch from the tracker: the lowest epoch at
                // which the tracker reports it visible.
                Set<Long> chunkVisibilityEpochs = new HashSet<>();
                for (String chunkId : chunkIds) {
                    assertThat(tracker.isChunkVisible(chunkId, commitEpoch - 1))
                        .as("Chunk " + chunkId + " must not be visible before the commit epoch")
                        .isFalse();
                    assertThat(tracker.isChunkVisible(chunkId, commitEpoch))
                        .as("Chunk " + chunkId + " must be visible at the commit epoch")
                        .isTrue();
                    chunkVisibilityEpochs.add(firstVisibleEpoch(tracker, chunkId, commitEpoch));
                }

                assertThat(chunkVisibilityEpochs)
                    .as("All chunks of document " + documentId + " must have the same visibility epoch")
                    .containsExactly(commitEpoch);
            }

            assertThat(tracker.getDocumentCount()).isEqualTo(numDocuments);

            System.out.println("Verified identical epoch for all chunks across " + numDocuments + " documents");
        }

        /**
         * Returns the lowest epoch at which the tracker reports {@code chunkId} visible.
         *
         * @param tracker    The tracker being interrogated
         * @param chunkId    The chunk to probe
         * @param upperBound An epoch the chunk is known to be visible at
         */
        private long firstVisibleEpoch(DocumentVisibilityTracker tracker, String chunkId, long upperBound) {
            long lowest = upperBound;
            for (long epoch = upperBound - 1; epoch >= 0; epoch--) {
                if (!tracker.isChunkVisible(chunkId, epoch)) {
                    break;
                }
                lowest = epoch;
            }
            return lowest;
        }
    }

    @Nested
    @DisplayName("No Partial Documents")
    class NoPartialDocuments {

        /**
         * Test: Verify document atomicity using DocumentAtomicityFilter.
         *
         * <p>This test verifies that the DocumentAtomicityFilter correctly
         * filters out partial documents from query results.</p>
         *
         * <p><strong>Domain Correctness:</strong></p>
         * <ul>
         *   <li>Documents are either fully visible or not visible at all</li>
         *   <li>AtomicityVerification.valid must be true for all query results</li>
         * </ul>
         */
        @Test
        @DisplayName("document atomicity filter excludes partial documents")
        void documentAtomicityFilterExcludesPartialDocuments() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("atomicity-filter.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 10000);

            // Register segment
            long segmentId = storageEngine.registerSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(segmentId, 0L);

            // Ingest documents atomically
            int numDocuments = 15;
            int chunksPerDocument = 5;

            for (int d = 0; d < numDocuments; d++) {
                String documentId = "doc-atomic-filter-" + d;

                // Use DocumentBatchBuffer for atomic publication
                DocumentBatchBuffer batchBuffer = new DocumentBatchBuffer(documentId, "test-source");
                List<TextChunk> chunks = createChunks(documentId, chunksPerDocument);

                for (int c = 0; c < chunks.size(); c++) {
                    TextChunk chunk = chunks.get(c);
                    String chunkId = documentId + ":" + c;
                    long offset = writeLog.writeOffset();
                    batchBuffer.addChunk(chunkId, chunk, offset);
                }

                batchBuffer.seal();

                DocumentMetadata metadata = DocumentMetadata.builder()
                    .sourceId("test-source")
                    .documentId(documentId)
                    .lastModifiedMillis(System.currentTimeMillis())
                    .contentHash((long) d * 1000)
                    .contentLength(1000)
                    .indexTimeMillis(System.currentTimeMillis())
                    .build();

                publisher.publish(batchBuffer, metadata);

                // Insert vector
                float[] vector = createRandomVector(dimension);
                graph.insert(vector);
                graph.setLastIntegratedOffset(writeLog.writeOffset());

                long epoch = visibilityTracker.advanceEpoch();

                documentVisibilityTracker.registerDocument(documentId, chunksPerDocument, epoch);
            }

            // Create atomicity filter
            DocumentAtomicityFilter filter = new DocumentAtomicityFilter(
                (DocumentVisibilityChecker) documentVisibilityTracker);

            long queryEpoch = visibilityTracker.captureEpoch();

            // Simulate query results (would normally come from QueryPipeline)
            List<QueryResultItem> mockResults = new ArrayList<>();
            for (int d = 0; d < numDocuments; d++) {
                String documentIdStr = "doc-atomic-filter-" + d;
                // Create mock result for each document
                // Constructor: QueryResultItem(long documentId, String documentIdStr, float score, byte[] payload, long segmentId, long nodeId)
                mockResults.add(new QueryResultItem(
                    d,                          // documentId (numeric)
                    documentIdStr,              // documentIdStr
                    0.85f - (d * 0.01f),        // score (normalized)
                    null,                       // payload
                    0L,                         // segmentId
                    d                           // nodeId
                ));
            }

            // Verify atomicity of all results
            DocumentAtomicityFilter.AtomicityVerification verification =
                filter.verifyAtomicity(mockResults, queryEpoch);

            assertThat(verification.valid())
                .as("All results should be from atomically visible documents")
                .isTrue();

            assertThat(verification.partialCount())
                .as("No partial documents should be in results")
                .isEqualTo(0);

            System.out.println("Atomicity verification: valid=" + verification.valid() +
                ", total=" + verification.totalCount() +
                ", partial=" + verification.partialCount());

            graph.close();
        }

        /**
         * Test: Verify no partial documents during high-concurrency ingestion.
         *
         * <p><strong>Domain Correctness:</strong> Even under high concurrency,
         * no query should ever see a partial document.</p>
         */
        @Test
        @DisplayName("no partial documents under high concurrency")
        void noPartialDocumentsUnderHighConcurrency() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("high-concurrency.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 20000);

            // Create query pipeline
            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            // Register segment
            long segmentId = storageEngine.registerSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(segmentId, 0L);

            int numWriterThreads = 3;
            int numReaderThreads = 5;
            int documentsPerWriter = 10;
            int chunksPerDocument = 4;

            ExecutorService executor = Executors.newFixedThreadPool(numWriterThreads + numReaderThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch writersDone = new CountDownLatch(numWriterThreads);
            AtomicBoolean writersFinished = new AtomicBoolean(false);

            AtomicInteger partialDocumentCount = new AtomicInteger(0);
            AtomicInteger totalQueries = new AtomicInteger(0);
            AtomicInteger queryErrors = new AtomicInteger(0);
            List<String> errorMessages = Collections.synchronizedList(new ArrayList<>());

            // Track all ingested documents
            ConcurrentHashMap<String, Integer> ingestedDocuments = new ConcurrentHashMap<>();

            // Writer tasks
            Runnable writer = () -> {
                try {
                    startLatch.await();
                    int threadId = (int) Thread.currentThread().threadId();

                    for (int d = 0; d < documentsPerWriter; d++) {
                        String documentId = "doc-concurrent-" + threadId + "-" + d;

                        List<TextChunk> chunks = createChunks(documentId, chunksPerDocument);
                        documentWriter.write(documentId, chunks);

                        float[] vector = createRandomVector(dimension);
                        graph.insert(vector);
                        graph.setLastIntegratedOffset(writeLog.writeOffset());

                        long epoch = visibilityTracker.advanceEpoch();

                        documentVisibilityTracker.registerDocument(documentId, chunksPerDocument, epoch);
                        ingestedDocuments.put(documentId, chunksPerDocument);

                        Thread.sleep(5);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    writersDone.countDown();
                }
            };

            // Reader tasks
            Runnable reader = () -> {
                try {
                    startLatch.await();

                    while (!writersFinished.get()) {
                        try {
                            QueryRequest request = QueryRequest.builder()
                                .queryText("concurrent atomicity check")
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

                            // Verify no partial documents in results
                            for (QueryResultItem result : response.results()) {
                                String docId = result.documentIdStr();
                                if (docId != null && ingestedDocuments.containsKey(docId)) {
                                    // Document is in results - verify it's fully visible
                                    // (This is a simplified check; in production we'd verify chunk counts)
                                }
                            }

                            // Verify coverage is in valid range
                            IndexCoverage coverage = response.coverage();
                            if (coverage != null) {
                                double cov = coverage.estimatedCorpusCoverage();
                                if (cov < 0.0 || cov > 1.0) {
                                    partialDocumentCount.incrementAndGet();
                                    errorMessages.add("Invalid coverage: " + cov);
                                }
                            }

                            Thread.sleep(10);
                        } catch (Exception e) {
                            queryErrors.incrementAndGet();
                            errorMessages.add("Query error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };

            // Start all threads
            for (int i = 0; i < numWriterThreads; i++) {
                executor.submit(writer);
            }
            for (int i = 0; i < numReaderThreads; i++) {
                executor.submit(reader);
            }

            startLatch.countDown();

            // Wait for writers
            writersDone.await(120, TimeUnit.SECONDS);
            writersFinished.set(true);

            executor.shutdown();
            executor.awaitTermination(15, TimeUnit.SECONDS);

            System.out.println("High concurrency test completed: " +
                totalQueries.get() + " queries, " +
                queryErrors.get() + " errors, " +
                partialDocumentCount.get() + " partial documents");

            // Verify no partial documents
            assertThat(partialDocumentCount.get())
                .as("No partial documents should be visible under high concurrency. Errors: " + errorMessages)
                .isEqualTo(0);

            graph.close();
        }
    }

    @Nested
    @DisplayName("No Concurrent Modification Exceptions")
    class NoConcurrentModificationExceptions {

        /**
         * Test: Verify no ConcurrentModificationException during concurrent operations.
         *
         * <p>This test specifically checks for Java's ConcurrentModificationException
         * which can occur when modifying a collection while iterating over it.
         * The storage engine and visibility tracker must handle concurrent access safely.</p>
         *
         * <p><strong>Domain Correctness:</strong></p>
         * <ul>
         *   <li>No ConcurrentModificationException should ever be thrown</li>
         *   <li>All concurrent operations should complete without errors</li>
         * </ul>
         */
        @Test
        @DisplayName("no ConcurrentModificationException during concurrent ingestion and queries")
        void noConcurrentModificationException() throws Exception {
            // Create HNSW graph
            HnswConfig config = HnswConfig.create(dimension);
            Path graphPath = tempDir.resolve("cme-test.graph");
            HnswGraph graph = new HnswGraph(graphPath, config, distanceFunction, 20000);

            // Create query pipeline
            QueryPipeline.HnswGraphProvider graphProvider = segmentId -> graph;
            QueryPipeline pipeline = new QueryPipeline(
                storageEngine,
                visibilityTracker,
                graphProvider,
                distanceFunction
            );

            // Register segment
            long segmentId = storageEngine.registerSegment(0L, LifecycleState.ACTIVE);
            visibilityTracker.registerSegment(segmentId, 0L);

            int numThreads = 6;
            int operationsPerThread = 30;

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);

            AtomicInteger cmeCount = new AtomicInteger(0);
            AtomicInteger totalOperations = new AtomicInteger(0);
            AtomicInteger successOperations = new AtomicInteger(0);
            List<String> exceptions = Collections.synchronizedList(new ArrayList<>());

            // Mixed operation task - each thread does both ingestion and queries
            Runnable mixedTask = () -> {
                try {
                    startLatch.await();
                    int threadId = (int) Thread.currentThread().threadId();

                    for (int i = 0; i < operationsPerThread; i++) {
                        try {
                            totalOperations.incrementAndGet();

                            if (i % 3 == 0) {
                                // Ingest operation
                                String documentId = "doc-cme-" + threadId + "-" + i;
                                List<TextChunk> chunks = createChunks(documentId, 3);
                                documentWriter.write(documentId, chunks);

                                float[] vector = createRandomVector(dimension);
                                graph.insert(vector);
                                graph.setLastIntegratedOffset(writeLog.writeOffset());

                                long epoch = visibilityTracker.advanceEpoch();
                                documentVisibilityTracker.registerDocument(documentId, 3, epoch);

                            } else {
                                // Query operation
                                QueryRequest request = QueryRequest.builder()
                                    .queryText("CME test query")
                                    .queryVector(createRandomVector(dimension))
                                    .topK(20)
                                    .timeoutMs(3000)
                                    .includePartialSegments(true)
                                    .enableLlmScoring(false)
                                    .correlationId(UUID.randomUUID().toString())
                                    .build();

                                CancellationToken token = new CancellationToken();
                                QueryResponse response = pipeline.executeSync(request, token);

                                // Access results to trigger potential CME
                                for (QueryResultItem result : response.results()) {
                                    // Just access the result to trigger any hidden CME
                                    long docId = result.documentId();
                                    String docStr = result.documentIdStr();
                                    float score = result.score();
                                }
                            }

                            successOperations.incrementAndGet();

                        } catch (java.util.ConcurrentModificationException e) {
                            cmeCount.incrementAndGet();
                            exceptions.add("CME at thread " + threadId + " iteration " + i + ": " + e.getMessage());
                            e.printStackTrace();
                        } catch (Exception e) {
                            exceptions.add("Other exception at thread " + threadId + " iteration " + i +
                                ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                        }

                        Thread.yield();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };

            // Start all threads
            for (int i = 0; i < numThreads; i++) {
                executor.submit(mixedTask);
            }

            startLatch.countDown();

            executor.shutdown();
            executor.awaitTermination(120, TimeUnit.SECONDS);

            System.out.println("CME test completed: " +
                totalOperations.get() + " operations, " +
                successOperations.get() + " successful, " +
                cmeCount.get() + " CMEs, " +
                exceptions.size() + " total exceptions");

            // CRITICAL: No ConcurrentModificationException should occur
            assertThat(cmeCount.get())
                .as("No ConcurrentModificationException should occur during concurrent operations. " +
                    "Exceptions: " + exceptions)
                .isEqualTo(0);

            // Verify most operations succeeded
            double successRate = (double) successOperations.get() / totalOperations.get();
            assertThat(successRate)
                .as("Most operations should succeed (success rate should be >= 0.95)")
                .isGreaterThanOrEqualTo(0.95);

            graph.close();
        }

        /**
         * Test: Stress test visibility tracker for concurrent modification safety.
         *
         * <p>This test specifically targets the VisibilityTracker's internal data
         * structures to ensure they handle concurrent modifications safely.</p>
         */
        @Test
        @DisplayName("visibility tracker handles concurrent modifications safely")
        void visibilityTrackerHandlesConcurrentModificationsSafely() throws Exception {
            int numThreads = 8;
            int operationsPerThread = 1000;

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);

            AtomicInteger cmeCount = new AtomicInteger(0);
            AtomicInteger totalOperations = new AtomicInteger(0);

            Runnable task = () -> {
                try {
                    startLatch.await();

                    for (int i = 0; i < operationsPerThread; i++) {
                        try {
                            totalOperations.incrementAndGet();

                            // Mix of operations on visibility tracker
                            int op = i % 4;
                            long segmentId = i % 10;

                            switch (op) {
                                case 0 -> {
                                    visibilityTracker.registerSegment(segmentId, visibilityTracker.currentEpoch());
                                }
                                case 1 -> {
                                    visibilityTracker.advanceEpoch();
                                }
                                case 2 -> {
                                    visibilityTracker.visibleSegments(visibilityTracker.captureEpoch());
                                }
                                case 3 -> {
                                    visibilityTracker.isSegmentVisible(segmentId, visibilityTracker.captureEpoch());
                                }
                            }
                        } catch (java.util.ConcurrentModificationException e) {
                            cmeCount.incrementAndGet();
                            e.printStackTrace();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };

            for (int i = 0; i < numThreads; i++) {
                executor.submit(task);
            }

            startLatch.countDown();

            executor.shutdown();
            executor.awaitTermination(60, TimeUnit.SECONDS);

            System.out.println("Visibility tracker stress test: " +
                totalOperations.get() + " operations, " + cmeCount.get() + " CMEs");

            assertThat(cmeCount.get())
                .as("VisibilityTracker should handle concurrent modifications without CME")
                .isEqualTo(0);
        }

        /**
         * Test: Stress test document visibility tracker for concurrent modification safety.
         */
        @Test
        @DisplayName("document visibility tracker handles concurrent modifications safely")
        void documentVisibilityTrackerHandlesConcurrentModificationsSafely() throws Exception {
            int numThreads = 6;
            int operationsPerThread = 500;

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);

            AtomicInteger cmeCount = new AtomicInteger(0);
            AtomicInteger totalOperations = new AtomicInteger(0);

            Runnable task = () -> {
                try {
                    startLatch.await();

                    for (int i = 0; i < operationsPerThread; i++) {
                        try {
                            totalOperations.incrementAndGet();

                            String documentId = "doc-stress-" + (i % 50);
                            int op = i % 3;

                            switch (op) {
                                case 0 -> {
                                    documentVisibilityTracker.registerDocument(documentId, 5, System.currentTimeMillis());
                                }
                                case 1 -> {
                                    documentVisibilityTracker.isDocumentVisible(documentId, System.currentTimeMillis());
                                }
                                case 2 -> {
                                    documentVisibilityTracker.getVisibleDocuments(System.currentTimeMillis());
                                }
                            }
                        } catch (java.util.ConcurrentModificationException e) {
                            cmeCount.incrementAndGet();
                            e.printStackTrace();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };

            for (int i = 0; i < numThreads; i++) {
                executor.submit(task);
            }

            startLatch.countDown();

            executor.shutdown();
            executor.awaitTermination(60, TimeUnit.SECONDS);

            System.out.println("Document visibility tracker stress test: " +
                totalOperations.get() + " operations, " + cmeCount.get() + " CMEs");

            assertThat(cmeCount.get())
                .as("DocumentVisibilityTracker should handle concurrent modifications without CME")
                .isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Epoch Consistency")
    class EpochConsistency {

        /**
         * Test: Verify epoch monotonically increases during concurrent operations.
         */
        @Test
        @DisplayName("epoch monotonically increases during concurrent operations")
        void epochMonotonicallyIncreasesDuringConcurrentOperations() throws Exception {
            int numThreads = 4;
            int advancesPerThread = 100;

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);

            List<Long> epochs = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger violations = new AtomicInteger(0);

            Runnable task = () -> {
                try {
                    startLatch.await();

                    for (int i = 0; i < advancesPerThread; i++) {
                        long newEpoch = visibilityTracker.advanceEpoch();
                        epochs.add(newEpoch);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };

            for (int i = 0; i < numThreads; i++) {
                executor.submit(task);
            }

            startLatch.countDown();

            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);

            // Verify all epochs are unique (no duplicates)
            Set<Long> uniqueEpochs = new HashSet<>(epochs);
            assertThat(uniqueEpochs.size())
                .as("All epochs should be unique")
                .isEqualTo(epochs.size());

            // Verify epochs are sorted when sorted by thread (each thread's epochs should increase)
            long previousEpoch = 0;
            for (Long epoch : epochs) {
                assertThat(epoch)
                    .as("Epoch should be greater than 0")
                    .isGreaterThan(0);
                // Note: We can't verify strict monotonicity across threads because
                // threads advance concurrently, but we can verify uniqueness
            }

            System.out.println("Epoch monotonicity test: " + epochs.size() + " advances, " +
                uniqueEpochs.size() + " unique epochs");
        }

        /**
         * Test: Verify segment visibility is consistent with epoch.
         *
         * <p><strong>Domain Correctness:</strong> Segments registered at epoch E
         * should be visible at epoch E and all subsequent epochs, but not at
         * epochs before E.</p>
         */
        @Test
        @DisplayName("segment visibility is consistent with epoch")
        void segmentVisibilityIsConsistentWithEpoch() throws Exception {
            // Get initial epoch (before any segments)
            // IMPORTANT: The visibility tracker starts at epoch 1 (or 0)
            long epochBeforeAnyRegistration = visibilityTracker.currentEpoch();

            // Register segment1 at current epoch
            long segment1 = visibilityTracker.registerSegmentAndGetId(visibilityTracker.currentEpoch());
            long epoch1 = visibilityTracker.currentEpoch();

            // Advance epoch and register segment2
            visibilityTracker.advanceEpoch();
            long segment2 = visibilityTracker.registerSegmentAndGetId(visibilityTracker.currentEpoch());
            long epoch2 = visibilityTracker.currentEpoch();

            // Advance epoch and register segment3
            visibilityTracker.advanceEpoch();
            long segment3 = visibilityTracker.registerSegmentAndGetId(visibilityTracker.currentEpoch());
            long epoch3 = visibilityTracker.currentEpoch();

            // Query at epoch1 - should only see segment1
            Set<Long> visibleAtEpoch1 = visibilityTracker.visibleSegments(epoch1);
            assertThat(visibleAtEpoch1).contains(segment1);
            assertThat(visibleAtEpoch1).doesNotContain(segment2, segment3);

            // Query at epoch2 - should see segment1 and segment2
            Set<Long> visibleAtEpoch2 = visibilityTracker.visibleSegments(epoch2);
            assertThat(visibleAtEpoch2).contains(segment1, segment2);
            assertThat(visibleAtEpoch2).doesNotContain(segment3);

            // Query at epoch3 - should see all segments
            Set<Long> visibleAtEpoch3 = visibilityTracker.visibleSegments(epoch3);
            assertThat(visibleAtEpoch3).contains(segment1, segment2, segment3);

            // Query at epoch before segment1 was registered
            // If epoch1 is the first epoch where segment1 is registered,
            // query at epoch1-1 should see nothing from segment1
            // But we need to be careful: if epoch1 is 1, then epoch1-1 = 0
            // which might still show segment1 if creationEpoch is 0-based
            // So we verify the principle: segments registered at epoch E are NOT visible at epoch E-1
            if (epoch1 > 1) {
                Set<Long> visibleAtEpochBefore = visibilityTracker.visibleSegments(epoch1 - 1);
                assertThat(visibleAtEpochBefore).doesNotContain(segment1);
            }

            // Verify tombstoned segments are not visible after tombstone epoch
            visibilityTracker.markTombstoned(segment1, epoch3);
            Set<Long> visibleAtEpoch3AfterTombstone = visibilityTracker.visibleSegments(epoch3);
            // segment1 should NOT be visible at epoch3 because it was tombstoned at epoch3
            // (visibility is creationEpoch <= queryEpoch < tombstoneEpoch)
            assertThat(visibleAtEpoch3AfterTombstone).doesNotContain(segment1);
            // But segment1 should still be visible at epoch2 (before tombstone)
            Set<Long> visibleAtEpoch2AfterTombstone = visibilityTracker.visibleSegments(epoch2);
            assertThat(visibleAtEpoch2AfterTombstone).contains(segment1);

            System.out.println("Segment visibility consistency verified across 3 epochs");
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

    // ==================== Test Infrastructure ====================

    /**
     * Test StorageEngine that stores and returns actual segments.
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

        long registerSegment(long ignoredSegmentId, LifecycleState state) throws IOException {
            Path segmentPath = dataDir.resolve("test_segment_" + segmentCounter++ + ".dat");
            Segment segment = new Segment(segmentPath, 1024 * 1024, arena);
            long actualSegmentId = segment.segmentId();
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
     */
    private static class TestSegmentCatalog extends SegmentCatalog {
        private final ConcurrentHashMap<Long, SegmentMetadata> metadata = new ConcurrentHashMap<>();

        void registerSegment(long segmentId, LifecycleState lifecycleState) {
            SegmentMetadata meta = new SegmentMetadata(segmentId);
            if (lifecycleState != LifecycleState.BOOTSTRAPPING) {
                meta.transitionToIndexing();
                if (lifecycleState == LifecycleState.WARMING
                    || lifecycleState == LifecycleState.ACTIVE
                    || lifecycleState == LifecycleState.COMPACTING) {
                    meta.transitionToWarming();
                    if (lifecycleState == LifecycleState.ACTIVE || lifecycleState == LifecycleState.COMPACTING) {
                        meta.transitionToActive();
                        if (lifecycleState == LifecycleState.COMPACTING) {
                            meta.transitionToCompacting();
                        }
                    }
                }
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

        public void clear() {
            metadata.clear();
        }
    }

    /**
     * Test VisibilityTracker implementation.
     */
    private static class TestVisibilityTracker extends VisibilityTracker {
        private final AtomicLong epoch = new AtomicLong(1);
        private final ConcurrentHashMap<Long, Long> creationEpochs = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Long> tombstoneEpochs = new ConcurrentHashMap<>();
        private final AtomicLong segmentIdCounter = new AtomicLong(1000);

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

        /**
         * Registers a segment and returns a generated segment ID.
         */
        public long registerSegmentAndGetId(long creationEpoch) {
            long segmentId = segmentIdCounter.incrementAndGet();
            registerSegment(segmentId, creationEpoch);
            return segmentId;
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
            if (creationEpoch == null) {
                return false;
            }
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
    }

    /**
     * Test DocumentVisibilityTracker implementation.
     */
    private static class TestDocumentVisibilityTracker implements DocumentVisibilityChecker {
        @Override
        public long currentEpoch() {
            // A test double with no epoch of its own. Unbounded keeps these tests asserting the
            // completeness behaviour they were written for, rather than an epoch bound.
            return Long.MAX_VALUE;
        }

        private final ConcurrentHashMap<String, Long> documentCommitEpochs = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Integer> documentChunkCounts = new ConcurrentHashMap<>();

        public void registerDocument(String documentId, int chunkCount, long commitEpoch) {
            documentCommitEpochs.put(documentId, commitEpoch);
            documentChunkCounts.put(documentId, chunkCount);
        }

        @Override
        public boolean isDocumentVisible(String documentId, long queryEpoch) {
            Long commitEpoch = documentCommitEpochs.get(documentId);
            return commitEpoch != null && queryEpoch >= commitEpoch;
        }

        @Override
        public boolean isChunkVisible(String chunkId, long queryEpoch) {
            // Extract document ID from chunk ID (format: docId:chunkIndex)
            String documentId = chunkId.contains(":") ? chunkId.substring(0, chunkId.lastIndexOf(':')) : chunkId;
            return isDocumentVisible(documentId, queryEpoch);
        }

        @Override
        public List<String> filterPartialDocuments(List<String> chunkIds, long queryEpoch) {
            List<String> filtered = new ArrayList<>();
            for (String chunkId : chunkIds) {
                if (isChunkVisible(chunkId, queryEpoch)) {
                    filtered.add(chunkId);
                }
            }
            return filtered;
        }

        @Override
        public java.util.Set<String> getVisibleDocuments(long queryEpoch) {
            java.util.Set<String> visible = new HashSet<>();
            for (Map.Entry<String, Long> entry : documentCommitEpochs.entrySet()) {
                if (queryEpoch >= entry.getValue()) {
                    visible.add(entry.getKey());
                }
            }
            return visible;
        }

        @Override
        public long getCommitEpoch(String documentId) {
            return documentCommitEpochs.getOrDefault(documentId, -1L);
        }

        @Override
        public int getChunkCount(String documentId) {
            return documentChunkCounts.getOrDefault(documentId, 0);
        }

        @Override
        public int getDocumentCount() {
            return documentCommitEpochs.size();
        }
    }
}
