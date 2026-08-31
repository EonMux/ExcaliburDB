package excalibur.ingest.buffer;

import excalibur.ingest.processor.TextChunk;
import io.excalibur.store.WriteLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
        assertEquals("doc1:chunk:0", batch.getFirstChunkId());
        
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
        assertEquals("doc1:chunk:0", batch.getChunkIdList().get(0));
        assertEquals("doc1:chunk:1", batch.getChunkIdList().get(1));
        assertEquals("doc1:chunk:2", batch.getChunkIdList().get(2));
        
        // Verify contiguous offsets
        assertTrue(batch.getEndOffset() > batch.getStartOffset());
    }

    @Test
    void testCommitDocument_contiguousOffsets() throws IOException {
        // Buffer multiple documents
        buffer.bufferChunk(createChunk("doc1", 0, "content1"));
        buffer.bufferChunk(createChunk("doc2", 0, "content2"));
        
        long initialHead = writeLog.writeOffset();
        
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

    // ==================== Encoding/Decoding Tests ====================

    /**
     * Test that encodeVectorOnly produces LITTLE_ENDIAN format.
     * This is critical for compatibility with TailScanner.
     */
    @Test
    void testEncodeVectorOnly_littleEndianFormat() {
        float[] vector = {1.0f, 2.0f, 3.0f, 4.0f};
        byte[] encoded = DocumentChunkBuffer.encodeVectorOnly(vector);

        // Verify length: 4 bytes for dimension + 4 floats * 4 bytes = 20 bytes
        assertEquals(20, encoded.length);

        // Verify dimension is LITTLE_ENDIAN (low byte first)
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        int decodedDimension = buffer.getInt();
        assertEquals(4, decodedDimension);

        // Verify floats are LITTLE_ENDIAN
        assertEquals(1.0f, buffer.getFloat(), 0.0001f);
        assertEquals(2.0f, buffer.getFloat(), 0.0001f);
        assertEquals(3.0f, buffer.getFloat(), 0.0001f);
        assertEquals(4.0f, buffer.getFloat(), 0.0001f);
    }

    /**
     * Test that encodeVectorOnly rejects null input.
     */
    @Test
    void testEncodeVectorOnly_nullInput() {
        assertThrows(NullPointerException.class, () -> DocumentChunkBuffer.encodeVectorOnly(null));
    }

    /**
     * Test encodeChunkWithTextAndEmbedding produces correct format.
     * Format: [dimension:4][vector:dim*4][text:variable]
     */
    @Test
    void testEncodeChunkWithTextAndEmbedding_correctFormat() {
        float[] vector = {0.5f, 0.6f, 0.7f};
        String text = "Hello world";

        byte[] encoded = DocumentChunkBuffer.encodeChunkWithTextAndEmbedding(text, vector);

        // Verify minimum length: 4 + 12 + text length
        int textBytes = text.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(4 + 12 + textBytes, encoded.length);

        // Verify dimension is LITTLE_ENDIAN
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(3, buffer.getInt());

        // Verify floats
        assertEquals(0.5f, buffer.getFloat(), 0.0001f);
        assertEquals(0.6f, buffer.getFloat(), 0.0001f);
        assertEquals(0.7f, buffer.getFloat(), 0.0001f);

        // Verify text is appended after vector
        byte[] textBytesArray = new byte[encoded.length - 16]; // 16 = 4 + 12
        buffer.get(textBytesArray);
        assertEquals(text, new String(textBytesArray, StandardCharsets.UTF_8));
    }

    /**
     * Test encodeChunkWithTextAndEmbedding rejects null inputs.
     */
    @Test
    void testEncodeChunkWithTextAndEmbedding_nullInputs() {
        float[] vector = {1.0f, 2.0f};
        String text = "text";

        assertThrows(NullPointerException.class,
            () -> DocumentChunkBuffer.encodeChunkWithTextAndEmbedding(null, vector));
        assertThrows(NullPointerException.class,
            () -> DocumentChunkBuffer.encodeChunkWithTextAndEmbedding(text, null));
    }

    /**
     * Test decodeEmbedding correctly decodes LITTLE_ENDIAN format.
     */
    @Test
    void testDecodeEmbedding_littleEndianFormat() {
        float[] original = {1.5f, 2.5f, 3.5f, 4.5f, 5.5f};

        // Encode using production method
        byte[] encoded = DocumentChunkBuffer.encodeVectorOnly(original);

        // Decode
        float[] decoded = DocumentChunkBuffer.decodeEmbedding(encoded, 5);

        assertNotNull(decoded);
        assertEquals(5, decoded.length);
        for (int i = 0; i < 5; i++) {
            assertEquals(original[i], decoded[i], 0.0001f);
        }
    }

    /**
     * Test decodeEmbedding handles null input.
     */
    @Test
    void testDecodeEmbedding_nullInput() {
        assertNull(DocumentChunkBuffer.decodeEmbedding(null, 128));
    }

    /**
     * Test decodeEmbedding handles too short input.
     */
    @Test
    void testDecodeEmbedding_tooShort() {
        assertNull(DocumentChunkBuffer.decodeEmbedding(new byte[2], 128));
        assertNull(DocumentChunkBuffer.decodeEmbedding(new byte[0], 128));
    }

    /**
     * Test decodeEmbedding handles dimension mismatch.
     */
    @Test
    void testDecodeEmbedding_dimensionMismatch() {
        float[] vector = {1.0f, 2.0f, 3.0f};
        byte[] encoded = DocumentChunkBuffer.encodeVectorOnly(vector);

        // Request wrong dimension
        assertNull(DocumentChunkBuffer.decodeEmbedding(encoded, 128));
        assertNull(DocumentChunkBuffer.decodeEmbedding(encoded, 4));
    }

    /**
     * Test decodeEmbedding handles value with text appended (production format).
     */
    @Test
    void testDecodeEmbedding_withTextAppended() {
        float[] vector = {0.1f, 0.2f, 0.3f};
        String text = "Some chunk text here";

        byte[] encoded = DocumentChunkBuffer.encodeChunkWithTextAndEmbedding(text, vector);

        // Should still decode vector correctly despite text being present
        float[] decoded = DocumentChunkBuffer.decodeEmbedding(encoded, 3);

        assertNotNull(decoded);
        assertEquals(3, decoded.length);
        for (int i = 0; i < 3; i++) {
            assertEquals(vector[i], decoded[i], 0.0001f);
        }
    }

    /**
     * Test decodeText correctly extracts text from encoded value.
     */
    @Test
    void testDecodeText_success() {
        float[] vector = {1.0f, 2.0f, 3.0f};
        String originalText = "This is the chunk text content.";

        byte[] encoded = DocumentChunkBuffer.encodeChunkWithTextAndEmbedding(originalText, vector);
        String decodedText = DocumentChunkBuffer.decodeText(encoded);

        assertEquals(originalText, decodedText);
    }

    /**
     * Test decodeText handles null input.
     */
    @Test
    void testDecodeText_nullInput() {
        assertNull(DocumentChunkBuffer.decodeText(null));
    }

    /**
     * Test decodeText handles too short input.
     */
    @Test
    void testDecodeText_tooShort() {
        assertNull(DocumentChunkBuffer.decodeText(new byte[2]));
        assertNull(DocumentChunkBuffer.decodeText(new byte[0]));
    }

    /**
     * Test decodeText reports the absence of text on a vector-only encoding.
     *
     * <p>Null, not the empty string. The two readers of this layout disagreed on it -- the
     * query side already returned null and treated it as "nothing to score", while this one
     * returned "" -- and a caller distinguishing them would have got a different answer
     * depending on which decoder it happened to call.
     */
    @Test
    void testDecodeText_vectorOnly() {
        float[] vector = {1.0f, 2.0f};
        byte[] encoded = DocumentChunkBuffer.encodeVectorOnly(vector);

        assertNull(DocumentChunkBuffer.decodeText(encoded));
    }

    /**
     * Test getEncodedSize returns correct values.
     */
    @Test
    void testGetEncodedSize() {
        int textLength = 100;
        int dimension = 128;

        int expected = 4 + 128 * 4 + 100; // 4 + 512 + 100 = 616
        assertEquals(expected, DocumentChunkBuffer.getEncodedSize(textLength, dimension));
    }

    /**
     * CRITICAL TEST: Verify round-trip encoding matches TailScanner expectations.
     *
     * This test simulates what TailScanner.extractVector() does and verifies
     * that the production encoding format is compatible.
     */
    @Test
    void testEncodingCompatibleWithTailScanner() {
        // Test vector
        float[] originalVector = new float[128];
        for (int i = 0; i < 128; i++) {
            originalVector[i] = (float) Math.random();
        }
        String text = "Sample document chunk";

        // Encode using production method (with text)
        byte[] encoded = DocumentChunkBuffer.encodeChunkWithTextAndEmbedding(text, originalVector);

        // Simulate TailScanner.extractVector() logic
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        int dimension = buffer.getInt();

        // Verify dimension matches
        assertEquals(128, dimension);

        // Verify minimum length check (allows extra bytes for text)
        int expectedBytes = 4 + dimension * Float.BYTES;
        assertTrue(encoded.length >= expectedBytes);

        // Extract vector
        float[] extractedVector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            extractedVector[i] = buffer.getFloat();
        }

        // Verify extracted vector matches original
        for (int i = 0; i < 128; i++) {
            assertEquals(originalVector[i], extractedVector[i], 0.0001f,
                "Vector mismatch at index " + i);
        }
    }

    /**
     * CRITICAL TEST: Verify BIG_ENDIAN encoding would FAIL (regression test).
     *
     * This test demonstrates why the fix was necessary - BIG_ENDIAN encoding
     * would cause TailScanner to read garbage values.
     */
    @Test
    void testBigEndianWouldFail() {
        float[] vector = {1.0f, 2.0f, 3.0f, 4.0f};

        // Encode with BIG_ENDIAN (the OLD buggy format)
        ByteBuffer bigEndianBuffer = ByteBuffer.allocate(4 + vector.length * 4)
            .order(ByteOrder.BIG_ENDIAN);
        bigEndianBuffer.putInt(vector.length);
        for (float f : vector) {
            bigEndianBuffer.putFloat(f);
        }
        byte[] bigEndianEncoded = bigEndianBuffer.array();

        // Try to read with LITTLE_ENDIAN (what TailScanner does)
        ByteBuffer littleEndianReader = ByteBuffer.wrap(bigEndianEncoded)
            .order(ByteOrder.LITTLE_ENDIAN);
        int dimension = littleEndianReader.getInt();

        // Dimension would be completely wrong due to byte reversal
        // 4 in BIG_ENDIAN = [0, 0, 0, 4]
        // Read as LITTLE_ENDIAN = 4 * 256^3 = 67108864
        assertNotEquals(4, dimension, "BIG_ENDIAN dimension should NOT match when read as LITTLE_ENDIAN");
        // The value would be a huge garbage number
        assertTrue(dimension > 1000000, "Dimension should be garbage value: " + dimension);
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
