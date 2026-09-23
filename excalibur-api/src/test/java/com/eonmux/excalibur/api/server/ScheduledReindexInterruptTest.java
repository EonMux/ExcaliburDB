package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.endpoint.ReindexEndpoint;
import com.eonmux.excalibur.api.endpoint.ReindexRun;
import com.eonmux.excalibur.api.reindex.ReindexCoordinator;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a cancelled reindex leaves behind on the thread that ran it.
 *
 * <p>A reindex is cancelled by interrupting the thread running it, and the reindexer restores
 * the interrupt flag on its way out so the caller can tell a cancellation from a failure. On a
 * thread that belongs to a pool, that flag is a trap: the run it was addressed to is over, and
 * the next task scheduled onto the same thread fails at its first interruptible channel
 * operation - which, for a channel, closes it for good. The scheduler thread must therefore go
 * back to the pool clean.</p>
 */
@DisplayName("Scheduled reindex and the interrupt flag")
class ScheduledReindexInterruptTest {

    /**
     * Builds a runner whose reindexer interrupts the calling thread, the way a cancelled
     * reindex does.
     *
     * @param ran Set when the reindexer is reached
     * @return The runner under test
     */
    private static ScheduledReindexRunner runnerThatIsCancelled(AtomicBoolean ran) {
        ReindexCoordinator coordinator = new ReindexCoordinator();
        DocumentReindexer reindexer = mock(DocumentReindexer.class);
        Source source = mock(Source.class);
        SourceResolver resolver = id -> source;

        try {
            when(reindexer.reindexAll(any(Source.class), any(), any())).thenAnswer(invocation -> {
                ran.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("reindex was asked to stop",
                    new InterruptedException());
            });
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        when(source.getSourceId()).thenReturn("s-1");
        ReindexEndpoint.ReindexStatusRegistry statuses =
            new ReindexEndpoint.InMemoryReindexStatusRegistry();
        // No volume manager and no catalog, so the run takes the plain reindexAll(Source) path.
        return new ScheduledReindexRunner(coordinator,
            new ReindexRun(reindexer, resolver, null, null, statuses, null), statuses,
            id -> true);
    }

    @Test
    @DisplayName("a cancelled run does not carry its interrupt back into the pool")
    void interruptDoesNotEscapeIntoThePool() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        ScheduledReindexRunner runner = runnerThatIsCancelled(ran);

        Thread worker = new Thread(() -> runner.executeReindex("s-1"));
        worker.start();
        worker.join(5000);

        assertThat(ran).as("the reindexer must actually have been reached").isTrue();
    }

    @Test
    @DisplayName("the flag is cleared on the very thread that ran the reindex")
    void flagIsClearedOnTheRunningThread() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        AtomicBoolean interruptedAfter = new AtomicBoolean(true);
        ScheduledReindexRunner runner = runnerThatIsCancelled(ran);

        Thread worker = new Thread(() -> {
            runner.executeReindex("s-1");
            interruptedAfter.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        worker.join(5000);

        assertThat(ran).isTrue();
        assertThat(interruptedAfter)
            .as("the interrupt was addressed to the run that is now over; carried away, it "
                + "fails the next task scheduled onto this thread")
            .isFalse();
    }
}
