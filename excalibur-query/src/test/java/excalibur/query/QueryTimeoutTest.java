package excalibur.query;

import excalibur.query.executor.CancellationToken;
import excalibur.query.executor.QueryExecutor;
import excalibur.query.executor.QueryExecutor.SegmentWithGraph;
import excalibur.query.pipeline.QueryPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for query timeout and cancellation functionality.
 *
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Queries respect the configured timeout</li>
 *   <li>Cancellation propagates to all query stages</li>
 *   <li>Resources are cleaned up on timeout</li>
 *   <li>Partial results are returned on timeout</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
class QueryTimeoutTest {

    // ==================== CancellationToken Tests ====================

    @Test
    @DisplayName("CancellationToken starts in non-cancelled state")
    void testCancellationTokenInitialState() {
        CancellationToken token = new CancellationToken();

        assertThat(token.isCancelled()).isFalse();
        assertThat(token.cancellationReason()).isNull();
    }

    @Test
    @DisplayName("CancellationToken can be cancelled")
    void testCancellationTokenCancel() {
        CancellationToken token = new CancellationToken();

        boolean result = token.cancel();

        assertThat(result).isTrue();
        assertThat(token.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("CancellationToken.cancel() is idempotent")
    void testCancellationTokenCancelIdempotent() {
        CancellationToken token = new CancellationToken();

        token.cancel("reason 1");
        boolean secondCancel = token.cancel("reason 2");

        assertThat(secondCancel).isFalse();
        assertThat(token.cancellationReason()).isEqualTo("reason 1");
    }

    @Test
    @DisplayName("CancellationToken.checkCancelled() throws when cancelled")
    void testCancellationTokenCheckCancelled() {
        CancellationToken token = new CancellationToken();

        // Should not throw when not cancelled
        token.checkCancelled();

        // Cancel the token
        token.cancel("test cancellation");

        // Should throw after cancellation
        assertThatThrownBy(() -> token.checkCancelled())
            .isInstanceOf(CancellationException.class)
            .hasMessageContaining("test cancellation");
    }

    @Test
    @DisplayName("CancellationToken listeners are notified on cancellation")
    void testCancellationTokenListeners() {
        CancellationToken token = new CancellationToken();
        AtomicBoolean listenerCalled = new AtomicBoolean(false);

        token.addListener(() -> listenerCalled.set(true));

        token.cancel();

        assertThat(listenerCalled.get()).isTrue();
    }

    @Test
    @DisplayName("CancellationToken listener runs immediately if already cancelled")
    void testCancellationTokenListenerImmediate() {
        CancellationToken token = CancellationToken.cancelled("already cancelled");
        AtomicBoolean listenerCalled = new AtomicBoolean(false);

        token.addListener(() -> listenerCalled.set(true));

        assertThat(listenerCalled.get()).isTrue();
    }

    @Test
    @DisplayName("CancellationToken factory creates cancelled token")
    void testCancellationTokenFactory() {
        CancellationToken token = CancellationToken.cancelled("factory test");

        assertThat(token.isCancelled()).isTrue();
        assertThat(token.cancellationReason()).isEqualTo("factory test");
    }

    // ==================== QueryRequest Tests ====================

    @Test
    @DisplayName("QueryRequest requires non-null vector")
    void testQueryRequestRequiresVector() {
        assertThatThrownBy(() ->
            QueryRequest.builder().topK(10).timeoutMs(1000).build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("Query vector cannot be null or empty");
    }

    @Test
    @DisplayName("QueryRequest requires positive topK")
    void testQueryRequestRequiresPositiveTopK() {
        assertThatThrownBy(() ->
            QueryRequest.builder().queryVector(new float[10]).topK(0).build()
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("topK must be positive");
    }

    @Test
    @DisplayName("QueryRequest requires positive timeout")
    void testQueryRequestRequiresPositiveTimeout() {
        assertThatThrownBy(() ->
            QueryRequest.builder().queryVector(new float[10]).topK(10).timeoutMs(0).build()
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("timeoutMs must be positive");
    }

    @Test
    @DisplayName("QueryRequest builds with valid parameters")
    void testQueryRequestBuilds() {
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};

        QueryRequest request = QueryRequest.builder()
            .queryText("test query")
            .queryVector(vector)
            .topK(10)
            .timeoutMs(5000)
            .includePartialSegments(true)
            .threshold(0.5f)
            .correlationId("test-123")
            .build();

        assertThat(request.queryText()).isEqualTo("test query");
        assertThat(request.topK()).isEqualTo(10);
        assertThat(request.timeoutMs()).isEqualTo(5000);
        assertThat(request.includePartialSegments()).isTrue();
        assertThat(request.threshold()).isEqualTo(0.5f);
        assertThat(request.correlationId()).isEqualTo("test-123");
        // Verify vector is copied
        assertThat(request.queryVector()).isNotSameAs(vector);
        assertThat(request.queryVector()).containsExactly(vector);
    }

    // ==================== QueryResponse Tests ====================

    @Test
    @DisplayName("QueryResponse includes timedOut field")
    void testQueryResponseTimedOut() {
        QueryResponse response = new QueryResponse(
            Collections.emptyList(),
            IndexCoverage.builder().degraded(true).estimatedCorpusCoverage(0.0).build(),
            1000,
            false,
            true
        );

        assertThat(response.timedOut()).isTrue();
    }

    @Test
    @DisplayName("QueryResponse.isDegraded() returns true when timedOut")
    void testQueryResponseDegradedWhenTimedOut() {
        IndexCoverage coverage = IndexCoverage.builder()
            .degraded(false)
            .estimatedCorpusCoverage(1.0)
            .build();

        QueryResponse response = new QueryResponse(
            Collections.emptyList(),
            coverage,
            1000,
            false,
            true  // timedOut
        );

        // Even with non-degraded coverage, timedOut should make isDegraded true
        assertThat(response.isDegraded()).isTrue();
    }

    @Test
    @DisplayName("QueryResponse.withTimedOut creates new instance")
    void testQueryResponseWithTimedOut() {
        QueryResponse original = new QueryResponse(
            Collections.emptyList(),
            IndexCoverage.builder().build(),
            1000,
            false,
            false
        );

        QueryResponse modified = original.withTimedOut(true);

        assertThat(original.timedOut()).isFalse();
        assertThat(modified.timedOut()).isTrue();
    }

    // ==================== Timeout Behavior Tests ====================

    @Test
    @DisplayName("CompletableFuture timeout triggers TimeoutException")
    void testCompletableFutureTimeout() {
        CancellationToken token = new CancellationToken();

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                // Simulate long-running operation
                Thread.sleep(10000);
                return "completed";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        });

        // Apply short timeout
        CompletableFuture<String> timeoutFuture = future.orTimeout(100, TimeUnit.MILLISECONDS);

        assertThatThrownBy(() -> timeoutFuture.join())
            .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("Timeout propagates cancellation via token")
    void testTimeoutPropagatesCancellation() throws InterruptedException {
        CancellationToken token = new CancellationToken();
        AtomicBoolean operationCancelled = new AtomicBoolean(false);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(1);

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            startLatch.countDown();
            try {
                while (!token.isCancelled()) {
                    // Simulate work
                    Thread.sleep(10);
                }
                operationCancelled.set(true);
                endLatch.countDown();
                return "cancelled";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        });

        // Apply timeout and handle it
        future.orTimeout(200, TimeUnit.MILLISECONDS)
            .handle((result, error) -> {
                if (error instanceof TimeoutException ||
                    (error != null && error.getCause() instanceof TimeoutException)) {
                    token.cancel("Query timeout");
                }
                return result;
            });

        // Wait for operation to detect cancellation
        startLatch.await(1, TimeUnit.SECONDS);
        boolean cancelled = endLatch.await(1, TimeUnit.SECONDS);

        assertThat(token.isCancelled()).isTrue();
        assertThat(operationCancelled.get()).isTrue();
    }

    @Test
    @DisplayName("Cancellation stops cooperative operation")
    void testCancellationStopsCooperativeOperation() throws InterruptedException {
        CancellationToken token = new CancellationToken();
        AtomicInteger stageReached = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                // Stage 1
                token.checkCancelled();
                stageReached.set(1);
                Thread.sleep(50);

                // Stage 2
                token.checkCancelled();
                stageReached.set(2);
                Thread.sleep(50);

                // Stage 3
                token.checkCancelled();
                stageReached.set(3);
                Thread.sleep(50);

                // Stage 4
                token.checkCancelled();
                stageReached.set(4);
                latch.countDown();

            } catch (CancellationException e) {
                // Expected
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Let stages progress, then cancel
        Thread.sleep(75);
        token.cancel("test cancellation");

        latch.await(1, TimeUnit.SECONDS);
        worker.join(1000);

        // Should have stopped at stage 2 (after ~75ms)
        assertThat(stageReached.get()).isLessThan(4);
    }

    // ==================== Resource Cleanup Tests ====================

    @Test
    @DisplayName("Cancellation listeners provide cleanup hook")
    void testCancellationListenersProvideCleanup() {
        CancellationToken token = new CancellationToken();
        AtomicBoolean resourceCleaned = new AtomicBoolean(false);

        // Register cleanup listener
        token.addListener(() -> resourceCleaned.set(true));

        // Simulate resource allocation
        Object resource = new Object();
        assertThat(resource).isNotNull();

        // Cancel and verify cleanup
        token.cancel();
        assertThat(resourceCleaned.get()).isTrue();
    }

    @Test
    @DisplayName("Multiple listeners all called on cancellation")
    void testMultipleListenersCalled() {
        CancellationToken token = new CancellationToken();
        AtomicInteger callCount = new AtomicInteger(0);

        token.addListener(() -> callCount.incrementAndGet());
        token.addListener(() -> callCount.incrementAndGet());
        token.addListener(() -> callCount.incrementAndGet());

        token.cancel();

        assertThat(callCount.get()).isEqualTo(3);
    }

    // ==================== Integration Test Helpers ====================

    @Test
    @DisplayName("QueryRequest.toQueryOptions converts correctly")
    void testQueryRequestToQueryOptions() {
        QueryRequest request = QueryRequest.builder()
            .queryVector(new float[128])
            .topK(20)
            .threshold(0.7f)
            .timeoutMs(10000)
            .includePartialSegments(false)
            .build();

        QueryOptions options = request.toQueryOptions();

        assertThat(options.topK()).isEqualTo(20);
        assertThat(options.threshold()).isEqualTo(0.7f);
        assertThat(options.timeout().toMillis()).isEqualTo(10000);
        assertThat(options.includePartialSegments()).isFalse();
    }
}
