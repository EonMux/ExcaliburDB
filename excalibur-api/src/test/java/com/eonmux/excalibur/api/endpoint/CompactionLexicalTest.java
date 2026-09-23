package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult;
import com.eonmux.excalibur.shard.CompactionReport;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An on-demand compaction reclaims the chunk index's dead weight as well as the store's.
 *
 * <p>Replaced versions of re-crawled chunks and erased ones are tombstoned in the lexical index
 * too, and until this runner compacted it, {@code POST /v1/compact} reported a run that had left
 * every one of them on disk.</p>
 */
@DisplayName("On-demand compaction of the chunk index")
class CompactionLexicalTest {

    private static final CompactionReport STORAGE =
        new CompactionReport(7, 3, 1, 4, 2, 0, Duration.ofMillis(250));

    private static final CompactionResult LEXICAL =
        new CompactionResult(true, 12, 2, 20000, "full rewrite");

    private static String status(CompactionRunner runner) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/compact/status"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(body);
        new CompactionStatusEndpoint(runner).handle(exchange);
        ArgumentCaptor<Integer> code = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(code.capture(), anyLong());
        assertThat(code.getValue()).isEqualTo(200);
        return body.toString(StandardCharsets.UTF_8);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 1000 && !condition.getAsBoolean(); i++) {
            Thread.sleep(5);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void awaitFinished(CompactionRunner runner) throws InterruptedException {
        await(() -> !runner.snapshot().isRunning()
            && !CompactionRunner.STATE_IDLE.equals(runner.snapshot().state()));
    }

    @Test
    @DisplayName("force reaches the chunk index, and its counts are reported beside the store's")
    void forceReachesTheChunkIndex() throws Exception {
        List<Boolean> asked = new CopyOnWriteArrayList<>();
        try (CompactionRunner runner = new CompactionRunner((force, stop) -> STORAGE, (force, stop) -> {
            asked.add(force);
            return LEXICAL;
        })) {
            runner.start(true);
            awaitFinished(runner);
            runner.start(false);
            await(() -> asked.size() == 2 && !runner.snapshot().isRunning());

            assertThat(asked).containsExactly(true, false);
            assertThat(runner.snapshot().lexical().performed()).isTrue();
            assertThat(status(runner))
                .contains("\"state\": \"completed\"")
                .contains("\"segments_compacted\": 3")
                .contains("\"lexical\": {\"performed\": true, \"segments_rewritten\": 12, "
                    + "\"segments_produced\": 2, \"chunks_removed\": 20000, "
                    + "\"groups_abandoned\": 0, "
                    + "\"reason\": \"full rewrite\"}");
        }
    }

    @Test
    @DisplayName("a policy that declines is reported as not performed, with its reason")
    void aDeclinedCompactionSaysWhy() throws Exception {
        try (CompactionRunner runner = new CompactionRunner((force, stop) -> STORAGE,
                (force, stop) -> CompactionResult.declined("not due: 1 of 200 chunk(s) tombstoned"))) {
            runner.start(false);
            awaitFinished(runner);

            assertThat(status(runner)).contains("\"lexical\": {\"performed\": false, "
                + "\"segments_rewritten\": 0, \"segments_produced\": 0, \"chunks_removed\": 0, "
                + "\"groups_abandoned\": 0, "
                + "\"reason\": \"not due: 1 of 200 chunk(s) tombstoned\"}");
        }
    }

    @Test
    @DisplayName("a chunk index that fails leaves the store's counts standing")
    void aLexicalFailureDoesNotFailTheRun() throws Exception {
        try (CompactionRunner runner = new CompactionRunner((force, stop) -> STORAGE, (force, stop) -> {
            throw new IOException("manifest is unwritable");
        })) {
            runner.start(true);
            awaitFinished(runner);

            assertThat(runner.snapshot().state()).isEqualTo(CompactionRunner.STATE_COMPLETED);
            assertThat(status(runner))
                .contains("\"segments_compacted\": 3")
                .contains("\"lexical\": {\"performed\": false, "
                    + "\"error\": \"manifest is unwritable\"}");
        }
    }

    @Test
    @DisplayName("a store that fails does not keep the chunk index from being compacted")
    void aStorageFailureStillCompactsTheChunkIndex() throws Exception {
        try (CompactionRunner runner = new CompactionRunner((force, stop) -> {
            throw new IllegalStateException("segment 3 is closed");
        }, (force, stop) -> LEXICAL)) {
            runner.start(false);
            awaitFinished(runner);

            assertThat(runner.snapshot().state()).isEqualTo(CompactionRunner.STATE_FAILED);
            assertThat(status(runner))
                .contains("segment 3 is closed")
                .doesNotContain("segments_compacted")
                .contains("\"chunks_removed\": 20000");
        }
    }

    @Test
    @DisplayName("groups a rewrite had to abandon are reported, so a partial run does not read "
        + "as a finished one")
    void abandonedGroupsAreReported() throws Exception {
        try (CompactionRunner runner = new CompactionRunner((force, stop) -> STORAGE,
                (force, stop) -> new CompactionResult(false, 0, 0, 0, 3,
                    "nothing published: 3 group(s) abandoned"))) {
            runner.start(true);
            awaitFinished(runner);

            assertThat(runner.snapshot().lexical().groupsAbandoned()).isEqualTo(3);
            assertThat(status(runner)).contains("\"lexical\": {\"performed\": false, "
                + "\"segments_rewritten\": 0, \"segments_produced\": 0, \"chunks_removed\": 0, "
                + "\"groups_abandoned\": 3, "
                + "\"reason\": \"nothing published: 3 group(s) abandoned\"}");
        }
    }

    @Test
    @DisplayName("a node without a chunk index reports no lexical block at all")
    void noChunkIndexNoBlock() throws Exception {
        try (CompactionRunner runner = new CompactionRunner((force, stop) -> STORAGE)) {
            runner.start(true);
            awaitFinished(runner);

            assertThat(runner.snapshot().lexical()).isNull();
            assertThat(status(runner)).doesNotContain("lexical");
        }
    }

    @Test
    @DisplayName("a run stopped while the store is compacting leaves the chunk index alone")
    void aCancelBeforeTheChunkIndexSkipsIt() throws Exception {
        CountDownLatch storageStarted = new CountDownLatch(1);
        AtomicBoolean lexicalRan = new AtomicBoolean();
        try (CompactionRunner runner = new CompactionRunner((force, stop) -> {
            storageStarted.countDown();
            // Stands in for the segment boundaries, where a real compaction reads the stop.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!stop.getAsBoolean() && System.nanoTime() < deadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            return STORAGE;
        }, (force, stop) -> {
            lexicalRan.set(true);
            return LEXICAL;
        })) {
            runner.start(true);
            assertThat(storageStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(runner.cancel()).isTrue();
            awaitFinished(runner);

            assertThat(runner.snapshot().state()).isEqualTo(CompactionRunner.STATE_CANCELLED);
            assertThat(lexicalRan).isFalse();
            assertThat(status(runner)).contains("\"lexical\": {\"performed\": false, "
                + "\"segments_rewritten\": 0, \"segments_produced\": 0, \"chunks_removed\": 0, "
                + "\"groups_abandoned\": 0, "
                + "\"reason\": \"the run was asked to stop before the chunk index was compacted\"}");
        }
    }

    /**
     * An interrupt reaching a thread in the middle of file I/O closes the channel it was using,
     * and the chunk index shares its channels with every query. So a cancel arriving while the
     * index is being rewritten reaches it only as the cooperative stop request, and a rewrite
     * that has no boundary left to stop at finishes and is reported.
     */
    @Test
    @DisplayName("a cancel arriving while the chunk index is rewriting never interrupts it")
    void theChunkIndexIsNeverInterrupted() throws Exception {
        CountDownLatch lexicalStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try (CompactionRunner runner = new CompactionRunner((force, stop) -> STORAGE, (force, stop) -> {
            lexicalStarted.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
            interrupted.compareAndSet(false, Thread.currentThread().isInterrupted());
            return LEXICAL;
        })) {
            runner.start(true);
            assertThat(lexicalStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(runner.cancel()).isTrue();
            release.countDown();
            awaitFinished(runner);

            assertThat(interrupted).as("the rewrite ran to its end undisturbed").isFalse();
            assertThat(runner.snapshot().state()).isEqualTo(CompactionRunner.STATE_CANCELLED);
            assertThat(runner.snapshot().lexical().chunksRemoved()).isEqualTo(20000);
        }
    }
}
