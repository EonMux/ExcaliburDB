package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceResolver;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.ReindexOperationHandler;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration tests for scheduled reindex functionality.
 *
 * <p>Tests verify that when {@link ExcaliburDBServer#withReindexer(DocumentReindexer, SourceResolver)}
 * is called, the {@link ReindexOperationHandler} the scheduler dispatches to is installed and
 * runs the {@link DocumentReindexer}. The scheduler's own dispatch to that handler is covered by
 * the shard module's tests.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Scheduled reindex should execute DocumentReindexer when configured</li>
 *   <li>ReindexOperationHandler should be null before withReindexer is called</li>
 *   <li>ReindexOperationHandler should be non-null after withReindexer is called</li>
 * </ul>
 */
class ScheduledReindexIntegrationTest {

    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;
    private QueryPipeline queryPipeline;
    private SegmentCatalog segmentCatalog;
    private ExcaliburDBServer server;

    @BeforeEach
    void setUp() {
        segmentCatalog = new SegmentCatalog();

        // Create a real IndexOrchestrator (not a spy) to test the actual wiring
        storageEngine = mock(StorageEngine.class);
        when(storageEngine.arena()).thenReturn(null);
        when(storageEngine.getCatalog()).thenReturn(segmentCatalog);

        indexOrchestrator = new IndexOrchestrator(segmentCatalog, storageEngine);
        queryPipeline = mock(QueryPipeline.class);

        // Create server but don't start it - we need to configure reindexer first
        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Nested
    @DisplayName("ReindexOperationHandler wiring")
    class HandlerWiringTests {

        @Test
        @DisplayName("ReindexOperationHandler is null before withReindexer is called")
        void handlerIsNullBeforeConfiguration() {
            assertThat(indexOrchestrator.getReindexOperationHandler()).isNull();
        }

        @Test
        @DisplayName("withReindexer(DocumentReindexer, SourceResolver) wires ReindexOperationHandler")
        void withReindexerWiresHandler() {
            // Setup mock reindexer and resolver
            DocumentReindexer mockReindexer = mock(DocumentReindexer.class);
            SourceResolver mockResolver = mock(SourceResolver.class);

            // Configure reindexer
            server.withReindexer(mockReindexer, mockResolver);

            // Verify handler is now wired
            ReindexOperationHandler handler = indexOrchestrator.getReindexOperationHandler();
            assertThat(handler).isNotNull();
        }

        @Test
        @DisplayName("withReindexer(DocumentReindexer) wires ReindexOperationHandler")
        void withReindexerOverloadWiresHandler() {
            // Setup mock reindexer
            DocumentReindexer mockReindexer = mock(DocumentReindexer.class);

            // Configure reindexer with automatic source resolution
            server.withReindexer(mockReindexer);

            // Verify handler is now wired
            ReindexOperationHandler handler = indexOrchestrator.getReindexOperationHandler();
            assertThat(handler).isNotNull();
        }

        @Test
        @DisplayName("withReindexer(null, null) clears ReindexOperationHandler")
        void withNullReindexerClearsHandler() {
            // First configure with a mock
            DocumentReindexer mockReindexer = mock(DocumentReindexer.class);
            SourceResolver mockResolver = mock(SourceResolver.class);
            server.withReindexer(mockReindexer, mockResolver);

            // Verify handler is set
            assertThat(indexOrchestrator.getReindexOperationHandler()).isNotNull();

            // Now clear it
            server.withReindexer(null, null);

            // Verify handler is cleared
            assertThat(indexOrchestrator.getReindexOperationHandler()).isNull();
        }
    }

    @Nested
    @DisplayName("Scheduled reindex execution")
    class ScheduledReindexExecutionTests {

        @Test
        @DisplayName("scheduled reindex should execute DocumentReindexer when configured")
        void scheduledReindexExecutesReindexer() throws Exception {
            // Setup mock reindexer with successful result
            DocumentReindexer mockReindexer = mock(DocumentReindexer.class);
            Source mockSource = mock(Source.class);
            SourceResolver mockResolver = mock(SourceResolver.class);

            DocumentReindexer.ReindexResult mockResult = mock(DocumentReindexer.ReindexResult.class);
            when(mockResult.hasErrors()).thenReturn(false);
            when(mockSource.getSourceId()).thenReturn("1");
            when(mockResolver.resolve("1")).thenReturn(mockSource);
            when(mockReindexer.reindexAll(eq(mockSource), any(), any())).thenReturn(mockResult);

            // Configure reindexer
            server.withReindexer(mockReindexer, mockResolver);

            // Trigger reindex directly
            boolean triggered = indexOrchestrator.getReindexOperationHandler()
                .executeReindex("1");

            // Verify reindex was triggered and reindexer was called
            assertThat(triggered).isTrue();
            verify(mockReindexer, times(1)).reindexAll(eq(mockSource), any(), any());
        }

        @Test
        @DisplayName("scheduled reindex returns false when source not found")
        void scheduledReindexReturnsFalseWhenSourceNotFound() throws Exception {
            // Setup mock reindexer
            DocumentReindexer mockReindexer = mock(DocumentReindexer.class);
            SourceResolver mockResolver = mock(SourceResolver.class);

            // Resolver returns null for unknown source
            when(mockResolver.resolve("999")).thenReturn(null);

            // Configure reindexer
            server.withReindexer(mockReindexer, mockResolver);

            // Trigger reindex - should fail because source not found
            boolean triggered = indexOrchestrator.getReindexOperationHandler()
                .executeReindex("999");

            // Verify reindex was not triggered (returns false)
            assertThat(triggered).isFalse();
            verify(mockReindexer, never()).reindexAll(any(), any(), any());
        }

        @Test
        @DisplayName("scheduled reindex returns false when DocumentReindexer throws exception")
        void scheduledReindexReturnsFalseOnException() throws Exception {
            // Setup mock reindexer that throws exception
            DocumentReindexer mockReindexer = mock(DocumentReindexer.class);
            Source mockSource = mock(Source.class);
            SourceResolver mockResolver = mock(SourceResolver.class);

            when(mockSource.getSourceId()).thenReturn("2");
            when(mockResolver.resolve("2")).thenReturn(mockSource);
            when(mockReindexer.reindexAll(eq(mockSource), any(), any()))
                .thenThrow(new RuntimeException("Simulated reindex failure"));

            // Configure reindexer
            server.withReindexer(mockReindexer, mockResolver);

            // Trigger reindex - should fail because of exception
            boolean triggered = indexOrchestrator.getReindexOperationHandler()
                .executeReindex("2");

            // Verify reindex was attempted but returned false due to exception
            assertThat(triggered).isFalse();
            verify(mockReindexer, times(1)).reindexAll(eq(mockSource), any(), any());
        }

        @Test
        @DisplayName("scheduled reindex returns false when ReindexResult has errors")
        void scheduledReindexReturnsFalseWhenResultHasErrors() throws Exception {
            // Setup mock reindexer with error result
            DocumentReindexer mockReindexer = mock(DocumentReindexer.class);
            Source mockSource = mock(Source.class);
            SourceResolver mockResolver = mock(SourceResolver.class);

            DocumentReindexer.ReindexResult mockResult = mock(DocumentReindexer.ReindexResult.class);
            when(mockResult.hasErrors()).thenReturn(true);
            when(mockSource.getSourceId()).thenReturn("3");
            when(mockResolver.resolve("3")).thenReturn(mockSource);
            when(mockReindexer.reindexAll(eq(mockSource), any(), any())).thenReturn(mockResult);

            // Configure reindexer
            server.withReindexer(mockReindexer, mockResolver);

            // Trigger reindex - should return false because result has errors
            boolean triggered = indexOrchestrator.getReindexOperationHandler()
                .executeReindex("3");

            // Verify reindex was triggered but returned false due to errors
            assertThat(triggered).isFalse();
            verify(mockReindexer, times(1)).reindexAll(eq(mockSource), any(), any());
        }
    }

    @Nested
    @DisplayName("getIndexOrchestrator accessor")
    class GetIndexOrchestratorTests {

        @Test
        @DisplayName("getIndexOrchestrator returns non-null orchestrator")
        void getIndexOrchestratorReturnsNonNull() {
            IndexOrchestrator orchestrator = server.getIndexOrchestrator();
            assertThat(orchestrator).isNotNull();
            assertThat(orchestrator).isSameAs(indexOrchestrator);
        }
    }
}
