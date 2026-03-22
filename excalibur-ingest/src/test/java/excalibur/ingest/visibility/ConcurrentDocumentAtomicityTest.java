package excalibur.ingest.visibility;

import excalibur.ingest.buffer.AtomicDocumentBatch;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for document atomicity guarantee under concurrent load.
 *
 * <h2>Document Atomicity Contract</h2>
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Queries never see partially-ingested documents</li>
 *   <li>All chunks of a document become visible at the same epoch</li>
 *   <li>Epoch isolation is maintained across concurrent operations</li>
 *   <li>No torn reads occur during concurrent ingestion and queries</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Epochs are monotonically increasing</li>
 *   <li>A document is either fully visible or not visible at all</li>
 *   <li>Visibility is consistent across all chunks of a document</li>
 *   <li>Concurrent queries see consistent snapshots</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
class ConcurrentDocumentAtomicityTest {

    private DocumentVisibilityTracker tracker;
    private DocumentVisibilityRegistry registry;
    private AtomicLong epochCounter;

    @BeforeEach
    void setUp() {
        epochCounter = new AtomicLong(0);
        DocumentRegistry docRegistry = new DocumentRegistry();
        tracker = new DocumentVisibilityTracker(docRegistry);
        registry = new DocumentVisibilityRegistry();
    }

    @Test
    void testConcurrentIngestionQueriesNeverSeePartialDocuments() throws Exception {
        int numWriters = 4;
        int numReaders = 8;
        int docsPerWriter = 25;
        int chunksPerDoc = 5;

        ConcurrentHashMap<String, Long> documentCommitEpochs = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Boolean> seenPartial = new ConcurrentHashMap<>();
        AtomicInteger queriesRun = new AtomicInteger(0);
        AtomicInteger partialFound = new AtomicInteger(0);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(numWriters);
        CountDownLatch readersDone = new CountDownLatch(numReaders);

        ExecutorService executor = Executors.newFixedThreadPool(numWriters + numReaders);

        for (int w = 0; w < numWriters; w++) {
            final int writerId = w;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int d = 0; d < docsPerWriter; d++) {
                        String docId = "writer" + writerId + "_doc" + d;
                        List<String> chunkIds = new ArrayList<>();
                        for (int c = 0; c < chunksPerDoc; c++) {
                            chunkIds.add(docId + ":chunk" + c);
                        }

                        AtomicDocumentBatch batch = new AtomicDocumentBatch(
                            docId,
                            100L + writerId * 10000L + d * 100L,
                            200L + writerId * 10000L + d * 100L,
                            chunkIds,
                            Collections.nCopies(chunkIds.size(), 1L)
                        );

                        Thread.sleep(1);

                        long commitEpoch = epochCounter.incrementAndGet();
                        tracker.registerAtomicDocument(batch, commitEpoch);
                        registry.registerDocument(docId, new HashSet<>(chunkIds), commitEpoch);
                        documentCommitEpochs.put(docId, commitEpoch);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    writersDone.countDown();
                }
            });
        }

        for (int r = 0; r < numReaders; r++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (writersDone.getCount() > 0 || queriesRun.get() < 100) {
                        long queryEpoch = epochCounter.get();
                        queriesRun.incrementAndGet();

                        Set<String> allChunks = new HashSet<>();
                        for (String docId : documentCommitEpochs.keySet()) {
                            for (int c = 0; c < chunksPerDoc; c++) {
                                allChunks.add(docId + ":chunk" + c);
                            }
                        }

                        List<String> visibleChunks = tracker.filterPartialDocuments(
                            new ArrayList<>(allChunks), queryEpoch);

                        for (String chunkId : visibleChunks) {
                            String docId = extractDocumentId(chunkId);
                            Long commitEpoch = documentCommitEpochs.get(docId);

                            if (commitEpoch != null && commitEpoch > queryEpoch) {
                                seenPartial.put(chunkId, true);
                                partialFound.incrementAndGet();
                            }
                        }

                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    readersDone.countDown();
                }
            });
        }

        startLatch.countDown();

        assertTrue(writersDone.await(30, TimeUnit.SECONDS), "Writers should complete");
        assertTrue(readersDone.await(30, TimeUnit.SECONDS), "Readers should complete");

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(0, partialFound.get(), 
            "No partial documents should be visible. Found partial chunks: " + seenPartial.keySet());
        assertTrue(queriesRun.get() > 0, "Queries should have run");
    }

    @Test
    void testEpochIsolationConcurrentQueries() throws Exception {
        int numQueries = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numQueries);
        AtomicInteger violations = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(numQueries);

        AtomicDocumentBatch batch1 = createBatch("doc1", 0, 100, "doc1:0", "doc1:1");
        AtomicDocumentBatch batch2 = createBatch("doc2", 100, 200, "doc2:0", "doc2:1");
        AtomicDocumentBatch batch3 = createBatch("doc3", 200, 300, "doc3:0", "doc3:1");

        tracker.registerAtomicDocument(batch1, 1L);
        tracker.registerAtomicDocument(batch2, 2L);

        for (int i = 0; i < numQueries; i++) {
            final int queryId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    long epoch1 = 1L;
                    Set<String> visibleAtEpoch1 = tracker.getVisibleDocuments(epoch1);
                    if (!visibleAtEpoch1.contains("doc1")) {
                        violations.incrementAndGet();
                    }
                    if (visibleAtEpoch1.contains("doc2")) {
                        violations.incrementAndGet();
                    }
                    if (visibleAtEpoch1.contains("doc3")) {
                        violations.incrementAndGet();
                    }

                    long epoch2 = 2L;
                    Set<String> visibleAtEpoch2 = tracker.getVisibleDocuments(epoch2);
                    if (!visibleAtEpoch2.contains("doc1")) {
                        violations.incrementAndGet();
                    }
                    if (!visibleAtEpoch2.contains("doc2")) {
                        violations.incrementAndGet();
                    }
                    if (visibleAtEpoch2.contains("doc3")) {
                        violations.incrementAndGet();
                    }

                    tracker.registerAtomicDocument(batch3, 3L);

                    Set<String> visibleAtEpoch1After = tracker.getVisibleDocuments(epoch1);
                    if (visibleAtEpoch1After.contains("doc3")) {
                        violations.incrementAndGet();
                    }

                    doneLatch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startLatch.countDown();

        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Queries should complete");
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(0, violations.get(), 
            "Epoch isolation violations detected");
    }

    @Test
    void testDocumentAtomicityAllChunksOrNone() {
        String docId = "atomicDoc";
        int numChunks = 10;
        List<String> chunkIds = new ArrayList<>();
        for (int i = 0; i < numChunks; i++) {
            chunkIds.add(docId + ":chunk" + i);
        }

        AtomicDocumentBatch batch = new AtomicDocumentBatch(
            docId, 0L, 1000L, chunkIds, Collections.nCopies(numChunks, 1L)
        );

        long commitEpoch = 5L;
        tracker.registerAtomicDocument(batch, commitEpoch);

        for (int i = 0; i < numChunks; i++) {
            String chunkId = docId + ":chunk" + i;
            assertTrue(tracker.isChunkVisible(chunkId, commitEpoch),
                "Chunk " + chunkId + " should be visible at commit epoch");
            assertTrue(tracker.isChunkVisible(chunkId, commitEpoch + 1),
                "Chunk " + chunkId + " should be visible after commit epoch");
            assertFalse(tracker.isChunkVisible(chunkId, commitEpoch - 1),
                "Chunk " + chunkId + " should not be visible before commit epoch");
        }

        List<String> allChunks = new ArrayList<>(chunkIds);
        List<String> visibleAtCommit = tracker.filterPartialDocuments(allChunks, commitEpoch);
        assertEquals(numChunks, visibleAtCommit.size(), "All chunks should be visible at commit epoch");

        List<String> visibleBeforeCommit = tracker.filterPartialDocuments(allChunks, commitEpoch - 1);
        assertEquals(0, visibleBeforeCommit.size(), "No chunks should be visible before commit epoch");
    }

    @RepeatedTest(5)
    void testNoTornReadsDuringConcurrentRegistration() throws Exception {
        int numDocuments = 100;
        int chunksPerDoc = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numDocuments);
        AtomicInteger tornReads = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        for (int d = 0; d < numDocuments; d++) {
            final int docIndex = d;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    String docId = "concurrentDoc" + docIndex;
                    List<String> chunkIds = new ArrayList<>();
                    for (int c = 0; c < chunksPerDoc; c++) {
                        chunkIds.add(docId + ":chunk" + c);
                    }

                    AtomicDocumentBatch batch = new AtomicDocumentBatch(
                        docId,
                        docIndex * 1000L,
                        docIndex * 1000L + 500L,
                        chunkIds,
                        Collections.nCopies(chunkIds.size(), 1L)
                    );

                    long commitEpoch = tracker.advanceEpoch();
                    tracker.registerAtomicDocument(batch, commitEpoch);

                    boolean allVisible = true;
                    boolean noneVisible = true;
                    for (String chunkId : chunkIds) {
                        boolean visible = tracker.isChunkVisible(chunkId, commitEpoch);
                        allVisible = allVisible && visible;
                        noneVisible = noneVisible && !visible;
                    }

                    if (!allVisible && !noneVisible) {
                        tornReads.incrementAndGet();
                    }

                    doneLatch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startLatch.countDown();

        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All documents should be registered");
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(0, tornReads.get(), "No torn reads should occur during concurrent registration");
    }

    @Test
    void testVisibilityRegistryAtomicRegistration() throws Exception {
        int numDocuments = 50;
        int chunksPerDoc = 3;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numDocuments);
        AtomicInteger violations = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        AtomicLong epochCounter = new AtomicLong(0);

        for (int d = 0; d < numDocuments; d++) {
            final int docIndex = d;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    String docId = "registryDoc" + docIndex;
                    Set<String> chunkIds = new HashSet<>();
                    for (int c = 0; c < chunksPerDoc; c++) {
                        chunkIds.add(docId + ":chunk" + c);
                    }

                    long commitEpoch = epochCounter.incrementAndGet();
                    registry.registerDocument(docId, chunkIds, commitEpoch);

                    for (String chunkId : chunkIds) {
                        boolean visibleAtCommit = registry.isChunkVisibleAt(chunkId, commitEpoch);
                        boolean visibleAfter = registry.isChunkVisibleAt(chunkId, commitEpoch + 1000);
                        boolean visibleBefore = registry.isChunkVisibleAt(chunkId, commitEpoch - 1);

                        if (!visibleAtCommit || !visibleAfter || visibleBefore) {
                            violations.incrementAndGet();
                        }
                    }

                    doneLatch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startLatch.countDown();

        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All documents should be registered");
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(0, violations.get(), 
            "Visibility registry should maintain atomic visibility for all chunks");
    }

    @Test
    void testConcurrentQueriesWithLiveIngestion() throws Exception {
        int numIngestionThreads = 4;
        int numQueryThreads = 8;
        int docsPerIngestion = 20;
        int chunksPerDoc = 4;

        ConcurrentHashMap<String, Long> documentEpochs = new ConcurrentHashMap<>();
        AtomicInteger queryCount = new AtomicInteger(0);
        AtomicInteger partialDocumentCount = new AtomicInteger(0);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch ingestionDone = new CountDownLatch(numIngestionThreads);

        ExecutorService executor = Executors.newFixedThreadPool(numIngestionThreads + numQueryThreads);

        for (int t = 0; t < numIngestionThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int d = 0; d < docsPerIngestion; d++) {
                        String docId = "live_doc_" + threadId + "_" + d;
                        Set<String> chunkIds = new HashSet<>();
                        for (int c = 0; c < chunksPerDoc; c++) {
                            chunkIds.add(docId + ":chunk" + c);
                        }

                        AtomicDocumentBatch batch = new AtomicDocumentBatch(
                            docId,
                            System.nanoTime(),
                            System.nanoTime() + 1000,
                            new ArrayList<>(chunkIds),
                            Collections.nCopies(chunkIds.size(), 1L)
                        );

                        long epoch = tracker.advanceEpoch();
                        tracker.registerAtomicDocument(batch, epoch);
                        registry.registerDocument(docId, chunkIds, epoch);
                        documentEpochs.put(docId, epoch);

                        Thread.sleep(2);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    ingestionDone.countDown();
                }
            });
        }

        Set<String> observedPartialDocs = ConcurrentHashMap.newKeySet();

        for (int t = 0; t < numQueryThreads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (!ingestionDone.await(10, TimeUnit.MILLISECONDS)) {
                        long queryEpoch = tracker.currentEpoch();
                        queryCount.incrementAndGet();

                        for (String docId : documentEpochs.keySet()) {
                            Long commitEpoch = documentEpochs.get(docId);
                            if (commitEpoch == null) continue;

                            Set<String> docChunks = registry.getChunksForDocument(docId);
                            int visibleCount = 0;
                            for (String chunkId : docChunks) {
                                if (registry.isChunkVisibleAt(chunkId, queryEpoch)) {
                                    visibleCount++;
                                }
                            }

                            if (commitEpoch <= queryEpoch) {
                                if (visibleCount != docChunks.size()) {
                                    observedPartialDocs.add(docId);
                                    partialDocumentCount.incrementAndGet();
                                }
                            } else {
                                if (visibleCount > 0) {
                                    observedPartialDocs.add(docId);
                                    partialDocumentCount.incrementAndGet();
                                }
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startLatch.countDown();

        assertTrue(ingestionDone.await(60, TimeUnit.SECONDS), "Ingestion should complete");
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(0, partialDocumentCount.get(), 
            "No partial documents should be observed. Partial docs: " + observedPartialDocs);
        assertTrue(queryCount.get() > 0, "Queries should have run during ingestion");
    }

    @Test
    void testDocumentReplacementAtomicity() throws Exception {
        String docId = "replacedDoc";

        Set<String> oldChunks = new HashSet<>(Arrays.asList(docId + ":old0", docId + ":old1"));
        registry.registerDocument(docId, oldChunks, 1L);

        assertTrue(registry.isVisibleAt(docId, 1L));
        assertEquals(2, registry.getChunksForDocument(docId).size());

        Set<String> newChunks = new HashSet<>(Arrays.asList(
            docId + ":new0", docId + ":new1", docId + ":new2"
        ));
        registry.replaceDocument(docId, newChunks, 5L);

        assertFalse(registry.isVisibleAt(docId, 3L), 
            "Document should not be visible between replacement epochs");
        assertTrue(registry.isVisibleAt(docId, 5L), 
            "Document should be visible at replacement epoch");
        assertEquals(3, registry.getChunksForDocument(docId).size());

        for (String oldChunk : oldChunks) {
            assertNull(registry.getDocumentForChunk(oldChunk),
                "Old chunk should not be registered: " + oldChunk);
        }
        for (String newChunk : newChunks) {
            assertEquals(docId, registry.getDocumentForChunk(newChunk),
                "New chunk should be registered: " + newChunk);
        }
    }

    @Test
    void testTombstoneAtomicity() throws Exception {
        String docId = "tombstonedDoc";
        Set<String> chunkIds = new HashSet<>(Arrays.asList(docId + ":0", docId + ":1", docId + ":2"));

        registry.registerDocument(docId, chunkIds, 1L);

        assertTrue(registry.isVisibleAt(docId, 5L));
        for (String chunkId : chunkIds) {
            assertTrue(registry.isChunkVisibleAt(chunkId, 5L));
        }

        boolean removed = registry.tombstoneDocument(docId);
        assertTrue(removed);

        assertFalse(registry.isVisibleAt(docId, 5L));
        for (String chunkId : chunkIds) {
            assertFalse(registry.isChunkVisibleAt(chunkId, 5L));
        }

        assertEquals(0, registry.getDocumentCount());
    }

    private AtomicDocumentBatch createBatch(String documentId, long startOffset, long endOffset, String... chunkIds) {
        return new AtomicDocumentBatch(
            documentId,
            startOffset,
            endOffset,
            Arrays.asList(chunkIds),
            Collections.nCopies(chunkIds.length, 1L)
        );
    }

    private String extractDocumentId(String chunkId) {
        int lastColon = chunkId.lastIndexOf(':');
        if (lastColon > 0) {
            return chunkId.substring(0, lastColon);
        }
        return chunkId;
    }
}
