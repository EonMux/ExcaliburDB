package com.eonmux.excalibur.api.bootstrap;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import com.eonmux.excalibur.graph.browse.GraphCatalog;
import com.eonmux.excalibur.retrieval.pipeline.GraphRagPipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class GraphRagHolderTest {

    private static final Path DIRECTORY = Path.of("graph");

    /** A stack whose only interest is whether it was closed. */
    private static GraphRagFactory.GraphRag stack(AtomicBoolean closed) {
        return new GraphRagFactory.GraphRag(mock(GraphRagPipeline.class), mention -> new long[0],
            mock(GraphCatalog.class),
            List.of(() -> closed.set(true)), DIRECTORY);
    }

    @Test
    @DisplayName("a replaced stack closes once its last reader is done, not before")
    void replacedStackDrainsFirst() {
        AtomicBoolean firstClosed = new AtomicBoolean();
        try (GraphRagHolder holder = new GraphRagHolder(DIRECTORY, stack(firstClosed))) {
            GraphRagHolder.Reading reading = holder.acquire().orElseThrow();

            holder.install(stack(new AtomicBoolean()));

            assertThat(firstClosed).isFalse();
            reading.close();
            assertThat(firstClosed).isTrue();
        }
    }

    @Test
    @DisplayName("an install into a closed holder is refused and the stack it brought is closed")
    void installAfterCloseIsRefused() {
        GraphRagHolder holder = new GraphRagHolder(DIRECTORY, null);
        holder.close();
        AtomicBoolean closed = new AtomicBoolean();

        assertThatThrownBy(() -> holder.install(stack(closed)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(closed).isTrue();
        assertThat(holder.isPresent()).isFalse();
    }

    @Test
    @DisplayName("an install racing a close never leaves a stack open behind a closed holder")
    void installRacingCloseLeaksNothing() throws Exception {
        for (int round = 0; round < 2_000; round++) {
            GraphRagHolder holder = new GraphRagHolder(DIRECTORY, null);
            AtomicBoolean closed = new AtomicBoolean();
            CountDownLatch start = new CountDownLatch(1);
            Thread installer = Thread.ofPlatform().start(() -> {
                awaitQuietly(start);
                try {
                    holder.install(stack(closed));
                } catch (IllegalStateException refused) {
                    // Lost the race to the close; the stack is closed by the refusal.
                }
            });
            Thread closer = Thread.ofPlatform().start(() -> {
                awaitQuietly(start);
                holder.close();
            });
            start.countDown();
            installer.join();
            closer.join();

            assertThat(closed).as("round %d", round).isTrue();
            assertThat(holder.isPresent()).as("round %d", round).isFalse();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
