package com.eonmux.excalibur.query.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a query executor's shutdown owes the caller that closes the storage next: no interrupt
 * of the work still running, and a true answer about whether it has ended.
 */
@DisplayName("ExecutorDrain")
class ExecutorDrainTest {

    @Test
    @DisplayName("work still running is neither interrupted nor claimed ended")
    void runningWorkIsReportedNotInterrupted() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        executor.submit(() -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });
        assertThat(inside.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(ExecutorDrain.drain(executor, 50, "Test work"))
            .as("a drain whose wait ran out must say so")
            .isFalse();
        assertThatThrownBy(() -> executor.submit(() -> { }))
            .as("no new work is taken once the drain has begun")
            .isInstanceOf(RejectedExecutionException.class);

        release.countDown();
        assertThat(ExecutorDrain.drain(executor, TimeUnit.SECONDS.toMillis(30), "Test work"))
            .as("a later drain waits again and reports the work ended")
            .isTrue();
        assertThat(interrupted)
            .as("the running work was never interrupted")
            .isFalse();
    }

    @Test
    @DisplayName("an idle executor drains at once")
    void idleExecutorDrains() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        assertThat(ExecutorDrain.drain(executor, 0, "Idle")).isTrue();
        assertThat(ExecutorDrain.drain(executor, -1, "Idle")).isTrue();
    }

    @Test
    @DisplayName("a drain whose own thread is interrupted reports work still running")
    void interruptedDrainReportsRunning() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(() -> {
            inside.countDown();
            release.await();
            return null;
        });
        assertThat(inside.await(30, TimeUnit.SECONDS)).isTrue();

        Thread.currentThread().interrupt();
        try {
            assertThat(ExecutorDrain.drain(executor, TimeUnit.MINUTES.toMillis(5), "Test work"))
                .isFalse();
            assertThat(Thread.currentThread().isInterrupted())
                .as("the caller's interrupt is kept for it to see")
                .isTrue();
        } finally {
            Thread.interrupted();
            release.countDown();
        }
        assertThat(ExecutorDrain.drain(executor, TimeUnit.SECONDS.toMillis(30), "Test work")).isTrue();
    }

    @Test
    @DisplayName("a null executor is refused")
    void nullExecutorIsRefused() {
        assertThatThrownBy(() -> ExecutorDrain.drain(null, 0, "None"))
            .isInstanceOf(NullPointerException.class);
    }
}
