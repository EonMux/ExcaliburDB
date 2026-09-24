package com.eonmux.excalibur.ingest.visibility;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;

import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
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
    private AtomicLong epochCounter;

    @BeforeEach
    void setUp() {
        epochCounter = new AtomicLong(0);
        DocumentRegistry docRegistry = new DocumentRegistry();
        tracker = new DocumentVisibilityTracker(docRegistry);
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
                            chunkIds.add(docId + ":chunk:" + c);
                        }

                        Thread.sleep(1);

                        long commitEpoch = epochCounter.incrementAndGet();
                        tracker.registerDocument(docId, chunkIds, commitEpoch);
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
                                allChunks.add(docId + ":chunk:" + c);
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

        List<String> doc3Chunks = List.of("doc3:0", "doc3:1");

        tracker.registerDocument("doc1", List.of("doc1:0", "doc1:1"), 1L);
        tracker.registerDocument("doc2", List.of("doc2:0", "doc2:1"), 2L);

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

                    tracker.registerDocument("doc3", doc3Chunks, 3L);

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
            chunkIds.add(docId + ":chunk:" + i);
        }

        long commitEpoch = 5L;
        tracker.registerDocument(docId, chunkIds, commitEpoch);

        for (int i = 0; i < numChunks; i++) {
            String chunkId = docId + ":chunk:" + i;
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
                        chunkIds.add(docId + ":chunk:" + c);
                    }

                    long commitEpoch = tracker.advanceEpoch();
                    tracker.registerDocument(docId, chunkIds, commitEpoch);

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
    void testTrackerAtomicRegistration() throws Exception {
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

                    String docId = "trackedDoc" + docIndex;
                    Set<String> chunkIds = new HashSet<>();
                    for (int c = 0; c < chunksPerDoc; c++) {
                        chunkIds.add(docId + ":chunk:" + c);
                    }

                    long commitEpoch = epochCounter.incrementAndGet();
                    tracker.registerDocument(docId, chunkIds, commitEpoch);

                    for (String chunkId : chunkIds) {
                        boolean visibleAtCommit = tracker.isChunkVisible(chunkId, commitEpoch);
                        boolean visibleAfter = tracker.isChunkVisible(chunkId, commitEpoch + 1000);
                        boolean visibleBefore = tracker.isChunkVisible(chunkId, commitEpoch - 1);

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
            "Visibility tracking should maintain atomic visibility for all chunks");
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
                            chunkIds.add(docId + ":chunk:" + c);
                        }

                        long epoch = tracker.advanceEpoch();
                        tracker.registerDocument(docId, chunkIds, epoch);
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

                            DocumentVisibilityTracker.DocumentVisibility tracked =
                                tracker.getVisibility(docId);
                            if (tracked == null) continue;
                            Set<String> docChunks = tracked.chunkIds();
                            int visibleCount = 0;
                            for (String chunkId : docChunks) {
                                if (tracker.isChunkVisible(chunkId, queryEpoch)) {
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
    void testDocumentReplacementAtomicity() {
        String docId = "replacedDoc";

        Set<String> oldChunks = new HashSet<>(Arrays.asList(
            docId + ":chunk:0", docId + ":chunk:1"));
        tracker.registerDocument(docId, oldChunks, 1L);

        assertTrue(tracker.isDocumentFullyVisible(docId, 1L));
        assertEquals(2, tracker.getChunkCount(docId));

        Set<String> newChunks = new HashSet<>(Arrays.asList(
            docId + ":chunk:0", docId + ":chunk:1", docId + ":chunk:2"
        ));
        tracker.registerDocument(docId, newChunks, 5L);

        assertFalse(tracker.isDocumentFullyVisible(docId, 3L),
            "A query older than the replacement must not see the replaced document");
        assertTrue(tracker.isDocumentFullyVisible(docId, 5L),
            "Document should be visible at replacement epoch");
        assertEquals(newChunks, tracker.getVisibility(docId).chunkIds());
        for (String newChunk : newChunks) {
            assertTrue(tracker.isChunkVisible(newChunk, 5L),
                "New chunk should be visible with its document: " + newChunk);
        }
    }

    @Test
    void testRemovalAtomicity() {
        String docId = "tombstonedDoc";
        Set<String> chunkIds = new HashSet<>(Arrays.asList(
            docId + ":chunk:0", docId + ":chunk:1", docId + ":chunk:2"));

        tracker.registerDocument(docId, chunkIds, 1L);

        assertTrue(tracker.isDocumentFullyVisible(docId, 5L));
        for (String chunkId : chunkIds) {
            assertTrue(tracker.isChunkVisible(chunkId, 5L));
        }

        assertTrue(tracker.removeDocument(docId));

        assertFalse(tracker.isDocumentFullyVisible(docId, 5L));
        for (String chunkId : chunkIds) {
            assertFalse(tracker.isChunkVisible(chunkId, 5L));
        }

        assertEquals(0, tracker.getDocumentCount());
    }

    /**
     * Recovers the document key from a chunk id using the pipeline's own convention, so this
     * test cannot drift from the format the visibility tracker parses.
     */
    private String extractDocumentId(String chunkId) {
        return com.eonmux.excalibur.core.text.ChunkIdentifiers.documentKeyOfChunk(chunkId);
    }
}
