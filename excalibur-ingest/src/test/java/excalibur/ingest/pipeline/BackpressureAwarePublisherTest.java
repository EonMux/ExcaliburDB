package excalibur.ingest.pipeline;

import excalibur.shard.IndexOrchestrator;
import excalibur.ingest.registry.DocumentMetadata;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.visibility.DocumentVisibilityRegistry;
import excalibur.filter.CuckooFilter;
import io.excalibur.store.VisibilityTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link BackpressureAwarePublisher}.
 */
@DisplayName("BackpressureAwarePublisher Tests")
class BackpressureAwarePublisherTest {

    private IndexOrchestrator mockOrchestrator;
    private DocumentBatchPublisher mockDelegate;
    private BackpressureAwarePublisher publisher;

    @BeforeEach
    void setUp() {
        mockOrchestrator = mock(IndexOrchestrator.class);
        mockDelegate = mock(DocumentBatchPublisher.class);
        publisher = new BackpressureAwarePublisher(mockOrchestrator, mockDelegate, 5000);
    }

    @Nested
    @DisplayName("Constructor Validation")
    class ConstructorValidation {

        @Test
        @DisplayName("should reject null orchestrator")
        void shouldRejectNullOrchestrator() {
            assertThrows(NullPointerException.class, () ->
                new BackpressureAwarePublisher(null, mockDelegate));
        }

        @Test
        @DisplayName("should reject null delegate")
        void shouldRejectNullDelegate() {
            assertThrows(NullPointerException.class, () ->
                new BackpressureAwarePublisher(mockOrchestrator, null));
        }

        @Test
        @DisplayName("should reject negative timeout")
        void shouldRejectNegativeTimeout() {
            assertThrows(IllegalArgumentException.class, () ->
                new BackpressureAwarePublisher(mockOrchestrator, mockDelegate, -1));
        }

        @Test
        @DisplayName("should accept default timeout")
        void shouldAcceptDefaultTimeout() {
            var pub = new BackpressureAwarePublisher(mockOrchestrator, mockDelegate);
            assertEquals(BackpressureAwarePublisher.DEFAULT_BACKPRESSURE_TIMEOUT_MS,
                pub.getBackpressureTimeoutMs());
        }
    }

    @Nested
    @DisplayName("Backpressure Behavior")
    class BackpressureBehavior {

        @Test
        @DisplayName("should publish immediately when not backpressured")
        void shouldPublishImmediatelyWhenNotBackpressured() {
            // Given: no backpressure
            when(mockOrchestrator.isBackpressured()).thenReturn(false);
            DocumentBatchBuffer buffer = createMockBuffer();
            DocumentMetadata metadata = createMockMetadata();
            DocumentBatchPublisher.PublicationResult expectedResult =
                new DocumentBatchPublisher.PublicationResult(100L, 5);
            when(mockDelegate.publish(buffer, metadata)).thenReturn(expectedResult);

            // When
            var result = publisher.publish(buffer, metadata);

            // Then
            assertEquals(expectedResult, result);
            verify(mockOrchestrator, never()).awaitBackpressureRelease(anyLong());
            verify(mockDelegate).publish(buffer, metadata);
        }

        @Test
        @DisplayName("should wait for backpressure release before publishing")
        void shouldWaitForBackpressureReleaseBeforePublishing() {
            // Given: backpressure that releases quickly
            when(mockOrchestrator.isBackpressured())
                .thenReturn(true)   // First check
                .thenReturn(false); // After await
            when(mockOrchestrator.awaitBackpressureRelease(5000)).thenReturn(true);
            DocumentBatchBuffer buffer = createMockBuffer();
            DocumentMetadata metadata = createMockMetadata();
            DocumentBatchPublisher.PublicationResult expectedResult =
                new DocumentBatchPublisher.PublicationResult(101L, 3);
            when(mockDelegate.publish(buffer, metadata)).thenReturn(expectedResult);

            // When
            var result = publisher.publish(buffer, metadata);

            // Then
            assertEquals(expectedResult, result);
            verify(mockOrchestrator).awaitBackpressureRelease(5000);
            verify(mockDelegate).publish(buffer, metadata);
        }

        @Test
        @DisplayName("should throw on backpressure timeout")
        void shouldThrowOnBackpressureTimeout() {
            // Given: backpressure that never releases
            when(mockOrchestrator.isBackpressured()).thenReturn(true);
            when(mockOrchestrator.awaitBackpressureRelease(5000)).thenReturn(false);
            when(mockOrchestrator.getHnswQueueDepth()).thenReturn(150000L);
            when(mockOrchestrator.getHnswQueueDepthLimit()).thenReturn(100000);
            DocumentBatchBuffer buffer = createMockBuffer();
            DocumentMetadata metadata = createMockMetadata();

            // When/Then
            assertThrows(DocumentBatchPublisher.PublicationException.class,
                () -> publisher.publish(buffer, metadata));

            verify(mockDelegate, never()).publish(any(), any());
        }
    }

    @Nested
    @DisplayName("Bulk Publication")
    class BulkPublication {

        @Test
        @DisplayName("should publish bulk immediately when not backpressured")
        void shouldPublishBulkImmediatelyWhenNotBackpressured() {
            // Given: no backpressure
            when(mockOrchestrator.isBackpressured()).thenReturn(false);
            List<DocumentBatchBuffer> buffers = List.of(createMockBuffer());
            List<DocumentMetadata> metadataList = List.of(createMockMetadata());
            DocumentBatchPublisher.BulkPublicationResult expectedResult =
                new DocumentBatchPublisher.BulkPublicationResult(100L, 5, 1);
            when(mockDelegate.publishBulk(buffers, metadataList)).thenReturn(expectedResult);

            // When
            var result = publisher.publishBulk(buffers, metadataList);

            // Then
            assertEquals(expectedResult, result);
            verify(mockOrchestrator, never()).awaitBackpressureRelease(anyLong());
        }

        @Test
        @DisplayName("should wait for backpressure before bulk publish")
        void shouldWaitForBackpressureBeforeBulkPublish() {
            // Given: backpressure that releases
            when(mockOrchestrator.isBackpressured())
                .thenReturn(true)
                .thenReturn(false);
            when(mockOrchestrator.awaitBackpressureRelease(5000)).thenReturn(true);
            List<DocumentBatchBuffer> buffers = List.of(createMockBuffer());
            List<DocumentMetadata> metadataList = List.of(createMockMetadata());
            DocumentBatchPublisher.BulkPublicationResult expectedResult =
                new DocumentBatchPublisher.BulkPublicationResult(101L, 10, 2);
            when(mockDelegate.publishBulk(buffers, metadataList)).thenReturn(expectedResult);

            // When
            var result = publisher.publishBulk(buffers, metadataList);

            // Then
            assertEquals(expectedResult, result);
            verify(mockOrchestrator).awaitBackpressureRelease(5000);
        }
    }

    @Nested
    @DisplayName("Accessors")
    class Accessors {

        @Test
        @DisplayName("should return delegate")
        void shouldReturnDelegate() {
            assertEquals(mockDelegate, publisher.getDelegate());
        }

        @Test
        @DisplayName("should return orchestrator")
        void shouldReturnOrchestrator() {
            assertEquals(mockOrchestrator, publisher.getOrchestrator());
        }

        @Test
        @DisplayName("should return timeout")
        void shouldReturnTimeout() {
            assertEquals(5000, publisher.getBackpressureTimeoutMs());
        }

        @Test
        @DisplayName("should delegate currentEpoch")
        void shouldDelegateCurrentEpoch() {
            when(mockDelegate.currentEpoch()).thenReturn(12345L);
            assertEquals(12345L, publisher.currentEpoch());
        }
    }

    // Helper methods

    private DocumentBatchBuffer createMockBuffer() {
        DocumentBatchBuffer buffer = mock(DocumentBatchBuffer.class);
        when(buffer.isSealed()).thenReturn(true);
        when(buffer.getDocumentId()).thenReturn("test-doc-1");
        return buffer;
    }

    private DocumentMetadata createMockMetadata() {
        return mock(DocumentMetadata.class);
    }
}
