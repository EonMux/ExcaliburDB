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
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What {@code shutdown(Duration)} has to be true of before it says it is finished.
 */
@DisplayName("QueryPipeline shutdown")
class QueryPipelineShutdownTest {

    /** Far longer than any query here takes to finish. */
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    private QueryPipeline pipeline;
    private SegmentCatalog catalog;

    /** Set to make the next query's snapshot stop inside the storage engine until released. */
    private final AtomicBoolean holdQueries = new AtomicBoolean();
    private final CountDownLatch queryHeld = new CountDownLatch(1);
    private final CountDownLatch releaseQuery = new CountDownLatch(1);

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        catalog = new SegmentCatalog(tempDir.resolve("catalog.dat"));
        StorageEngine storageEngine = mock(StorageEngine.class);
        when(storageEngine.getCatalog()).thenAnswer(call -> {
            if (holdQueries.get()) {
                queryHeld.countDown();
                awaitUninterruptibly(releaseQuery);
            }
            return catalog;
        });
        when(storageEngine.getSegment(anyLong())).thenReturn(null);
        VisibilityTracker visibility = mock(VisibilityTracker.class);

        pipeline = new QueryPipeline(
            storageEngine, visibility, id -> null, L2Distance.getInstance());
    }

    @AfterEach
    void tearDown() throws Exception {
        releaseQuery.countDown();
        catalog.close();
    }

    /** Stands in for a read the thread cannot be talked out of: an interrupt does not end it. */
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void startAHeldQuery() throws InterruptedException {
        holdQueries.set(true);
        pipeline.execute(QueryRequest.builder()
            .queryVector(new float[]{0.1f, 0.2f})
            .topK(1)
            .timeoutMs(60_000)
            .build(), new CancellationToken());
        assertThat(queryHeld.await(10, TimeUnit.SECONDS))
            .as("the query reached the storage engine on a pipeline thread")
            .isTrue();
    }

    @Test
    @DisplayName("waits out the whole of the wait it is given before reporting a query still "
        + "running")
    void waitsAsLongAsItIsGiven() throws Exception {
        startAHeldQuery();
        Duration wait = Duration.ofMillis(300);

        long began = System.nanoTime();
        assertThat(pipeline.shutdown(wait))
            .as("the query is inside a read it cannot leave until released")
            .isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - began))
            .as("the query was given all of the wait the shutdown was handed")
            .isGreaterThanOrEqualTo(wait);
        assertThat(pipeline.isShutdown())
            .as("a query still reading means the pipeline has not stopped")
            .isFalse();

        releaseQuery.countDown();
        assertThat(pipeline.shutdown(SHUTDOWN_WAIT))
            .as("given longer, the shutdown sees the query end")
            .isTrue();
        assertThat(pipeline.isShutdown()).isTrue();
    }

    @Test
    @DisplayName("a query that ends inside the wait ends the shutdown early and clean")
    void aQueryThatEndsInTimeIsClean() throws Exception {
        startAHeldQuery();
        Thread releaser = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            releaseQuery.countDown();
        });

        long began = System.nanoTime();
        assertThat(pipeline.shutdown(SHUTDOWN_WAIT)).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - began))
            .as("the shutdown returned when the query ended, not when its wait ran out")
            .isLessThan(SHUTDOWN_WAIT);
        releaser.join();
    }

    @Test
    @DisplayName("a missing wait is refused")
    void aMissingWaitIsRefused() {
        assertThatThrownBy(() -> pipeline.shutdown(null))
            .isInstanceOf(NullPointerException.class);
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

        assertThat(pipeline.shutdown(SHUTDOWN_WAIT))
            .as("shutdown reports that the threads it waited for ended")
            .isTrue();

        assertThat(pipeline.isShutdown())
            .as("shutdown returns when the pipeline has stopped, not when it has been asked to")
            .isTrue();
    }

    @Test
    @DisplayName("a pipeline that never ran a query is shut down once shutdown returns")
    void shutdownOfAnUnusedPipeline() {
        assertThat(pipeline.shutdown(SHUTDOWN_WAIT)).isTrue();

        assertThat(pipeline.isShutdown()).isTrue();
    }

    @Test
    @DisplayName("shutting down twice is not an error")
    void shutdownIsIdempotent() {
        assertThat(pipeline.shutdown(SHUTDOWN_WAIT)).isTrue();
        assertThat(pipeline.shutdown(SHUTDOWN_WAIT)).isTrue();

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
        pipeline.shutdown(SHUTDOWN_WAIT);

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
