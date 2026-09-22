package com.eonmux.excalibur.ingest.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Source interface and supporting classes.
 */
class SourceTest {

    @Nested
    @DisplayName("FetchedDocument")
    class FetchedDocumentTest {

        @Test
        @DisplayName("creates document with all fields")
        void createsDocumentWithAllFields() {
            byte[] content = "Hello, World!".getBytes();
            FetchedDocument doc = new FetchedDocument("doc-1", content, "text/plain", 1234567890L);
            
            assertEquals("doc-1", doc.getDocumentId());
            assertArrayEquals(content, doc.getContent());
            assertEquals("text/plain", doc.getContentType());
            assertEquals(1234567890L, doc.getLastModifiedMillis());
            assertEquals(13, doc.getContentLength());
        }

        @Test
        @DisplayName("creates document without last modified time")
        void createsDocumentWithoutLastModified() {
            byte[] content = "test".getBytes();
            FetchedDocument doc = new FetchedDocument("doc-2", content, "text/html");
            
            assertEquals("doc-2", doc.getDocumentId());
            assertEquals("text/html", doc.getContentType());
            assertEquals(0L, doc.getLastModifiedMillis());
            assertFalse(doc.hasKnownLastModified());
        }

        @Test
        @DisplayName("rejects null document ID")
        void rejectsNullDocumentId() {
            assertThrows(IllegalArgumentException.class, () ->
                new FetchedDocument(null, new byte[0], "text/plain")
            );
        }

        @Test
        @DisplayName("rejects empty document ID")
        void rejectsEmptyDocumentId() {
            assertThrows(IllegalArgumentException.class, () ->
                new FetchedDocument("", new byte[0], "text/plain")
            );
        }

        @Test
        @DisplayName("rejects null content")
        void rejectsNullContent() {
            assertThrows(IllegalArgumentException.class, () ->
                new FetchedDocument("doc-1", null, "text/plain")
            );
        }

        @Test
        @DisplayName("uses default content type when null provided")
        void usesDefaultContentType() {
            FetchedDocument doc = new FetchedDocument("doc-1", new byte[0], null);
            assertEquals("application/octet-stream", doc.getContentType());
        }

        @Test
        @DisplayName("clamps negative last modified to zero")
        void clampsNegativeLastModified() {
            FetchedDocument doc = new FetchedDocument("doc-1", new byte[0], "text/plain", -1000L);
            assertEquals(0L, doc.getLastModifiedMillis());
        }

        @Test
        @DisplayName("defensively copies content on construction and access")
        void defensivelyCopiesContent() {
            byte[] original = "original".getBytes();
            FetchedDocument doc = new FetchedDocument("doc-1", original, "text/plain");
            
            original[0] = 'X';
            byte[] retrieved = doc.getContent();
            
            assertNotEquals('X', retrieved[0]);
            assertEquals('o', retrieved[0]);
        }

        @Test
        @DisplayName("hasKnownLastModified returns true for positive timestamps")
        void hasKnownLastModifiedReturnsTrueForPositive() {
            FetchedDocument doc = new FetchedDocument("d", new byte[0], "text/plain", 1L);
            assertTrue(doc.hasKnownLastModified());
        }

        @Test
        @DisplayName("implements equals and hashCode correctly")
        void implementsEqualsAndHashCode() {
            byte[] content = "test".getBytes();
            FetchedDocument doc1 = new FetchedDocument("doc-1", content, "text/plain", 1000L);
            FetchedDocument doc2 = new FetchedDocument("doc-1", content, "text/plain", 1000L);
            FetchedDocument doc3 = new FetchedDocument("doc-2", content, "text/plain", 1000L);
            
            assertEquals(doc1, doc2);
            assertEquals(doc1.hashCode(), doc2.hashCode());
            assertNotEquals(doc1, doc3);
        }
    }

    @Nested
    @DisplayName("SourceException")
    class SourceExceptionTest {

        @Test
        @DisplayName("creates exception with message")
        void createsExceptionWithMessage() {
            SourceException ex = new SourceException("Connection failed");
            assertEquals("Connection failed", ex.getMessage());
            assertNull(ex.getSourceId());
            assertTrue(ex.isRetryable());
        }

        @Test
        @DisplayName("creates exception with message and cause")
        void createsExceptionWithMessageAndCause() {
            IOException cause = new IOException("Network error");
            SourceException ex = new SourceException("Connection failed", cause);
            assertEquals("Connection failed", ex.getMessage());
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("creates exception with source context")
        void createsExceptionWithSourceContext() {
            SourceException ex = new SourceException("Rate limited", "source-1", false);
            assertEquals("Rate limited", ex.getMessage());
            assertEquals("source-1", ex.getSourceId());
            assertFalse(ex.isRetryable());
        }

        @Test
        @DisplayName("toString includes source context")
        void toStringIncludesSourceContext() {
            SourceException ex = new SourceException("Error", "my-source", false);
            String str = ex.toString();
            assertTrue(str.contains("my-source"));
            assertTrue(str.contains("retryable=false"));
        }
    }

    @Nested
    @DisplayName("DocumentNotFoundException")
    class DocumentNotFoundExceptionTest {

        @Test
        @DisplayName("creates exception with document ID")
        void createsExceptionWithDocumentId() {
            DocumentNotFoundException ex = new DocumentNotFoundException("doc-123");
            assertEquals("doc-123", ex.getDocumentId());
            assertFalse(ex.isRetryable());
        }

        @Test
        @DisplayName("creates exception with document and source ID")
        void createsExceptionWithDocumentAndSourceId() {
            DocumentNotFoundException ex = new DocumentNotFoundException("doc-123", "source-1");
            assertEquals("doc-123", ex.getDocumentId());
            assertEquals("source-1", ex.getSourceId());
        }

        @Test
        @DisplayName("creates exception with cause")
        void createsExceptionWithCause() {
            IOException cause = new IOException("404 Not Found");
            DocumentNotFoundException ex = new DocumentNotFoundException("doc-1", "source-1", cause);
            assertEquals("doc-1", ex.getDocumentId());
            assertSame(cause, ex.getCause());
        }
    }

    @Nested
    @DisplayName("Source Interface")
    class SourceInterfaceTest {

        @Test
        @DisplayName("provides default implementations")
        void providesDefaultImplementations() throws Exception {
            Source source = new TestSource("test-source");
            
            assertEquals(-1, source.getDocumentCount());
            assertEquals(-1, source.getEstimatedTotalSize());
            assertEquals("test:test-source", source.getDescription());
            
            source.close();
        }

        @Test
        @DisplayName("crawl returns document IDs")
        void crawlReturnsDocumentIds() throws SourceException {
            Source source = new TestSource("test");
            
            try (Stream<String> ids = source.crawl()) {
                List<String> idList = ids.toList();
                assertEquals(3, idList.size());
                assertTrue(idList.contains("doc-1"));
                assertTrue(idList.contains("doc-2"));
                assertTrue(idList.contains("doc-3"));
            }
        }

        @Test
        @DisplayName("hasChanged returns correct value")
        void hasChangedReturnsCorrectValue() throws SourceException {
            TestSource source = new TestSource("test");
            
            source.setChanged(false);
            assertFalse(source.hasChanged(Instant.now().minusSeconds(3600)));
            
            source.setChanged(true);
            assertTrue(source.hasChanged(Instant.now().minusSeconds(3600)));
        }

        @Test
        @DisplayName("fetch returns document content")
        void fetchReturnsDocumentContent() throws SourceException, DocumentNotFoundException {
            Source source = new TestSource("test");
            
            FetchedDocument doc = source.fetch("doc-1");
            assertEquals("doc-1", doc.getDocumentId());
            assertEquals("text/plain", doc.getContentType());
            assertTrue(doc.getContentLength() > 0);
        }

        @Test
        @DisplayName("fetch throws DocumentNotFoundException for missing documents")
        void fetchThrowsForMissingDocuments() {
            Source source = new TestSource("test");
            
            assertThrows(DocumentNotFoundException.class, () -> source.fetch("nonexistent"));
        }
    }

    /**
     * Simple test implementation of Source for unit testing.
     */
    private static class TestSource implements Source {
        private final String sourceId;
        private boolean changed = true;
        private final List<String> documentIds = List.of("doc-1", "doc-2", "doc-3");

        TestSource(String sourceId) {
            this.sourceId = sourceId;
        }

        void setChanged(boolean changed) {
            this.changed = changed;
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }

        @Override
        public String getSourceType() {
            return "test";
        }

        @Override
        public Stream<String> crawl() {
            return documentIds.stream();
        }

        @Override
        public boolean hasChanged(Instant since) {
            return changed;
        }

        @Override
        public FetchedDocument fetch(String documentId) throws DocumentNotFoundException {
            if (!documentIds.contains(documentId)) {
                throw new DocumentNotFoundException(documentId, sourceId);
            }
            byte[] content = ("Content of " + documentId).getBytes();
            return new FetchedDocument(documentId, content, "text/plain", System.currentTimeMillis());
        }
    }
}
