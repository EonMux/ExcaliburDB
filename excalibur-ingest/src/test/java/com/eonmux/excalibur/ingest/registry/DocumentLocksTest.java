package com.eonmux.excalibur.ingest.registry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading a document without waiting behind a rewrite of it, and without reading through one.
 */
@DisplayName("Document locks, read unless rewriting")
class DocumentLocksTest {

    private static final String KEY = "docs:a.txt";

    private final DocumentLocks locks = DocumentLocks.shared();

    @Test
    @DisplayName("reads when nobody is rewriting the document, without taking its lock")
    void readsWhenFree() {
        String answer = locks.readUnlessRewriting(KEY,
            () -> locks.heldByCurrentThread(KEY) ? "held" : "read", "busy");

        assertThat(answer).isEqualTo("read");
    }

    @Test
    @DisplayName("answers busy without waiting while another thread rewrites the document")
    void busyWhileAnotherThreadHoldsIt() throws Exception {
        try (Holder writer = Holder.of(locks, KEY)) {
            assertThat(locks.readUnlessRewriting(KEY, () -> "read", "busy")).isEqualTo("busy");
        }
        assertThat(locks.readUnlessRewriting(KEY, () -> "read", "busy")).isEqualTo("read");
    }

    @Test
    @DisplayName("reads a document whose stripe another document's rewrite holds")
    void readsPastAnotherDocumentOnItsStripe() throws Exception {
        String neighbour = neighbourOf(KEY);

        try (Holder writer = Holder.of(locks, neighbour)) {
            assertThat(locks.readUnlessRewriting(KEY, () -> "read", "busy")).isEqualTo("read");
            assertThat(locks.readUnlessRewriting(neighbour, () -> "read", "busy"))
                .isEqualTo("busy");
        }
    }

    @Test
    @DisplayName("is busy for either document while a writer holds one inside the other")
    void busyWhileNested() throws Exception {
        String neighbour = neighbourOf(KEY);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread writer = Thread.ofPlatform().start(() -> locks.inDocument(neighbour, () ->
            locks.inDocument(KEY, () -> {
                holding.countDown();
                await(release);
            })));
        try {
            assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(locks.readUnlessRewriting(KEY, () -> "read", "busy")).isEqualTo("busy");
            assertThat(locks.readUnlessRewriting(neighbour, () -> "read", "busy"))
                .isEqualTo("busy");
        } finally {
            release.countDown();
            writer.join(TimeUnit.SECONDS.toMillis(30));
        }
    }

    @Test
    @DisplayName("discards a read a rewrite began during, and reads again")
    void rereadsWhenARewriteBeganDuringTheRead() {
        AtomicInteger reads = new AtomicInteger();

        String answer = locks.readUnlessRewriting(KEY, () -> {
            if (reads.incrementAndGet() == 1) {
                rewriteElsewhere(KEY);
                return "torn";
            }
            return "whole";
        }, "busy");

        assertThat(answer).isEqualTo("whole");
        assertThat(reads).hasValue(2);
    }

    @Test
    @DisplayName("gives up as busy when every read is overtaken by a rewrite")
    void busyWhenEveryReadIsOvertaken() {
        AtomicInteger reads = new AtomicInteger();

        String answer = locks.readUnlessRewriting(KEY, () -> {
            reads.incrementAndGet();
            rewriteElsewhere(KEY);
            return "torn";
        }, "busy");

        assertThat(answer).isEqualTo("busy");
        assertThat(reads.get()).as("a bounded number of attempts").isBetween(1, 8);
    }

    @Test
    @DisplayName("reads for the thread already rewriting the document")
    void readsForTheHolder() {
        String answer = locks.inDocument(KEY,
            () -> locks.readUnlessRewriting(KEY, () -> "read", "busy"));

        assertThat(answer).isEqualTo("read");
    }

    @Test
    @DisplayName("lets a failing read fail")
    void propagatesFailure() {
        assertThatThrownBy(() -> locks.readUnlessRewriting(KEY, () -> {
            throw new IllegalStateException("boom");
        }, "busy")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("refuses a null key or null work")
    void refusesNulls() {
        assertThatThrownBy(() -> locks.readUnlessRewriting(null, () -> "x", "busy"))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> locks.readUnlessRewriting(KEY, null, "busy"))
            .isInstanceOf(NullPointerException.class);
    }

    /** Another document key on the same stripe as this one. */
    private static String neighbourOf(String key) {
        int stripe = DocumentLocks.stripeOf(key);
        for (int i = 0; ; i++) {
            String candidate = "docs:neighbour-" + i;
            if (DocumentLocks.stripeOf(candidate) == stripe && !candidate.equals(key)) {
                return candidate;
            }
        }
    }

    /** Takes and releases the document's lock on another thread, as a rewrite would. */
    private void rewriteElsewhere(String key) {
        Thread writer = Thread.ofPlatform().start(() -> locks.inDocument(key, () -> { }));
        try {
            writer.join(TimeUnit.SECONDS.toMillis(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A thread holding one document's lock until closed. */
    private static final class Holder implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread thread;

        private Holder(DocumentLocks locks, String key) throws InterruptedException {
            CountDownLatch holding = new CountDownLatch(1);
            this.thread = Thread.ofPlatform().start(() -> locks.inDocument(key, () -> {
                holding.countDown();
                DocumentLocksTest.await(release);
            }));
            assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();
        }

        static Holder of(DocumentLocks locks, String key) throws InterruptedException {
            return new Holder(locks, key);
        }

        @Override
        public void close() throws InterruptedException {
            release.countDown();
            thread.join(TimeUnit.SECONDS.toMillis(30));
        }
    }
}
