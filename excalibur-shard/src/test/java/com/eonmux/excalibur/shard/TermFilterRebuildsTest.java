package com.eonmux.excalibur.shard;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.core.text.TermExtractor;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.store.Segment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rebuild queue on its own: what it holds a segment's lock for, what it does with an entry
 * it cannot read, and what closing leaves behind.
 *
 * <p>Each test stops the rebuild inside its tokeniser on a latch, so what it checks happens at a
 * known point in the rebuild rather than whenever the scheduler gets to it.</p>
 */
@DisplayName("Term filter rebuild queue")
class TermFilterRebuildsTest {

    /** Bounds a wait that the code under test should end at once; never reached when correct. */
    private static final long BOUND_SECONDS = 10;

    @TempDir
    Path tempDir;

    private Arena arena;
    private Segment segment;
    private HierarchicalCuckooFilter filter;
    private TermFilterRebuilds rebuilds;

    /** Segments a test opened beside {@link #segment}. */
    private final List<Segment> opened = new ArrayList<>();

    @BeforeEach
    void openSegment() throws IOException {
        arena = Arena.ofShared();
        segment = new Segment(tempDir.resolve("segment_0.dat"), 1 << 20, arena);
        filter = new HierarchicalCuckooFilter(10_000);
    }

    @AfterEach
    void tearDown() {
        if (rebuilds != null) {
            rebuilds.close();
        }
        opened.forEach(Segment::close);
        segment.close();
        arena.close();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void store(String key, String text) {
        segment.put(bytes(key), ChunkPayloadCodec.encode(text, new float[] {0.5f}));
    }

    private TermFilterRebuilds queue(Function<String, ? extends Collection<String>> tokeniser) {
        rebuilds = new TermFilterRebuilds(() -> filter, id -> segment, null, tokeniser);
        return rebuilds;
    }

    /** A queue that resolves id 0 to whatever {@code registered} holds when it asks. */
    private TermFilterRebuilds queue(AtomicReference<Segment> registered,
                                     Function<String, ? extends Collection<String>> tokeniser) {
        rebuilds = new TermFilterRebuilds(() -> filter, id -> registered.get(), null, tokeniser);
        return rebuilds;
    }

    /** Opens another segment in the test directory, closed at tear-down. */
    private Segment another(String name) throws IOException {
        Segment other = new Segment(tempDir.resolve(name), 1 << 20, arena);
        opened.add(other);
        return other;
    }

    /**
     * How long a test gives a rebuild that must stay deferred. Several retry periods, so a
     * rebuild that was not deferred has long since drained the queue.
     */
    private static final long DEFERRED_MILLIS = 4 * TermFilterRebuilds.SPLIT_RETRY.toMillis();

    /**
     * A tokeniser that stops on its first call until released, and reports that it stopped.
     *
     * <p>It gives up well after any wait in a test would, so a test that fails does so on its
     * own bound, not because the gate let go first.</p>
     */
    private static final class Gate implements Function<String, Collection<String>> {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Collection<String> apply(String text) {
            entered.countDown();
            try {
                if (!release.await(3 * BOUND_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the tokeniser");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while tokenising", e);
            }
            return TermExtractor.extractUniqueTerms(text);
        }
    }

    @Test
    @DisplayName("a write to the segment goes through while the rebuild is tokenising")
    void tokenisingHoldsNoLock() throws Exception {
        store("a", "harbour lighthouse");
        Gate gate = new Gate();
        queue(gate).schedule(0);
        assertThat(gate.entered.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

        // Holding the read lock across tokenising would park this put until the release below.
        CompletableFuture<Void> write =
            CompletableFuture.runAsync(() -> store("b", "estuary"));
        write.get(BOUND_SECONDS, TimeUnit.SECONDS);

        assertThat(filter.isSegmentStale("0"))
            .as("stale while the terms are still being filed").isTrue();
        gate.release.countDown();
        assertThat(rebuilds.awaitIdle(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThat(filter.coversSegment("0")).isTrue();
        assertThat(filter.mightContainInSegment(bytes("lighthouse"), "0")).isTrue();
    }

    @Test
    @DisplayName("an entry that fails verification keeps the level stale, and the rest are filed")
    void aDamagedEntryKeepsTheLevelStale() throws Exception {
        store("a", "harbour");
        store("b", "zanzibar");
        store("c", "estuary");
        damage(bytes("zanzibar"));

        queue(TermExtractor::extractUniqueTerms).schedule(0);
        assertThat(rebuilds.awaitIdle(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThat(filter.isSegmentStale("0")).isTrue();
        assertThat(filter.coversSegment("0")).isFalse();
        assertThat(filter.mightContainInSegment(bytes("harbour"), "0")).isTrue();
        assertThat(filter.mightContainInSegment(bytes("estuary"), "0")).isTrue();
    }

    @Test
    @DisplayName("a segment read whole is vouched for")
    void aSegmentReadWholeIsTrusted() throws Exception {
        store("a", "harbour");
        store("vector-only", "");
        segment.put(bytes("raw"), ChunkPayloadCodec.encode(new float[] {0.5f}));

        queue(TermExtractor::extractUniqueTerms).schedule(0);
        assertThat(rebuilds.awaitIdle(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThat(filter.isSegmentStale("0")).isFalse();
        assertThat(filter.coversSegment("0")).isTrue();
    }

    @Test
    @DisplayName("closing counts off the rebuilds it drops, and stops the one running")
    void closingLeavesNothingPending() throws Exception {
        store("a", "harbour");
        Gate gate = new Gate();
        queue(gate).schedule(0);
        assertThat(gate.entered.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
        rebuilds.schedule(0);
        rebuilds.schedule(0);

        rebuilds.close();

        assertThat(rebuilds.awaitIdle(0, TimeUnit.MILLISECONDS))
            .as("nothing left counted as pending").isTrue();
        assertThat(filter.isSegmentStale("0"))
            .as("no dropped or stopped rebuild vouched for the level").isTrue();
    }

    @Test
    @DisplayName("a rebuild asked for after closing is not queued, and is not left pending")
    void aRebuildAfterClosingIsNotPending() throws Exception {
        queue(TermExtractor::extractUniqueTerms).close();

        rebuilds.schedule(0);

        assertThat(rebuilds.awaitIdle(0, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(filter.isSegmentStale("0")).isTrue();
    }

    @Test
    @DisplayName("a segment being split is not read, and is rebuilt once the split is given up")
    void aSplittingSegmentWaitsForItsSplit() throws Exception {
        store("a", "harbour");
        segment.beginSplit();

        queue(TermExtractor::extractUniqueTerms).schedule(0);

        assertThat(rebuilds.awaitIdle(DEFERRED_MILLIS, TimeUnit.MILLISECONDS))
            .as("the rebuild stays queued while the split is undecided").isFalse();
        assertThat(filter.isSegmentStale("0")).isTrue();

        segment.abortSplit();
        assertThat(rebuilds.awaitIdle(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(filter.coversSegment("0")).isTrue();
        assertThat(filter.mightContainInSegment(bytes("harbour"), "0")).isTrue();
    }

    @Test
    @DisplayName("a split parent still registered under the id is never read in its child's place")
    void aSplitParentIsNeverReadForItsChild() throws Exception {
        store("a", "harbour");
        segment.beginSplit();
        AtomicReference<Segment> registered = new AtomicReference<>(segment);
        queue(registered, TermExtractor::extractUniqueTerms).schedule(0);

        // The split is published: the left child holds the parent's entry and takes a write the
        // frozen parent never sees, while the parent is still what the id resolves to.
        Segment left = another("segment_0.left");
        left.put(bytes("a"), ChunkPayloadCodec.encode("harbour", new float[] {0.5f}));
        left.put(bytes("b"), ChunkPayloadCodec.encode("estuary", new float[] {0.5f}));
        segment.completeSplit(left, another("segment_0.right"), 0);
        assertThat(rebuilds.awaitIdle(BOUND_SECONDS, TimeUnit.SECONDS))
            .as("a parent whose split completed is not waited on").isTrue();
        assertThat(filter.isSegmentStale("0"))
            .as("no rebuild vouched for the level from the frozen parent").isTrue();

        // Registering the child is what schedules the rebuild that reads it.
        registered.set(left);
        rebuilds.schedule(0);
        assertThat(rebuilds.awaitIdle(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(filter.coversSegment("0")).isTrue();
        assertThat(filter.mightContainInSegment(bytes("estuary"), "0"))
            .as("the write the child took after the split is filed").isTrue();
    }

    @Test
    @DisplayName("a split parent left registered does not keep the queue from going idle")
    void aSplitParentLeftRegisteredIsNotWaitedOn() throws Exception {
        // The split's bookkeeping failed before it registered the child, so nothing will ever
        // replace the parent under the id. Retrying its rebuild would keep it pending for good.
        store("a", "harbour");
        segment.beginSplit();
        segment.completeSplit(another("segment_0.left"), another("segment_0.right"), 0);

        queue(TermExtractor::extractUniqueTerms).schedule(0);

        assertThat(rebuilds.awaitIdle(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(filter.isSegmentStale("0"))
            .as("the filter says nothing about the segment inherited from the parent").isTrue();
    }

    @Test
    @DisplayName("rebuilds of one id waiting out a split are coalesced into one retry")
    void deferredRebuildsAreCoalesced() throws Exception {
        store("a", "harbour");
        segment.beginSplit();
        queue(TermExtractor::extractUniqueTerms);

        rebuilds.schedule(0);
        rebuilds.schedule(0);
        rebuilds.schedule(0);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
        while (rebuilds.pending() != 1 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(rebuilds.pending())
            .as("one retry stands for all three, rather than three spinning on the queue")
            .isEqualTo(1);

        segment.abortSplit();
        assertThat(rebuilds.awaitIdle(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(filter.coversSegment("0")).isTrue();
        assertThat(filter.mightContainInSegment(bytes("harbour"), "0")).isTrue();
    }

    @Test
    @DisplayName("a segment replaced under its id during the rebuild leaves the level stale")
    void aReplacedSegmentIsNotVouchedFor() throws Exception {
        store("a", "harbour");
        AtomicReference<Segment> registered = new AtomicReference<>(segment);
        Gate gate = new Gate();
        queue(registered, gate).schedule(0);
        assertThat(gate.entered.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

        registered.set(another("segment_0.replacement"));
        gate.release.countDown();

        assertThat(rebuilds.awaitIdle(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(filter.isSegmentStale("0"))
            .as("the rebuild read a segment the id no longer serves").isTrue();
    }

    @Test
    @DisplayName("a segment that begins splitting during the rebuild is vouched for only once it settles")
    void aSplitBegunDuringTheRebuildDefersTheVouching() throws Exception {
        store("a", "harbour");
        Gate gate = new Gate();
        queue(gate).schedule(0);
        assertThat(gate.entered.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

        segment.beginSplit();
        gate.release.countDown();

        assertThat(rebuilds.awaitIdle(DEFERRED_MILLIS, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(filter.isSegmentStale("0")).isTrue();

        segment.abortSplit();
        assertThat(rebuilds.awaitIdle(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(filter.coversSegment("0")).isTrue();
    }

    @Test
    @DisplayName("closing counts off a rebuild waiting out a split")
    void closingCountsOffADeferredRebuild() throws Exception {
        segment.beginSplit();
        queue(TermExtractor::extractUniqueTerms).schedule(0);
        assertThat(rebuilds.awaitIdle(DEFERRED_MILLIS, TimeUnit.MILLISECONDS)).isFalse();

        rebuilds.close();

        assertThat(rebuilds.awaitIdle(BOUND_SECONDS, TimeUnit.SECONDS))
            .as("the deferred rebuild is counted off rather than left pending").isTrue();
        assertThat(filter.isSegmentStale("0")).isTrue();
        segment.abortSplit();
    }

    /** Changes the case of one byte of a stored value, through the live mapping. */
    private void damage(byte[] marker) {
        var mapping = segment.segment();
        long size = mapping.byteSize();
        outer:
        for (long i = 0; i <= size - marker.length; i++) {
            for (int j = 0; j < marker.length; j++) {
                if (mapping.get(ValueLayout.JAVA_BYTE, i + j) != marker[j]) {
                    continue outer;
                }
            }
            byte original = mapping.get(ValueLayout.JAVA_BYTE, i + 2);
            mapping.set(ValueLayout.JAVA_BYTE, i + 2, (byte) (original ^ 0x20));
            return;
        }
        throw new IllegalStateException("marker not found in the segment");
    }
}
