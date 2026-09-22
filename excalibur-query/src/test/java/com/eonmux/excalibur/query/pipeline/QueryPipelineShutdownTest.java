package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What {@code shutdown()} has to be true of before it says it is finished.
 */
@DisplayName("QueryPipeline shutdown")
class QueryPipelineShutdownTest {

    private QueryPipeline pipeline;
    private SegmentCatalog catalog;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        catalog = new SegmentCatalog(tempDir.resolve("catalog.dat"));
        StorageEngine storageEngine = mock(StorageEngine.class);
        when(storageEngine.getCatalog()).thenReturn(catalog);
        when(storageEngine.getSegment(anyLong())).thenReturn(null);
        VisibilityTracker visibility = mock(VisibilityTracker.class);

        pipeline = new QueryPipeline(
            storageEngine, visibility, id -> null, L2Distance.getInstance());
    }

    @AfterEach
    void tearDown() throws Exception {
        catalog.close();
    }

    /**
     * The log line said the shutdown was complete without anything having checked. A caller that
     * closes the storage engine on the strength of it does so while pipeline threads may still
     * be reading through it.
     */
    @Test
    @DisplayName("does not report itself shut down until its threads have actually stopped")
    void reportsShutdownOnlyOnceThreadsHaveStopped() throws Exception {
        pipeline.execute(QueryRequest.builder()
            .queryVector(new float[]{0.1f, 0.2f})
            .topK(1)
            .timeoutMs(1_000)
            .build(), new CancellationToken())
            .get(30, TimeUnit.SECONDS);

        pipeline.shutdown();

        assertThat(pipeline.isShutdown())
            .as("shutdown() returns when the pipeline has stopped, not when it has been asked to")
            .isTrue();
    }

    @Test
    @DisplayName("a pipeline that never ran a query is shut down once shutdown returns")
    void shutdownOfAnUnusedPipeline() {
        pipeline.shutdown();

        assertThat(pipeline.isShutdown()).isTrue();
    }

    @Test
    @DisplayName("shutting down twice is not an error")
    void shutdownIsIdempotent() {
        pipeline.shutdown();
        pipeline.shutdown();

        assertThat(pipeline.isShutdown()).isTrue();
    }

    /**
     * There was no shutdown flag, only a null check on the lazily created executor. A pipeline
     * that had served no query shut down without creating one, and the next query created a
     * fresh executor and submitted work against a storage engine the shutdown sequence was
     * about to close.
     */
    @Test
    @DisplayName("stays shut down when a query arrives afterwards")
    void staysShutDownAfterALateQuery() {
        pipeline.shutdown();

        assertThatThrownBy(() -> pipeline.execute(QueryRequest.builder()
            .queryVector(new float[]{0.1f, 0.2f})
            .topK(1)
            .timeoutMs(1_000)
            .build(), new CancellationToken()).get(30, TimeUnit.SECONDS))
            .as("a query after shutdown must be refused, not served by a new executor")
            .isNotNull();
        assertThat(pipeline.isShutdown())
            .as("a late query must not quietly bring the pipeline back up")
            .isTrue();
    }
}
