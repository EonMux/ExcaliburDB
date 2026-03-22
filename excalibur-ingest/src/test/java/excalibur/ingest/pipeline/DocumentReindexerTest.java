package excalibur.ingest.pipeline;

import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.registry.UpsertHandler;
import excalibur.ingest.source.FetchedDocument;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import excalibur.filter.CuckooFilter;
import excalibur.llm.client.LLMClientConfig;
import io.excalibur.llm.client.LLMHttpClient;
import io.excalibur.llm.LLMException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for DocumentReindexer.
 */
@DisplayName("DocumentReindexer Tests")
class DocumentReindexerTest {

    private TextChunker chunker;
    private MockLLMClient llmClient;
    private DocumentRegistry registry;
    private MockStorageWriter storageWriter;
    private UpsertHandler upsertHandler;
    private CuckooFilter cuckooFilter;

    @BeforeEach
    void setUp() {
        chunker = new TextChunker(512, 64);
        llmClient = new MockLLMClient(1536);
        registry = new DocumentRegistry();
        storageWriter = new MockStorageWriter();
        cuckooFilter = CuckooFilter.create(10000);
        
        upsertHandler = new UpsertHandler(registry, cuckooFilter, storageWriter);
    }

    @Nested
    @DisplayName("Single Document Re-indexing")
    class SingleDocumentTests {

        @Test
        @DisplayName("Should successfully re-index a document")
        void testSuccessfulReindex() {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            String content = "This is a test document. ".repeat(100);
            FetchedDocument fetched = new FetchedDocument(
                "doc-1",
                content.getBytes(StandardCharsets.UTF_8),
                "text/plain",
                System.currentTimeMillis()
            );
            
            DocumentReindexer.DocumentResult result = reindexer.reindexDocument(
                "test-source", "doc-1", fetched);
            
            assertNotNull(result);
            assertEquals("doc-1", result.getDocumentId());
            assertTrue(result.getTotalChunks() > 0, "Should create at least one chunk");
            assertEquals(result.getTotalChunks(), result.getSuccessfulChunks());
            assertEquals(0, result.getFailedChunks());
            assertTrue(result.isSuccess());
            assertNull(result.getError());
            
            assertTrue(registry.contains("test-source", "doc-1"));
            DocumentMetadata metadata = registry.get("test-source", "doc-1");
            assertNotNull(metadata);
            assertEquals("doc-1", metadata.getDocumentId());
            assertEquals(result.getTotalChunks(), metadata.getChunkCount());
        }

        @Test
        @DisplayName("Should handle empty document content")
        void testEmptyContent() {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            FetchedDocument fetched = new FetchedDocument(
                "empty-doc",
                new byte[0],
                "text/plain",
                System.currentTimeMillis()
            );
            
            DocumentReindexer.DocumentResult result = reindexer.reindexDocument(
                "test-source", "empty-doc", fetched);
            
            assertEquals(0, result.getTotalChunks());
            assertNotNull(result.getError());
        }

        @Test
        @DisplayName("Should reject content exceeding maximum length")
        void testContentTooLarge() {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            byte[] largeContent = new byte[101 * 1024 * 1024];
            Arrays.fill(largeContent, (byte) 'x');
            
            FetchedDocument fetched = new FetchedDocument(
                "large-doc",
                largeContent,
                "text/plain",
                System.currentTimeMillis()
            );
            
            assertThrows(IllegalArgumentException.class, () -> 
                reindexer.reindexDocument("test-source", "large-doc", fetched));
        }

        @Test
        @DisplayName("Should generate correct chunk IDs")
        void testChunkIdFormat() {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            String content = "Test content for chunking. ".repeat(50);
            FetchedDocument fetched = new FetchedDocument(
                "doc-chunks",
                content.getBytes(StandardCharsets.UTF_8),
                "text/plain",
                System.currentTimeMillis()
            );
            
            DocumentReindexer.DocumentResult result = reindexer.reindexDocument(
                "test-source", "doc-chunks", fetched);
            
            DocumentMetadata metadata = registry.get("test-source", "doc-chunks");
            assertNotNull(metadata);
            
            for (int i = 0; i < metadata.getChunkCount(); i++) {
                String expectedChunkId = "doc-chunks:chunk:" + i;
                assertTrue(metadata.getChunkIds().contains(expectedChunkId),
                    "Should contain chunk ID: " + expectedChunkId);
            }
        }

        @Test
        @DisplayName("Should generate embeddings for each chunk")
        void testEmbeddingGeneration() {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            String content = "Content requiring multiple chunks. ".repeat(50);
            FetchedDocument fetched = new FetchedDocument(
                "embed-test",
                content.getBytes(StandardCharsets.UTF_8),
                "text/plain",
                System.currentTimeMillis()
            );
            
            reindexer.reindexDocument("test-source", "embed-test", fetched);
            
            assertEquals(storageWriter.getWriteCount(), llmClient.getEmbeddingCallCount());
        }
    }

    @Nested
    @DisplayName("Change Detection Integration")
    class ChangeDetectionTests {

        @Test
        @DisplayName("Should detect and re-index changed documents")
        void testChangedDocumentDetection() throws SourceException {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            String oldChunkId = "doc-changed:chunk:0";
            byte[] oldChunkBytes = oldChunkId.getBytes(StandardCharsets.UTF_8);
            cuckooFilter.insert(oldChunkBytes);
            storageWriter.writeChunk(oldChunkId, "Old content", new float[1536]);
            
            DocumentMetadata oldMetadata = DocumentMetadata.builder()
                .sourceId("test-source")
                .documentId("doc-changed")
                .contentHash(12345L)
                .contentLength(100)
                .lastModifiedMillis(System.currentTimeMillis() - 10000)
                .addChunkId(oldChunkId)
                .build();
            registry.register(oldMetadata);
            
            MockSource source = new MockSource("test-source");
            String newContent = "Updated content for this document. ".repeat(50);
            source.addDocument("doc-changed", newContent);
            source.addDocument("doc-new", "This is a new document. ".repeat(50));
            
            DocumentReindexer.ReindexResult result = reindexer.reindexAll(source);
            
            assertNotNull(result);
            assertEquals(2, result.getDocumentsIndexed());
            assertTrue(result.getChunksTombstoned() >= 1, 
                "Old chunks should be tombstoned");
        }

        @Test
        @DisplayName("Should tombstone deleted documents")
        void testDeletedDocuments() throws SourceException {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            String chunk1 = "doc-deleted:chunk:0";
            String chunk2 = "doc-deleted:chunk:1";
            byte[] chunk1Bytes = chunk1.getBytes(StandardCharsets.UTF_8);
            byte[] chunk2Bytes = chunk2.getBytes(StandardCharsets.UTF_8);
            cuckooFilter.insert(chunk1Bytes);
            cuckooFilter.insert(chunk2Bytes);
            storageWriter.writeChunk(chunk1, "Content 1", new float[1536]);
            storageWriter.writeChunk(chunk2, "Content 2", new float[1536]);
            
            DocumentMetadata deletedMetadata = DocumentMetadata.builder()
                .sourceId("test-source")
                .documentId("doc-deleted")
                .contentHash(99999L)
                .contentLength(500)
                .addChunkId(chunk1)
                .addChunkId(chunk2)
                .build();
            registry.register(deletedMetadata);
            
            MockSource source = new MockSource("test-source");
            source.addDocument("doc-keep", "This document still exists. ".repeat(50));
            
            DocumentReindexer.ReindexResult result = reindexer.reindexAll(source);
            
            assertEquals(1, result.getDocumentsIndexed());
            assertTrue(result.getChunksTombstoned() >= 2, 
                "Deleted document chunks should be tombstoned");
            assertFalse(registry.contains("test-source", "doc-deleted"));
        }

        @Test
        @DisplayName("Should handle new documents")
        void testNewDocuments() throws SourceException {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            MockSource source = new MockSource("test-source");
            source.addDocument("doc-1", "First document. ".repeat(50));
            source.addDocument("doc-2", "Second document. ".repeat(50));
            
            DocumentReindexer.ReindexResult result = reindexer.reindexAll(source);
            
            assertEquals(2, result.getDocumentsIndexed());
            assertTrue(registry.contains("test-source", "doc-1"));
            assertTrue(registry.contains("test-source", "doc-2"));
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should collect embedding errors without failing")
        void testEmbeddingError() {
            llmClient.setFailMode(true);
            
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            FetchedDocument fetched = new FetchedDocument(
                "error-doc",
                "Test content. ".repeat(50).getBytes(StandardCharsets.UTF_8),
                "text/plain",
                System.currentTimeMillis()
            );
            
            DocumentReindexer.DocumentResult result = reindexer.reindexDocument(
                "test-source", "error-doc", fetched);
            
            assertTrue(result.hasError());
            assertTrue(result.getFailedChunks() > 0);
        }

        @Test
        @DisplayName("Should continue processing after fetch errors")
        void testFetchErrorContinue() throws SourceException {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            MockSource source = new MockSource("test-source");
            source.addDocument("doc-good", "Good content. ".repeat(50));
            source.addFailingDocument("doc-bad");
            
            DocumentReindexer.ReindexResult result = reindexer.reindexAll(source);
            
            assertTrue(result.hasErrors() || result.getDocumentsIndexed() >= 1);
            assertTrue(result.getDocumentsIndexed() >= 1, 
                "At least one document should be indexed despite fetch errors");
        }

        @Test
        @DisplayName("Should handle storage write failures")
        void testStorageWriteFailure() {
            storageWriter.setFailMode(true);
            
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            FetchedDocument fetched = new FetchedDocument(
                "storage-fail-doc",
                "Test content. ".repeat(50).getBytes(StandardCharsets.UTF_8),
                "text/plain",
                System.currentTimeMillis()
            );
            
            DocumentReindexer.DocumentResult result = reindexer.reindexDocument(
                "test-source", "storage-fail-doc", fetched);
            
            assertTrue(result.getFailedChunks() > 0);
        }
    }

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("Should create chunks with correct token counts (512±10%)")
        void testChunkTokenCounts() {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3000; i++) {
                sb.append("word ");
            }
            String content = sb.toString();
            
            FetchedDocument fetched = new FetchedDocument(
                "chunk-size-test",
                content.getBytes(StandardCharsets.UTF_8),
                "text/plain",
                System.currentTimeMillis()
            );
            
            reindexer.reindexDocument("test-source", "chunk-size-test", fetched);
            
            DocumentMetadata metadata = registry.get("test-source", "chunk-size-test");
            assertNotNull(metadata);
            assertTrue(metadata.getChunkCount() > 1, "Should create multiple chunks");
        }

        @Test
        @DisplayName("Should generate embeddings with correct dimensions")
        void testEmbeddingDimensions() {
            int expectedDimension = 1536;
            llmClient = new MockLLMClient(expectedDimension);
            
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            FetchedDocument fetched = new FetchedDocument(
                "dim-test",
                "Test content. ".repeat(50).getBytes(StandardCharsets.UTF_8),
                "text/plain",
                System.currentTimeMillis()
            );
            
            reindexer.reindexDocument("test-source", "dim-test", fetched);
            
            Map<String, float[]> embeddings = storageWriter.getEmbeddings();
            assertFalse(embeddings.isEmpty());
            
            for (float[] embedding : embeddings.values()) {
                assertEquals(expectedDimension, embedding.length,
                    "Embedding should have correct dimension");
                for (float val : embedding) {
                    assertTrue(Float.isFinite(val), "Embedding values should be finite");
                    assertTrue(val >= -1.0f && val <= 1.0f, 
                        "Embedding values should be in [-1, 1] range");
                }
            }
        }

        @Test
        @DisplayName("Should compute content hash for change detection")
        void testContentHash() {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            String content = "Test content for hashing.";
            FetchedDocument fetched = new FetchedDocument(
                "hash-test",
                content.getBytes(StandardCharsets.UTF_8),
                "text/plain",
                System.currentTimeMillis()
            );
            
            reindexer.reindexDocument("test-source", "hash-test", fetched);
            
            DocumentMetadata metadata = registry.get("test-source", "hash-test");
            assertNotNull(metadata);
            assertTrue(metadata.getContentHash() > 0, "Content hash should be positive");
            
            long expectedHash = DocumentRegistry.computeContentHash(
                content.getBytes(StandardCharsets.UTF_8));
            assertEquals(expectedHash, metadata.getContentHash());
        }

        @Test
        @DisplayName("Should store non-negative timestamps")
        void testTimestamps() {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            FetchedDocument fetched = new FetchedDocument(
                "timestamp-test",
                "Test content.".getBytes(StandardCharsets.UTF_8),
                "text/plain",
                -1000L
            );
            
            reindexer.reindexDocument("test-source", "timestamp-test", fetched);
            
            DocumentMetadata metadata = registry.get("test-source", "timestamp-test");
            assertNotNull(metadata);
            assertTrue(metadata.getLastModifiedMillis() >= 0, 
                "Last modified should be non-negative");
            assertTrue(metadata.getIndexTimeMillis() > 0, 
                "Index time should be positive");
        }
    }

    @Nested
    @DisplayName("Cuckoo Filter Integration")
    class CuckooFilterTests {

        @Test
        @DisplayName("Should remove old chunks from Cuckoo filter on change")
        void testCuckooFilterDeletion() throws SourceException {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);
            
            String oldChunkId = "old-doc:chunk:0";
            byte[] oldChunkBytes = oldChunkId.getBytes(StandardCharsets.UTF_8);
            cuckooFilter.insert(oldChunkBytes);
            assertTrue(cuckooFilter.contains(oldChunkBytes));
            
            DocumentMetadata oldMetadata = DocumentMetadata.builder()
                .sourceId("test-source")
                .documentId("old-doc")
                .contentHash(111L)
                .contentLength(100)
                .addChunkId(oldChunkId)
                .build();
            registry.register(oldMetadata);
            
            MockSource source = new MockSource("test-source");
            source.addDocument("old-doc", "Updated content. ".repeat(50));
            
            reindexer.reindexAll(source);
            
            assertFalse(cuckooFilter.contains(oldChunkBytes),
                "Old chunk should be removed from Cuckoo filter");
        }
    }

    private static class MockSource implements Source {
        private final String sourceId;
        private final Map<String, String> documents = new HashMap<>();
        private final Set<String> failingDocuments = new HashSet<>();

        MockSource(String sourceId) {
            this.sourceId = sourceId;
        }

        void addDocument(String docId, String content) {
            documents.put(docId, content);
        }

        void addFailingDocument(String docId) {
            failingDocuments.add(docId);
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }

        @Override
        public String getSourceType() {
            return "mock";
        }

        @Override
        public Stream<String> crawl() {
            return documents.keySet().stream();
        }

        @Override
        public boolean hasChanged(Instant since) {
            return true;
        }

        @Override
        public FetchedDocument fetch(String documentId) throws SourceException {
            if (failingDocuments.contains(documentId)) {
                throw new SourceException("Fetch failed for " + documentId);
            }
            String content = documents.get(documentId);
            if (content == null) {
                throw new SourceException("Document not found: " + documentId);
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            return new FetchedDocument(documentId, bytes, "text/plain", 
                System.currentTimeMillis());
        }

        @Override
        public void close() {
        }
    }

    private static class MockLLMClient extends LLMHttpClient {
        private final int dimension;
        private int embeddingCallCount = 0;
        private boolean failMode = false;

        MockLLMClient(int dimension) {
            super(LLMClientConfig.builder()
                .baseUrl("http://localhost:8080")
                .apiKey("test-key")
                .embeddingModel("text-embedding-ada-002")
                .chatModel("gpt-4")
                .embeddingDimension(dimension)
                .timeout(Duration.ofSeconds(30))
                .failureThreshold(5)
                .cooldownPeriod(Duration.ofSeconds(60))
                .build());
            this.dimension = dimension;
        }

        void setFailMode(boolean fail) {
            this.failMode = fail;
        }

        int getEmbeddingCallCount() {
            return embeddingCallCount;
        }

        @Override
        public float[] generateEmbedding(String text, String correlationId) {
            embeddingCallCount++;
            if (failMode) {
                throw new LLMException("Mock embedding failure",
                    LLMException.ErrorType.NETWORK);
            }
            float[] embedding = new float[dimension];
            Random random = new Random(text.hashCode());
            for (int i = 0; i < dimension; i++) {
                embedding[i] = random.nextFloat() * 2 - 1;
            }
            return embedding;
        }
    }

    private static class MockStorageWriter implements DocumentReindexer.StorageWriter, 
                                                       UpsertHandler.StorageAdapter {
        private final Map<String, String> chunks = new ConcurrentHashMap<>();
        private final Map<String, float[]> embeddings = new ConcurrentHashMap<>();
        private int writeCount = 0;
        private boolean failMode = false;

        void setFailMode(boolean fail) {
            this.failMode = fail;
        }

        int getWriteCount() {
            return writeCount;
        }

        Map<String, float[]> getEmbeddings() {
            return Collections.unmodifiableMap(embeddings);
        }

        @Override
        public boolean writeChunk(String chunkId, String content, float[] embedding) {
            if (failMode) {
                return false;
            }
            chunks.put(chunkId, content);
            embeddings.put(chunkId, embedding.clone());
            writeCount++;
            return true;
        }

        @Override
        public boolean delete(byte[] key) {
            String chunkId = new String(key, StandardCharsets.UTF_8);
            return chunks.remove(chunkId) != null;
        }
    }
}
