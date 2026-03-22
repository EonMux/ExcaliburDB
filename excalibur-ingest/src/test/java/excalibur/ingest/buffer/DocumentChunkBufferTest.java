package excalibur.ingest.buffer;

import excalibur.ingest.processor.TextChunk;
import io.excalibur.store.log.WriteLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DocumentChunkBuffer.
 */
class DocumentChunkBufferTest {

    @TempDir
    Path tempDir;

    private Path logPath;
    private WriteLog writeLog;
    private DocumentChunkBuffer buffer;

    @BeforeEach
    void setUp() throws IOException {
        logPath = tempDir.resolve("test-buffer.log");
        writeLog = new WriteLog(logPath);
        buffer = new DocumentChunkBuffer(writeLog);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (writeLog != null) {
            writeLog.close();
        }
    }

    @Test
    void testConstructor_nullWriteLog() {
        assertThrows(NullPointerException.class, () -> new DocumentChunkBuffer(null));
    }

    @Test
    void testConstructor_invalidMaxChunks() {
        assertThrows(IllegalArgumentException.class, 
            () -> new DocumentChunkBuffer(writeLog, 0));
        assertThrows(IllegalArgumentException.class, 
            () -> new DocumentChunkBuffer(writeLog, -1));
    }

    @Test
    void testBufferChunk_null() {
        assertThrows(NullPointerException.class, () -> buffer.bufferChunk(null));
    }

    @Test
    void testBufferChunk_single() {
        TextChunk chunk = createChunk("doc1", 0, "content");
        
        buffer.bufferChunk(chunk);
        
        assertEquals(1, buffer.getBufferedChunkCount("doc1"));
        assertEquals(1, buffer.getTotalBufferedChunks());
        assertEquals(1, buffer.getBufferedDocumentCount());
    }

    @Test
    void testBufferChunk_multiple() {
        buffer.bufferChunk(createChunk("doc1", 0, "content0"));
        buffer.bufferChunk(createChunk("doc1", 1, "content1"));
        buffer.bufferChunk(createChunk("doc1", 2, "content2"));
        
        assertEquals(3, buffer.getBufferedChunkCount("doc1"));
        assertEquals(3, buffer.getTotalBufferedChunks());
    }

    @Test
    void testBufferChunk_multipleDocuments() {
        buffer.bufferChunk(createChunk("doc1", 0, "content"));
        buffer.bufferChunk(createChunk("doc2", 0, "content"));
        buffer.bufferChunk(createChunk("doc3", 0, "content"));
        
        assertEquals(1, buffer.getBufferedChunkCount("doc1"));
        assertEquals(1, buffer.getBufferedChunkCount("doc2"));
        assertEquals(1, buffer.getBufferedChunkCount("doc3"));
        assertEquals(3, buffer.getTotalBufferedChunks());
        assertEquals(3, buffer.getBufferedDocumentCount());
    }

    @Test
    void testBufferChunks_nullList() {
        assertThrows(NullPointerException.class, () -> buffer.bufferChunks(null));
    }

    @Test
    void testBufferChunks_emptyList() {
        buffer.bufferChunks(new ArrayList<>());
        
        assertEquals(0, buffer.getTotalBufferedChunks());
    }

    @Test
    void testBufferChunks_mixedDocuments() {
        List<TextChunk> chunks = new ArrayList<>();
        chunks.add(createChunk("doc1", 0, "content0"));
        chunks.add(createChunk("doc2", 0, "content1")); // Different document!
        
        assertThrows(IllegalArgumentException.class, () -> buffer.bufferChunks(chunks));
    }

    @Test
    void testBufferChunks_success() {
        List<TextChunk> chunks = new ArrayList<>();
        chunks.add(createChunk("doc1", 0, "content0"));
        chunks.add(createChunk("doc1", 1, "content1"));
        chunks.add(createChunk("doc1", 2, "content2"));
        
        buffer.bufferChunks(chunks);
        
        assertEquals(3, buffer.getBufferedChunkCount("doc1"));
    }

    @Test
    void testCommitDocument_null() throws IOException {
        assertThrows(NullPointerException.class, () -> buffer.commitDocument(null));
    }

    @Test
    void testCommitDocument_noChunks() throws IOException {
        assertThrows(IllegalStateException.class, () -> buffer.commitDocument("nonexistent"));
    }

    @Test
    void testCommitDocument_singleChunk() throws IOException {
        buffer.bufferChunk(createChunk("doc1", 0, "hello world"));
        
        AtomicDocumentBatch batch = buffer.commitDocument("doc1");
        
        assertNotNull(batch);
        assertEquals("doc1", batch.getDocumentId());
        assertEquals(1, batch.getChunkCount());
        assertTrue(batch.getStartOffset() >= 0);
        assertTrue(batch.getEndOffset() > batch.getStartOffset());
        assertEquals("doc1:0", batch.getFirstChunkId());
        
        // Buffer should be cleared after commit
        assertEquals(0, buffer.getBufferedChunkCount("doc1"));
        assertFalse(buffer.hasBufferedChunks("doc1"));
    }

    @Test
    void testCommitDocument_multipleChunks() throws IOException {
        buffer.bufferChunk(createChunk("doc1", 0, "chunk 0"));
        buffer.bufferChunk(createChunk("doc1", 1, "chunk 1"));
        buffer.bufferChunk(createChunk("doc1", 2, "chunk 2"));
        
        AtomicDocumentBatch batch = buffer.commitDocument("doc1");
        
        assertNotNull(batch);
        assertEquals(3, batch.getChunkCount());
        assertEquals("doc1:0", batch.getChunkIdList().get(0));
        assertEquals("doc1:1", batch.getChunkIdList().get(1));
        assertEquals("doc1:2", batch.getChunkIdList().get(2));
        
        // Verify contiguous offsets
        assertTrue(batch.getEndOffset() > batch.getStartOffset());
    }

    @Test
    void testCommitDocument_contiguousOffsets() throws IOException {
        // Buffer multiple documents
        buffer.bufferChunk(createChunk("doc1", 0, "content1"));
        buffer.bufferChunk(createChunk("doc2", 0, "content2"));
        
        long initialHead = writeLog.writeHead();
        
        AtomicDocumentBatch batch1 = buffer.commitDocument("doc1");
        
        // Doc1 should have contiguous offsets
        assertTrue(batch1.getStartOffset() >= initialHead);
        
        AtomicDocumentBatch batch2 = buffer.commitDocument("doc2");
        
        // Doc2 should start after doc1 ends
        assertTrue(batch2.getStartOffset() >= batch1.getEndOffset());
    }

    @Test
    void testCommitDocumentWithEmbeddings_nullDocumentId() {
        Map<Integer, float[]> embeddings = new HashMap<>();
        embeddings.put(0, new float[]{0.1f, 0.2f});
        
        assertThrows(NullPointerException.class, 
            () -> buffer.commitDocumentWithEmbeddings(null, embeddings));
    }

    @Test
    void testCommitDocumentWithEmbeddings_nullEmbeddings() {
        buffer.bufferChunk(createChunk("doc1", 0, "content"));
        
        assertThrows(NullPointerException.class, 
            () -> buffer.commitDocumentWithEmbeddings("doc1", null));
    }

    @Test
    void testCommitDocumentWithEmbeddings_missingEmbedding() {
        buffer.bufferChunk(createChunk("doc1", 0, "content0"));
        buffer.bufferChunk(createChunk("doc1", 1, "content1"));
        
        Map<Integer, float[]> embeddings = new HashMap<>();
        embeddings.put(0, new float[]{0.1f, 0.2f});
        // Missing embedding for chunk 1
        
        assertThrows(IllegalStateException.class, 
            () -> buffer.commitDocumentWithEmbeddings("doc1", embeddings));
    }

    @Test
    void testCommitDocumentWithEmbeddings_success() throws IOException {
        buffer.bufferChunk(createChunk("doc1", 0, "content0"));
        buffer.bufferChunk(createChunk("doc1", 1, "content1"));
        
        Map<Integer, float[]> embeddings = new HashMap<>();
        embeddings.put(0, new float[]{0.1f, 0.2f, 0.3f});
        embeddings.put(1, new float[]{0.4f, 0.5f, 0.6f});
        
        AtomicDocumentBatch batch = buffer.commitDocumentWithEmbeddings("doc1", embeddings);
        
        assertNotNull(batch);
        assertEquals(2, batch.getChunkCount());
        assertTrue(batch.getEndOffset() > batch.getStartOffset());
    }

    @Test
    void testAbortDocument_null() {
        assertThrows(NullPointerException.class, () -> buffer.abortDocument(null));
    }

    @Test
    void testAbortDocument_nonexistent() {
        int discarded = buffer.abortDocument("nonexistent");
        assertEquals(0, discarded);
    }

    @Test
    void testAbortDocument_success() {
        buffer.bufferChunk(createChunk("doc1", 0, "content0"));
        buffer.bufferChunk(createChunk("doc1", 1, "content1"));
        buffer.bufferChunk(createChunk("doc1", 2, "content2"));
        
        int discarded = buffer.abortDocument("doc1");
        
        assertEquals(3, discarded);
        assertEquals(0, buffer.getBufferedChunkCount("doc1"));
        assertFalse(buffer.hasBufferedChunks("doc1"));
    }

    @Test
    void testGetBufferedChunkCount_null() {
        assertThrows(NullPointerException.class, () -> buffer.getBufferedChunkCount(null));
    }

    @Test
    void testGetBufferedChunkCount_nonexistent() {
        assertEquals(0, buffer.getBufferedChunkCount("nonexistent"));
    }

    @Test
    void testHasBufferedChunks_null() {
        assertThrows(NullPointerException.class, () -> buffer.hasBufferedChunks(null));
    }

    @Test
    void testHasBufferedChunks_nonexistent() {
        assertFalse(buffer.hasBufferedChunks("nonexistent"));
    }

    @Test
    void testClear() {
        buffer.bufferChunk(createChunk("doc1", 0, "content"));
        buffer.bufferChunk(createChunk("doc2", 0, "content"));
        buffer.bufferChunk(createChunk("doc3", 0, "content"));
        
        buffer.clear();
        
        assertEquals(0, buffer.getTotalBufferedChunks());
        assertEquals(0, buffer.getBufferedDocumentCount());
    }

    @Test
    void testMaxChunksPerDocument() {
        buffer = new DocumentChunkBuffer(writeLog, 5);
        
        // Add 5 chunks - should succeed
        for (int i = 0; i < 5; i++) {
            buffer.bufferChunk(createChunk("doc1", i, "content" + i));
        }
        
        // Add 6th chunk - should fail
        assertThrows(IllegalStateException.class, 
            () -> buffer.bufferChunk(createChunk("doc1", 5, "content5")));
    }

    @Test
    void testGetWriteLog() {
        assertSame(writeLog, buffer.getWriteLog());
    }

    @Test
    void testGetMaxChunksPerDocument() {
        assertEquals(10000, buffer.getMaxChunksPerDocument());
        
        buffer = new DocumentChunkBuffer(writeLog, 5000);
        assertEquals(5000, buffer.getMaxChunksPerDocument());
    }

    @Test
    void testConcurrency_buffering() throws InterruptedException {
        int numThreads = 10;
        int chunksPerThread = 100;
        Thread[] threads = new Thread[numThreads];
        
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < chunksPerThread; i++) {
                    buffer.bufferChunk(createChunk("doc" + threadId, i, "content"));
                }
            });
        }
        
        for (Thread thread : threads) {
            thread.start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        assertEquals(numThreads, buffer.getBufferedDocumentCount());
        assertEquals(numThreads * chunksPerThread, buffer.getTotalBufferedChunks());
    }

    @Test
    void testConcurrency_commitAndBuffer() throws InterruptedException, IOException {
        // Pre-buffer some documents
        for (int d = 0; d < 5; d++) {
            for (int i = 0; i < 10; i++) {
                buffer.bufferChunk(createChunk("doc" + d, i, "content"));
            }
        }
        
        Thread committer = new Thread(() -> {
            try {
                for (int d = 0; d < 5; d++) {
                    AtomicDocumentBatch batch = buffer.commitDocument("doc" + d);
                    assertNotNull(batch);
                }
            } catch (IOException e) {
                fail(e);
            }
        });
        
        Thread bufferer = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                buffer.bufferChunk(createChunk("newdoc", i, "content"));
            }
        });
        
        committer.start();
        bufferer.start();
        committer.join();
        bufferer.join();
        
        // All original docs should be committed
        for (int d = 0; d < 5; d++) {
            assertFalse(buffer.hasBufferedChunks("doc" + d));
        }
        
        // New doc should have chunks
        assertTrue(buffer.hasBufferedChunks("newdoc"));
    }

    private TextChunk createChunk(String documentId, int chunkIndex, String text) {
        return new TextChunk(
            documentId,
            text,
            text.length() / 4, // ~4 chars per token
            0, // start offset
            text.length(), // end offset
            chunkIndex
        );
    }
}
