package excalibur.ingest.buffer;

import excalibur.ingest.processor.TextChunk;
import excalibur.ingest.visibility.DocumentVisibilityRegistry;
import io.excalibur.store.WriteLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for contiguous write log offsets and atomic publication of document chunks.
 *
 * <p>These tests verify the core document-atomicity guarantee:</p>
 * <ul>
 *   <li>Chunks for one document have contiguous write log offsets</li>
 *   <li>No interleaving from concurrent writers</li>
 *   <li>Documents are published atomically to visibility epoch</li>
 *   <li>Queries never see partially-ingested documents</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>startOffset &lt; endOffset for each committed document</li>
 *   <li>Entry IDs are unique and monotonically increasing</li>
 *   <li>No two documents have overlapping offset ranges</li>
 *   <li>All chunks of a document become visible at the same epoch</li>
 * </ul>
 */
class ContiguousOffsetAtomicityTest {

    @TempDir
    Path tempDir;

    private Path logPath;
    private WriteLog writeLog;
    private DocumentChunkBuffer buffer;
    private DocumentVisibilityRegistry visibilityRegistry;

    @BeforeEach
    void setUp() throws IOException {
        logPath = tempDir.resolve("test-contiguous.log");
        writeLog = new WriteLog(logPath);
        buffer = new DocumentChunkBuffer(writeLog);
        visibilityRegistry = new DocumentVisibilityRegistry();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (writeLog != null) {
            writeLog.close();
        }
    }

    @Test
    void testSingleDocumentContiguousOffsets() throws IOException {
        buffer.bufferChunk(createChunk("doc1", 0, "chunk zero content here"));
        buffer.bufferChunk(createChunk("doc1", 1, "chunk one content here"));
        buffer.bufferChunk(createChunk("doc1", 2, "chunk two content here"));
        buffer.bufferChunk(createChunk("doc1", 3, "chunk three content here"));

        AtomicDocumentBatch batch = buffer.commitDocument("doc1");

        assertNotNull(batch);
        assertEquals("doc1", batch.getDocumentId());
        assertEquals(4, batch.getChunkCount());
        assertTrue(batch.getStartOffset() >= 0);
        assertTrue(batch.getEndOffset() > batch.getStartOffset());
        assertTrue(batch.getByteSize() > 0);

        List<Long> entryIds = batch.getEntryIdList();
        assertEquals(4, entryIds.size());

        for (int i = 1; i < entryIds.size(); i++) {
            assertTrue(entryIds.get(i) > entryIds.get(i - 1),
                "Entry IDs must be monotonically increasing: " + entryIds);
        }

        long byteSize = batch.getByteSize();
        long expectedMinSize = 4 * (13 + 20);
        assertTrue(byteSize >= expectedMinSize,
            "Byte size should accommodate all chunks: " + byteSize + " >= " + expectedMinSize);
    }

    @Test
    void testMultipleDocumentsNoInterleaving() throws IOException {
        buffer.bufferChunk(createChunk("doc1", 0, "doc1-chunk0"));
        buffer.bufferChunk(createChunk("doc1", 1, "doc1-chunk1"));
        buffer.bufferChunk(createChunk("doc2", 0, "doc2-chunk0"));
        buffer.bufferChunk(createChunk("doc2", 1, "doc2-chunk1"));
        buffer.bufferChunk(createChunk("doc3", 0, "doc3-chunk0"));

        AtomicDocumentBatch batch1 = buffer.commitDocument("doc1");
        AtomicDocumentBatch batch2 = buffer.commitDocument("doc2");
        AtomicDocumentBatch batch3 = buffer.commitDocument("doc3");

        List<AtomicDocumentBatch> batches = List.of(batch1, batch2, batch3);
        for (AtomicDocumentBatch batch : batches) {
            assertTrue(batch.getStartOffset() >= 0, "startOffset must be non-negative");
            assertTrue(batch.getEndOffset() > batch.getStartOffset(), "endOffset > startOffset");
        }

        for (int i = 0; i < batches.size(); i++) {
            for (int j = i + 1; j < batches.size(); j++) {
                AtomicDocumentBatch a = batches.get(i);
                AtomicDocumentBatch b = batches.get(j);

                boolean disjoint = (a.getEndOffset() <= b.getStartOffset()) ||
                                   (b.getEndOffset() <= a.getStartOffset());
                assertTrue(disjoint,
                    String.format("Batches %d and %d must not overlap: [%d,%d) vs [%d,%d)",
                        i, j, a.getStartOffset(), a.getEndOffset(),
                        b.getStartOffset(), b.getEndOffset()));
            }
        }
    }

    @Test
    void testContiguousOffsetsUnderConcurrency() throws Exception {
        int numDocuments = 50;
        int chunksPerDocument = 20;
        int numThreads = 10;

        for (int d = 0; d < numDocuments; d++) {
            String docId = "doc" + d;
            for (int c = 0; c < chunksPerDocument; c++) {
                buffer.bufferChunk(createChunk(docId, c, "content for chunk " + c));
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        ConcurrentHashMap<String, AtomicDocumentBatch> results = new ConcurrentHashMap<>();
        AtomicInteger errors = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int d = 0; d < numDocuments; d++) {
            final String docId = "doc" + d;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    AtomicDocumentBatch batch = buffer.commitDocument(docId);
                    results.put(docId, batch);
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                }
            }));
        }

        startLatch.countDown();

        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(0, errors.get(), "No errors should occur during concurrent commits");
        assertEquals(numDocuments, results.size(), "All documents should be committed");

        List<AtomicDocumentBatch> batches = new ArrayList<>(results.values());

        for (AtomicDocumentBatch batch : batches) {
            assertTrue(batch.getStartOffset() >= 0,
                "startOffset must be non-negative for " + batch.getDocumentId());
            assertTrue(batch.getEndOffset() > batch.getStartOffset(),
                "endOffset > startOffset for " + batch.getDocumentId());
            assertEquals(chunksPerDocument, batch.getChunkCount(),
                "All chunks should be in batch for " + batch.getDocumentId());
        }

        for (int i = 0; i < batches.size(); i++) {
            for (int j = i + 1; j < batches.size(); j++) {
                AtomicDocumentBatch a = batches.get(i);
                AtomicDocumentBatch b = batches.get(j);

                boolean disjoint = (a.getEndOffset() <= b.getStartOffset()) ||
                                   (b.getEndOffset() <= a.getStartOffset());
                assertTrue(disjoint,
                    String.format("Documents %s and %s have overlapping offsets: [%d,%d) vs [%d,%d)",
                        a.getDocumentId(), b.getDocumentId(),
                        a.getStartOffset(), a.getEndOffset(),
                        b.getStartOffset(), b.getEndOffset()));
            }
        }
    }

    @Test
    void testAtomicPublicationToVisibilityEpoch() throws IOException {
        String docId = "atomicDoc";
        Set<String> chunkIds = new HashSet<>();

        for (int i = 0; i < 5; i++) {
            String chunkId = docId + ":" + i;
            chunkIds.add(chunkId);
            buffer.bufferChunk(createChunk(docId, i, "chunk content " + i));
        }

        AtomicDocumentBatch batch = buffer.commitDocument(docId);

        long visibilityEpoch = 100L;
        visibilityRegistry.registerDocument(docId, chunkIds, visibilityEpoch);

        for (String chunkId : chunkIds) {
            assertFalse(visibilityRegistry.isChunkVisibleAt(chunkId, visibilityEpoch - 1),
                "Chunk should not be visible before commit epoch: " + chunkId);
            assertTrue(visibilityRegistry.isChunkVisibleAt(chunkId, visibilityEpoch),
                "Chunk should be visible at commit epoch: " + chunkId);
        }

        assertTrue(visibilityRegistry.isVisibleAt(docId, visibilityEpoch),
            "Document should be visible at commit epoch");
        assertFalse(visibilityRegistry.isVisibleAt(docId, visibilityEpoch - 1),
            "Document should not be visible before commit epoch");
    }

    @Test
    void testNoPartialDocumentsInVisibility() throws IOException {
        String doc1Id = "doc1";
        String doc2Id = "doc2";

        Set<String> doc1Chunks = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            String chunkId = doc1Id + ":" + i;
            doc1Chunks.add(chunkId);
            buffer.bufferChunk(createChunk(doc1Id, i, "doc1 content"));
        }

        Set<String> doc2Chunks = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            String chunkId = doc2Id + ":" + i;
            doc2Chunks.add(chunkId);
            buffer.bufferChunk(createChunk(doc2Id, i, "doc2 content"));
        }

        AtomicDocumentBatch batch1 = buffer.commitDocument(doc1Id);
        AtomicDocumentBatch batch2 = buffer.commitDocument(doc2Id);

        visibilityRegistry.registerDocument(doc1Id, doc1Chunks, 10L);

        Set<String> allChunks = new HashSet<>();
        allChunks.addAll(doc1Chunks);
        allChunks.addAll(doc2Chunks);

        Set<String> visibleAt5 = visibilityRegistry.filterVisibleChunks(allChunks, 5L);
        assertEquals(0, visibleAt5.size(), "No chunks should be visible at epoch 5");

        Set<String> visibleAt10 = visibilityRegistry.filterVisibleChunks(allChunks, 10L);
        assertEquals(3, visibleAt10.size(), "Only doc1 chunks should be visible at epoch 10");
        assertTrue(visibleAt10.containsAll(doc1Chunks), "All doc1 chunks should be visible");
        for (String chunk : doc2Chunks) {
            assertFalse(visibleAt10.contains(chunk), "Doc2 chunks should not be visible: " + chunk);
        }

        visibilityRegistry.registerDocument(doc2Id, doc2Chunks, 20L);

        Set<String> visibleAt20 = visibilityRegistry.filterVisibleChunks(allChunks, 20L);
        assertEquals(7, visibleAt20.size(), "All chunks should be visible at epoch 20");
        assertTrue(visibleAt20.containsAll(doc1Chunks));
        assertTrue(visibleAt20.containsAll(doc2Chunks));
    }

    @Test
    void testEntryIdsAreUnique() throws IOException {
        int numDocs = 10;
        List<Long> allEntryIds = new ArrayList<>();

        for (int d = 0; d < numDocs; d++) {
            String docId = "doc" + d;
            for (int c = 0; c < 5; c++) {
                buffer.bufferChunk(createChunk(docId, c, "content"));
            }

            AtomicDocumentBatch batch = buffer.commitDocument(docId);
            allEntryIds.addAll(batch.getEntryIdList());
        }

        Set<Long> uniqueEntryIds = new HashSet<>(allEntryIds);
        assertEquals(allEntryIds.size(), uniqueEntryIds.size(),
            "All entry IDs must be unique across all documents");
    }

    @Test
    void testBatchWithEmbeddingsContiguous() throws IOException {
        String docId = "embedDoc";
        for (int i = 0; i < 5; i++) {
            buffer.bufferChunk(createChunk(docId, i, "content " + i));
        }

        Map<Integer, float[]> embeddings = new java.util.HashMap<>();
        for (int i = 0; i < 5; i++) {
            embeddings.put(i, new float[]{0.1f * i, 0.2f * i, 0.3f * i});
        }

        AtomicDocumentBatch batch = buffer.commitDocumentWithEmbeddings(docId, embeddings);

        assertNotNull(batch);
        assertEquals(5, batch.getChunkCount());
        assertTrue(batch.getStartOffset() >= 0);
        assertTrue(batch.getEndOffset() > batch.getStartOffset());

        List<Long> entryIds = batch.getEntryIdList();
        assertEquals(5, entryIds.size());
        for (int i = 1; i < entryIds.size(); i++) {
            assertTrue(entryIds.get(i) > entryIds.get(i - 1),
                "Entry IDs must be monotonically increasing");
        }
    }

    @Test
    void testConcurrentCommitsWithAtomicBatching() throws Exception {
        int numThreads = 20;
        int docsPerThread = 5;
        int chunksPerDoc = 10;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        ConcurrentHashMap<String, AtomicDocumentBatch> allBatches = new ConcurrentHashMap<>();
        AtomicInteger successCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();

                    for (int d = 0; d < docsPerThread; d++) {
                        String docId = "thread" + threadId + "_doc" + d;

                        for (int c = 0; c < chunksPerDoc; c++) {
                            buffer.bufferChunk(createChunk(docId, c,
                                "content for " + docId + " chunk " + c));
                        }

                        AtomicDocumentBatch batch = buffer.commitDocument(docId);
                        allBatches.put(docId, batch);
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
        }

        startLatch.countDown();

        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        int expectedDocs = numThreads * docsPerThread;
        assertEquals(expectedDocs, successCount.get(),
            "All document commits should succeed");
        assertEquals(expectedDocs, allBatches.size(),
            "All batches should be recorded");

        List<AtomicDocumentBatch> batches = new ArrayList<>(allBatches.values());

        int overlapCount = 0;
        for (int i = 0; i < batches.size(); i++) {
            for (int j = i + 1; j < batches.size(); j++) {
                AtomicDocumentBatch a = batches.get(i);
                AtomicDocumentBatch b = batches.get(j);

                boolean overlap = !(a.getEndOffset() <= b.getStartOffset() ||
                                    b.getEndOffset() <= a.getStartOffset());
                if (overlap) {
                    overlapCount++;
                    System.err.println(String.format(
                        "OVERLAP: %s [%d,%d) vs %s [%d,%d)",
                        a.getDocumentId(), a.getStartOffset(), a.getEndOffset(),
                        b.getDocumentId(), b.getStartOffset(), b.getEndOffset()));
                }
            }
        }

        assertEquals(0, overlapCount,
            "No documents should have overlapping offset ranges (found " + overlapCount + " overlaps)");
    }

    @Test
    void testOffsetRangesAreMonotonicPerDocument() throws IOException {
        List<AtomicDocumentBatch> batches = new ArrayList<>();

        for (int d = 0; d < 10; d++) {
            String docId = "doc" + d;
            for (int c = 0; c < 3; c++) {
                buffer.bufferChunk(createChunk(docId, c, "content"));
            }
            batches.add(buffer.commitDocument(docId));
        }

        for (int i = 1; i < batches.size(); i++) {
            AtomicDocumentBatch prev = batches.get(i - 1);
            AtomicDocumentBatch curr = batches.get(i);

            assertTrue(curr.getStartOffset() >= prev.getStartOffset(),
                "Start offsets should be monotonically increasing");
        }
    }

    private TextChunk createChunk(String documentId, int chunkIndex, String text) {
        return new TextChunk(
            documentId,
            text,
            Math.max(1, text.length() / 4),
            0,
            text.length(),
            chunkIndex
        );
    }
}
