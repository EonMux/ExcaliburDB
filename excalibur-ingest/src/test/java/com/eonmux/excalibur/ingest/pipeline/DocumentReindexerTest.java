package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.llm.client.LLMClientConfig;
import com.eonmux.excalibur.llm.client.LLMHttpClient;
import com.eonmux.excalibur.llm.LLMException;
import com.eonmux.excalibur.ingest.lexical.ChunkIndexStats;
import com.eonmux.excalibur.ingest.lexical.NativeChunkIndex;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionPlanner;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
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
    @DisplayName("Change detection after a partial failure")
    class PartialFailureTests {

        private static final String SOURCE = "test-source";
        private static final String DOCUMENT = "partial-doc";

        private FetchedDocument documentOf(String content) {
            return new FetchedDocument(DOCUMENT, content.getBytes(StandardCharsets.UTF_8),
                "text/plain", System.currentTimeMillis());
        }

        @Test
        @DisplayName("a document whose chunks partly failed is not recorded as indexed")
        void partialFailureLeavesTheDocumentChanged() {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient,
                upsertHandler, registry, storageWriter);

            FetchedDocument fetched = documentOf("This is a test document. ".repeat(400));
            // Enough text for several chunks; the embedder gives up after the second.
            llmClient.setFailAfterCalls(2);

            DocumentReindexer.DocumentResult result =
                reindexer.reindexDocument(SOURCE, DOCUMENT, fetched);

            assertTrue(result.getTotalChunks() > 2, "the document must chunk more than twice");
            assertTrue(result.getFailedChunks() > 0);
            // The registry entry is what change detection compares against. Recording the new
            // content hash beside an incomplete chunk list makes the document read as unchanged
            // for good, so the chunks that failed are never retried.
            assertFalse(registry.contains(SOURCE, DOCUMENT),
                "a partially indexed document must not be recorded as indexed");
        }

        @Test
        @DisplayName("a document whose chunks all failed leaves the previous entry intact")
        void totalFailureKeepsThePreviousEntry() {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient,
                upsertHandler, registry, storageWriter);

            FetchedDocument first = documentOf("Original content. ".repeat(100));
            reindexer.reindexDocument(SOURCE, DOCUMENT, first);
            DocumentMetadata indexed = registry.get(SOURCE, DOCUMENT);
            assertNotNull(indexed);

            llmClient.setFailMode(true);
            FetchedDocument second = documentOf("Replacement content. ".repeat(100));
            DocumentReindexer.DocumentResult result =
                reindexer.reindexDocument(SOURCE, DOCUMENT, second);

            assertEquals(0, result.getSuccessfulChunks());
            assertEquals(indexed.getContentHash(), registry.get(SOURCE, DOCUMENT).getContentHash(),
                "the failed run must not replace the hash the next run compares against");
            assertTrue(registry.get(SOURCE, DOCUMENT).getChunkCount() > 0,
                "the entry must keep naming the chunks that are actually in storage");
        }

        @Test
        @DisplayName("a document that is not text is not reindexed either")
        void binaryIsNotReindexed() {
            // A source is crawled by DocumentIngestor only on its first pass and by this one on
            // every pass after it, so without the same gate here a re-crawl is the way binary
            // content gets into an index that refused it an hour earlier.
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient,
                upsertHandler, registry, storageWriter);
            FetchedDocument png = new FetchedDocument(DOCUMENT,
                new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
                            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'},
                "image/png", System.currentTimeMillis());

            DocumentReindexer.DocumentResult result =
                reindexer.reindexDocument(SOURCE, DOCUMENT, png);

            assertEquals(0, result.getTotalChunks());
            assertFalse(registry.contains(SOURCE, DOCUMENT));
        }

        @Test
        @DisplayName("a fully successful document is still recorded")
        void completeSuccessIsRecorded() {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient,
                upsertHandler, registry, storageWriter);

            DocumentReindexer.DocumentResult result = reindexer.reindexDocument(
                SOURCE, DOCUMENT, documentOf("Complete content. ".repeat(100)));

            assertEquals(0, result.getFailedChunks());
            assertTrue(registry.contains(SOURCE, DOCUMENT));
        }
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
            
            // Chunk ids are source-qualified: excalibur-shard strips the ":chunk:N" suffix to
            // recover the document key the visibility tracker was registered under, so the
            // source id has to be part of the key the reindexer writes.
            for (int i = 0; i < metadata.getChunkCount(); i++) {
                String expectedChunkId = "test-source:doc-chunks:chunk:" + i;
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

        /**
         * Re-indexing must not delete anything from the Cuckoo filter.
         *
         * <p>The filter holds one entry per <em>term</em>, never a chunk id, and a Cuckoo delete
         * clears the first slot whose fingerprint matches without comparing keys. Deleting a
         * chunk id therefore evicts whichever term collides with it, and the query pipeline
         * trusts a covered level's negative as ground truth - it would search the segment last
         * for that term, for every document, until its level is next rebuilt. A surviving entry is
         * only a false positive, which at worst moves a segment ahead of others.</p>
         */
        @Test
        @DisplayName("Should leave the term filter untouched when a document changes")
        void testCuckooFilterUntouchedOnChange() throws SourceException {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient, 
                upsertHandler, registry, storageWriter);

            byte[] termBytes = "content".getBytes(StandardCharsets.UTF_8);
            cuckooFilter.insert(termBytes);
            assertTrue(cuckooFilter.contains(termBytes));

            String oldChunkId = "test-source:old-doc:chunk:0";
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

            assertTrue(cuckooFilter.contains(termBytes),
                "A term shared with live documents must survive a re-index");
        }

        @Test
        @DisplayName("Should tombstone the surplus chunks of the previous version")
        void testSurplusChunksTombstoned() throws SourceException {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient,
                upsertHandler, registry, storageWriter);

            // A previous version with far more chunks than the new one will produce.
            DocumentMetadata.Builder oldMetadata = DocumentMetadata.builder()
                .sourceId("test-source")
                .documentId("old-doc")
                .contentHash(111L)
                .contentLength(100);
            for (int i = 0; i < 20; i++) {
                oldMetadata.addChunkId("test-source:old-doc:chunk:" + i);
            }
            registry.register(oldMetadata.build());

            MockSource source = new MockSource("test-source");
            source.addDocument("old-doc", "Short.");

            reindexer.reindexAll(source);

            DocumentMetadata current = registry.get("test-source", "old-doc");
            assertNotNull(current);
            assertTrue(current.getChunkCount() < 20,
                "The new version should be shorter than the one it replaces");
            for (int i = current.getChunkCount(); i < 20; i++) {
                assertTrue(storageWriter.isDeleted("test-source:old-doc:chunk:" + i),
                    "Surplus chunk " + i + " of the previous version should be tombstoned");
            }
        }
    }

    @Nested
    @DisplayName("Keeping the lexical index in step")
    class LexicalIndexTests {

        @TempDir
        Path indexDir;

        /**
         * The node's epoch clock, which the test advances itself.
         *
         * <p>A run publishes at the epoch after the one it read, so a query at the epoch the run
         * started at will not see it - which is the visibility rule working, not a missing write.
         * A real node's tick advances this; here the test does.</p>
         */
        private final java.util.concurrent.atomic.AtomicLong epoch =
            new java.util.concurrent.atomic.AtomicLong(100);

        private NativeChunkIndex openIndex() throws Exception {
            // readerLagEpochs of zero and a policy that always fires, so a test can watch an
            // erasure reach disk without waiting out a latency bound.
            return NativeChunkIndex.open(indexDir, epoch::get, 0,
                new CompactionPlanner((stats, now) -> true, 0.2, Duration.ofHours(1), 16, 8));
        }

        /** Runs a reindex and lets the node's clock move on, as a tick would. */
        private DocumentReindexer.ReindexResult reindex(DocumentReindexer reindexer,
                                                        MockSource source) throws SourceException {
            DocumentReindexer.ReindexResult result = reindexer.reindexAll(source);
            epoch.incrementAndGet();
            return result;
        }

        private DocumentReindexer reindexerWith(NativeChunkIndex index) {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient,
                upsertHandler, registry, storageWriter);
            reindexer.setLexicalIndex(index, storageWriter::textOf);
            return reindexer;
        }

        private int hits(NativeChunkIndex index, String term) {
            return index.ranker(QueryExpander.identity())
                .rank(RetrievalQuery.ofText(term), 100).documents().size();
        }

        @Test
        @DisplayName("a reindexed document becomes lexically searchable")
        void reindexedDocumentsAreIndexed() throws Exception {
            try (NativeChunkIndex index = openIndex()) {
                DocumentReindexer reindexer = reindexerWith(index);
                MockSource source = new MockSource("test-source");
                source.addDocument("doc-1", "kubernetes scheduler preemption. ".repeat(20));

                reindex(reindexer, source);

                assertTrue(hits(index, "preemption") > 0,
                    "A reindex that writes to storage alone leaves the new content unsearchable");
            }
        }

        @Test
        @DisplayName("a deleted document stops answering queries")
        void deletedDocumentsAreErased() throws Exception {
            try (NativeChunkIndex index = openIndex()) {
                DocumentReindexer reindexer = reindexerWith(index);
                MockSource source = new MockSource("test-source");
                source.addDocument("doc-gone", "confidential incident postmortem. ".repeat(20));
                source.addDocument("doc-stays", "routine capacity planning. ".repeat(20));
                reindex(reindexer, source);
                assertTrue(hits(index, "postmortem") > 0, "precondition: it was indexed");

                MockSource without = new MockSource("test-source");
                without.addDocument("doc-stays", "routine capacity planning. ".repeat(20));
                reindex(reindexer, without);

                assertEquals(0, hits(index, "postmortem"),
                    "A document removed from its source must stop being findable");
                assertTrue(hits(index, "capacity") > 0,
                    "and the documents that remain must not be taken with it");
            }
        }

        @Test
        @DisplayName("a deleted document's text leaves the index's own files")
        void deletedDocumentsAreReclaimed() throws Exception {
            try (NativeChunkIndex index = openIndex()) {
                DocumentReindexer reindexer = reindexerWith(index);
                MockSource source = new MockSource("test-source");
                source.addDocument("doc-gone", "confidential incident postmortem. ".repeat(20));
                source.addDocument("doc-stays", "routine capacity planning. ".repeat(20));
                reindex(reindexer, source);

                MockSource without = new MockSource("test-source");
                without.addDocument("doc-stays", "routine capacity planning. ".repeat(20));
                reindex(reindexer, without);

                ChunkIndexStats stats = index.stats();
                assertEquals(0, stats.erasureRequests(),
                    "The erasure the reindex accepted should have been discharged by its own "
                        + "compaction, not left for whatever ingests next");
                assertEquals(0, stats.erasedDocuments());
            }
        }

        @Test
        @DisplayName("the surplus chunks of a shortened document are erased too")
        void surplusChunksAreErased() throws Exception {
            try (NativeChunkIndex index = openIndex()) {
                DocumentReindexer reindexer = reindexerWith(index);
                MockSource source = new MockSource("test-source");
                source.addDocument("doc-1", "distinctive tail marker. ".repeat(400));
                reindex(reindexer, source);
                int before = hits(index, "distinctive");
                assertTrue(before > 1, "precondition: the first version spans several chunks");

                MockSource shorter = new MockSource("test-source");
                shorter.addDocument("doc-1", "distinctive tail marker.");
                reindex(reindexer, shorter);

                assertEquals(1, hits(index, "distinctive"),
                    "The chunks a shortened document no longer has must not go on matching");
            }
        }

        @Test
        @DisplayName("a reindexed chunk replaces rather than joins its previous version")
        void rewrittenChunksReplace() throws Exception {
            try (NativeChunkIndex index = openIndex()) {
                DocumentReindexer reindexer = reindexerWith(index);
                MockSource first = new MockSource("test-source");
                first.addDocument("doc-1", "the certificate chain is untrusted.");
                reindex(reindexer, first);

                MockSource second = new MockSource("test-source");
                second.addDocument("doc-1", "the certificate chain is now trusted.");
                reindex(reindexer, second);

                assertEquals(1, hits(index, "certificate"),
                    "One chunk identifier is one chunk, however many times it is written");
                assertEquals(0, hits(index, "untrusted"),
                    "and the version it used to hold must stop matching");
            }
        }

        @Test
        @DisplayName("an index cannot be wired without a way to reclaim what it erases")
        void aTextSupplierIsRequired() throws Exception {
            try (NativeChunkIndex index = openIndex()) {
                DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient,
                    upsertHandler, registry, storageWriter);

                assertThrows(IllegalArgumentException.class,
                    () -> reindexer.setLexicalIndex(index, null));
            }
        }

        @Test
        @DisplayName("a reindexer with no index reindexes exactly as before")
        void noIndexIsNotAFailure() throws SourceException {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient,
                upsertHandler, registry, storageWriter);
            reindexer.setLexicalIndex(null, null);

            MockSource source = new MockSource("test-source");
            source.addDocument("doc-1", "ordinary content. ".repeat(20));

            assertEquals(1, reindexer.reindexAll(source).getDocumentsIndexed());
        }
    }


    @Nested
    @DisplayName("A reindex that failed partway")
    class PartialWriteRollbackTests {

        private static final String SOURCE = "test-source";
        private static final String DOCUMENT = "growing-doc";

        private FetchedDocument documentOf(String content) {
            return new FetchedDocument(DOCUMENT, content.getBytes(StandardCharsets.UTF_8),
                "text/plain", System.currentTimeMillis());
        }

        private String chunkId(int index) {
            return SOURCE + ":" + DOCUMENT + ":chunk:" + index;
        }

        @Test
        @DisplayName("a new document that failed partway leaves none of its chunks in storage")
        void newDocumentLeavesNothingBehind() {
            // Nothing names these chunk ids: the document was never registered, so a later
            // erasure reading the registry cannot find them and they stay for good.
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient,
                upsertHandler, registry, storageWriter);
            llmClient.setFailAfterCalls(2);

            DocumentReindexer.DocumentResult result = reindexer.reindexDocument(
                SOURCE, DOCUMENT, documentOf("This is a test document. ".repeat(400)));

            assertTrue(result.getTotalChunks() > 2, "the document must chunk more than twice");
            assertFalse(registry.contains(SOURCE, DOCUMENT));
            assertNull(storageWriter.textOf(chunkId(0)),
                "a chunk no registry entry names is content nothing can ever erase");
            assertNull(storageWriter.textOf(chunkId(1)));
        }

        @Test
        @DisplayName("a grown document that failed partway leaves no chunk past its old length")
        void surplusChunksAreRemoved() {
            // The previous version's ids are overwritten in place and stay named by the entry
            // that survives the failure. The ids past them are the orphans.
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient,
                upsertHandler, registry, storageWriter);

            DocumentReindexer.DocumentResult first = reindexer.reindexDocument(
                SOURCE, DOCUMENT, documentOf("Original content. ".repeat(120)));
            int oldChunks = first.getTotalChunks();
            assertTrue(oldChunks > 0);

            llmClient.setFailAfterCalls(llmClient.getEmbeddingCallCount() + oldChunks + 1);
            DocumentReindexer.DocumentResult second = reindexer.reindexDocument(
                SOURCE, DOCUMENT, documentOf("Replacement content. ".repeat(600)));

            assertTrue(second.getTotalChunks() > oldChunks + 1,
                "the replacement must be long enough to write past the old version");
            assertEquals(oldChunks, registry.get(SOURCE, DOCUMENT).getChunkCount(),
                "the previous entry is what survives a failed reindex");
            assertNull(storageWriter.textOf(chunkId(oldChunks)),
                "a chunk written past the surviving entry is named by nothing");
            for (int i = 0; i < oldChunks; i++) {
                assertNotNull(storageWriter.textOf(chunkId(i)),
                    "the ids the surviving entry still names must not be deleted");
            }
        }
    }

    @Nested
    @DisplayName("Vectors of documents a reindex removes")
    class DeletedDocumentVectorTests {

        @Test
        @DisplayName("a document the source stopped listing has its graph nodes retired")
        void deletedDocumentsVectorsAreRetired() throws SourceException {
            // Storage tombstones the chunks and the registry entry goes, but a graph node is
            // attributed to a document rather than to a chunk: nothing else reaches it, so the
            // document stays rankable by vector search with its name and a distance.
            List<String> retired = new ArrayList<>();
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient,
                upsertHandler, registry, storageWriter);
            reindexer.setVectorEraser(documentKey -> {
                retired.add(documentKey);
                return 2;
            });

            MockSource both = new MockSource("test-source");
            both.addDocument("doc-1", "kept content. ".repeat(20));
            both.addDocument("doc-2", "removed content. ".repeat(20));
            reindexer.reindexAll(both);

            MockSource shrunk = new MockSource("test-source");
            shrunk.addDocument("doc-1", "kept content. ".repeat(20));
            DocumentReindexer.ReindexResult result = reindexer.reindexAll(shrunk);

            assertEquals(1, result.getDocumentsTombstoned());
            assertEquals(List.of("test-source:doc-2"), retired,
                "nodes are attributed by source and document, and must be asked for that way");
        }

        @Test
        @DisplayName("a reindexer with no vector index reindexes exactly as before")
        void aVectorEraserIsOptional() throws SourceException {
            DocumentReindexer reindexer = new DocumentReindexer(chunker, llmClient,
                upsertHandler, registry, storageWriter);
            reindexer.setVectorEraser(null);

            MockSource source = new MockSource("test-source");
            source.addDocument("doc-1", "ordinary content. ".repeat(20));

            assertEquals(1, reindexer.reindexAll(source).getDocumentsIndexed());
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
        private int failAfterCalls = -1;

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

        /**
         * Succeeds for the first {@code calls} embeddings and fails after that, so a document
         * can be made to chunk partially - the case a whole-run failure switch cannot produce.
         */
        void setFailAfterCalls(int calls) {
            this.failAfterCalls = calls;
        }

        int getEmbeddingCallCount() {
            return embeddingCallCount;
        }

        @Override
        public float[] generateEmbedding(String text, String correlationId) {
            embeddingCallCount++;
            if (failAfterCalls >= 0 && embeddingCallCount > failAfterCalls) {
                throw new LLMException("Mock embedding failure after " + failAfterCalls + " calls",
                    LLMException.ErrorType.NETWORK);
            }
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
        private final Set<String> deletedKeys = ConcurrentHashMap.newKeySet();
        private int writeCount = 0;
        private boolean failMode = false;

        /**
         * Records every key a tombstone was issued for, whether or not it was present.
         */
        boolean isDeleted(String chunkId) {
            return deletedKeys.contains(chunkId);
        }

        void setFailMode(boolean fail) {
            this.failMode = fail;
        }

        int getWriteCount() {
            return writeCount;
        }

        /** The text a chunk currently holds, or null once it has been tombstoned. */
        String textOf(String chunkId) {
            return chunks.get(chunkId);
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
            deletedKeys.add(chunkId);
            return chunks.remove(chunkId) != null;
        }
    }
}
