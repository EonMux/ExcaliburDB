package io.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the catalog's own bookkeeping survives the concurrency it advertises.
 *
 * <p>{@link CatalogRegistry}'s class javadoc claims the registry is fully thread-safe. These
 * tests hold it to that for the three structures a concurrent ingest actually touches: the
 * per-segment entry count, the per-volume reverse index, and the map the reverse index hangs
 * off. Each failure mode here is a lost write rather than an exception, which is why none of
 * them would show up in a single-threaded test.</p>
 */
@DisplayName("CatalogRegistry under concurrency")
class CatalogRegistryConcurrencyTest {

    private static final int THREADS = 8;
    private static final int UPDATES_PER_THREAD = 2_000;

    @Test
    @DisplayName("concurrent entry-count updates do not lose increments")
    void concurrentUpdatesDoNotLoseIncrements() throws Exception {
        SegmentCatalog catalog = new SegmentCatalog();
        catalog.add(1L, Path.of("segment_1.dat"));

        runConcurrently(() -> {
            for (int i = 0; i < UPDATES_PER_THREAD; i++) {
                catalog.update(1L, SegmentCatalog.ENTRY_STATE_ACTIVE, 1);
            }
        });

        assertThat(catalog.get(1L).getEntryCount())
            .as("update() adjusts the count by a delta; a get-then-set drops the increments "
                + "another thread made in between")
            .isEqualTo(THREADS * UPDATES_PER_THREAD);
    }

    @Test
    @DisplayName("a segment registered against a volume is never dropped by a concurrent removal")
    void concurrentRegistrationAndRemovalKeepTheVolumeIndex() throws Exception {
        SegmentCatalog catalog = new SegmentCatalog();
        Path volume = Path.of("volume-a");

        // One id is registered and purged over and over while a second is registered once and
        // must stay reachable. Unlinking the id list from the map while it is non-empty loses
        // the survivor.
        long survivor = 999L;

        runConcurrently(threadIndex -> {
            if (threadIndex == 0) {
                for (int i = 0; i < 500; i++) {
                    long churn = 1_000_000L + i;
                    catalog.add(churn, volume);
                    catalog.tombstone(churn);
                    catalog.remove(churn);
                }
            } else {
                for (int i = 0; i < 500; i++) {
                    catalog.registerFile(survivor, volume);
                    assertThat(catalog.getByVolume(volume))
                        .as("the reverse index is read while it is being mutated")
                        .doesNotContainNull();
                }
            }
        });

        assertThat(catalog.getByVolume(volume))
            .as("a segment still in the catalog must still be reachable through its volume")
            .extracting(SegmentCatalog.SegmentMetadata::getSegmentId)
            .contains(survivor);
    }

    /**
     * Runs the same body on every thread, and rethrows whatever any of them threw.
     *
     * @param body What each thread runs
     * @throws Exception if a worker failed or the run did not finish
     */
    private static void runConcurrently(Runnable body) throws Exception {
        runConcurrently(index -> body.run());
    }

    /**
     * Runs one body per thread, started together, and rethrows whatever any of them threw.
     *
     * @param body What thread {@code i} runs
     * @throws Exception if a worker failed or the run did not finish
     */
    private static void runConcurrently(IndexedBody body) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            int index = i;
            Thread thread = new Thread(() -> {
                try {
                    if (!go.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("never released");
                    }
                    body.run(index);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "catalog-registry-" + index);
            threads.add(thread);
            thread.start();
        }

        go.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(60));
            assertThat(thread.isAlive()).as("worker finished").isFalse();
        }

        Throwable thrown = failure.get();
        if (thrown != null) {
            throw new AssertionError("a worker failed", thrown);
        }
    }

    /** A per-thread body that knows which thread it is. */
    @FunctionalInterface
    private interface IndexedBody {
        void run(int threadIndex) throws Exception;
    }
}
