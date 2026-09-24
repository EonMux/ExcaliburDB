package com.eonmux.excalibur.api.retrieval;

import com.eonmux.excalibur.core.text.SourceScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * That abandoning a BM25 search leaves the thread running it alone.
 *
 * <p>A request that no longer wants the lexical signal abandons it. Interrupting the search would
 * shorten nothing on a memory-mapped index, whose reads never look at the flag, and on the
 * {@code NIOFSDirectory} fallback it would close the index file for every query sharing it.</p>
 */
@DisplayName("LexicalSignal")
class LexicalSignalTest {

    @Test
    @DisplayName("cancelling abandons the search without interrupting it")
    void cancelNeverInterruptsTheSearch() throws Exception {
        CountDownLatch searching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        LexicalQueryService service = mock(LexicalQueryService.class);
        when(service.retrieve(anyString(), anyInt(), any(), any())).thenAnswer(invocation -> {
            searching.countDown();
            try {
                // Stands in for index reads, which never look at the interrupt flag.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (release.getCount() > 0 && System.nanoTime() < deadline) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                    interrupted.compareAndSet(false, Thread.currentThread().isInterrupted());
                }
            } finally {
                finished.countDown();
            }
            return new LexicalQueryService.Ranking(List.of(), null);
        });
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            LexicalSignal signal =
                LexicalSignal.start(service, executor, "query", SourceScope.ALL, 10,
                    Duration.ofSeconds(30));
            assertThat(signal).isNotNull();
            assertThat(searching.await(5, TimeUnit.SECONDS)).isTrue();

            signal.cancel();
            assertThat(signal.await(10))
                .as("an abandoned search contributes nothing to the answer")
                .isNull();
            release.countDown();

            assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted).as("the search's thread was never interrupted").isFalse();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
