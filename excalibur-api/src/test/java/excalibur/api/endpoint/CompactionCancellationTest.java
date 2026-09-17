package excalibur.api.endpoint;

import excalibur.shard.CompactionReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a compaction an operator regrets can be stopped.
 *
 * <p>A forced compaction rewrites every live segment and rebuilds every graph. On a large
 * corpus that is minutes of saturated I/O, and the operator who started it during business
 * hours by mistake had no way to end it: the runner's only stop was process shutdown. The
 * mechanism was already safe - a rewrite builds the replacement beside the original and swaps
 * it in at the end, so an interrupted run loses its own work and never data, and
 * {@code compactNow} stops at the next segment boundary and returns what it had done - it was
 * simply not reachable.</p>
 *
 * <p>Stopping is a request, as it is for a reindex: the run ends at the next boundary rather
 * than the instant the call returns.</p>
 */
@DisplayName("Cancelling an on-demand compaction")
class CompactionCancellationTest {

    private static final CompactionReport NOTHING =
        new CompactionReport(0, 0, 0, 0, 0, 0, java.time.Duration.ZERO);

    @Nested
    @DisplayName("the runner")
    class TheRunner {

        @Test
        @DisplayName("a run in progress is asked to stop, and reports itself cancelled")
        void aRunInProgressIsAskedToStop() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean();
            CompactionRunner runner = new CompactionRunner(force -> {
                started.countDown();
                try {
                    // Stands in for the next segment boundary: real compaction checks the
                    // interrupt flag between segments and returns what it had done by then.
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
                return NOTHING;
            });
            try {
                runner.start(true);
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

                assertThat(runner.cancel())
                    .as("a run was in progress, so there was something to stop")
                    .isTrue();

                assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(interrupted).isTrue();
                await(() -> CompactionRunner.STATE_CANCELLED.equals(runner.snapshot().state()));
                assertThat(runner.snapshot().state())
                    .isEqualTo(CompactionRunner.STATE_CANCELLED);
            } finally {
                runner.close();
            }
        }

        @Test
        @DisplayName("cancelling when nothing is running says so rather than failing")
        void cancellingNothingIsNotAnError() {
            CompactionRunner runner = new CompactionRunner(force -> NOTHING);
            try {
                assertThat(runner.cancel()).isFalse();
                assertThat(runner.snapshot().state()).isEqualTo(CompactionRunner.STATE_IDLE);
            } finally {
                runner.close();
            }
        }

        @Test
        @DisplayName("a run after a cancelled one starts clean")
        void theNextRunStartsClean() throws Exception {
            // The runner reuses one thread. A cancelled run that left the interrupt flag set
            // on it would make the next run die the moment it touched anything blocking,
            // which reads as a compaction that failed for no reason anyone can see.
            CountDownLatch started = new CountDownLatch(1);
            AtomicBoolean secondSawInterrupt = new AtomicBoolean();
            CompactionRunner runner = new CompactionRunner(force -> {
                if (started.getCount() > 0) {
                    started.countDown();
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return NOTHING;
                }
                secondSawInterrupt.set(Thread.currentThread().isInterrupted());
                return NOTHING;
            });
            try {
                runner.start(true);
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                runner.cancel();
                await(() -> !runner.snapshot().isRunning());

                assertThat(runner.start(false)).isNotNull();
                await(() -> CompactionRunner.STATE_COMPLETED.equals(runner.snapshot().state()));
                assertThat(secondSawInterrupt)
                    .as("the cancelled run must not leave its interrupt on the pool thread")
                    .isFalse();
            } finally {
                runner.close();
            }
        }
    }

    @Nested
    @DisplayName("the route")
    class TheRoute {

        @Test
        @DisplayName("DELETE says whether there was anything to stop")
        void deleteSaysWhetherAnythingWasRunning() throws Exception {
            CompactionRunner runner = new CompactionRunner(force -> NOTHING);
            try {
                Response response = call(new CompactionCancelEndpoint(runner), "DELETE");

                assertThat(response.status()).isEqualTo(200);
                assertThat(response.body()).contains("\"cancelled\": false");
            } finally {
                runner.close();
            }
        }

        @Test
        @DisplayName("any other method is refused")
        void otherMethodsAreRefused() throws Exception {
            CompactionRunner runner = new CompactionRunner(force -> NOTHING);
            try {
                assertThat(call(new CompactionCancelEndpoint(runner), "GET").status())
                    .isEqualTo(405);
            } finally {
                runner.close();
            }
        }
    }

    private record Response(int status, String body) { }

    private static Response call(CompactionCancelEndpoint endpoint, String method)
            throws java.io.IOException {
        com.sun.net.httpserver.HttpExchange exchange =
            org.mockito.Mockito.mock(com.sun.net.httpserver.HttpExchange.class);
        java.io.ByteArrayOutputStream responseBody = new java.io.ByteArrayOutputStream();
        org.mockito.Mockito.when(exchange.getRequestMethod()).thenReturn(method);
        org.mockito.Mockito.when(exchange.getRequestURI())
            .thenReturn(java.net.URI.create("/v1/compact"));
        org.mockito.Mockito.when(exchange.getRequestHeaders())
            .thenReturn(new com.sun.net.httpserver.Headers());
        org.mockito.Mockito.when(exchange.getResponseHeaders())
            .thenReturn(new com.sun.net.httpserver.Headers());
        org.mockito.Mockito.when(exchange.getResponseBody()).thenReturn(responseBody);

        endpoint.handle(exchange);

        org.mockito.ArgumentCaptor<Integer> status =
            org.mockito.ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(exchange)
            .sendResponseHeaders(status.capture(), org.mockito.ArgumentMatchers.anyLong());
        return new Response(status.getValue(),
            responseBody.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("condition was not reached within 5s");
    }
}
