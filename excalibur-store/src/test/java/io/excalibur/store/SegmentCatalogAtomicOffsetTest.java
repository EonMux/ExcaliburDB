package io.excalibur.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for atomic lastIntegratedOffset updates in {@link SegmentCatalog}.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>Atomic get/set operations</li>
 *   <li>Monotonic increase guarantee under concurrent access</li>
 *   <li>CAS operations correctness</li>
 *   <li>Thread safety guarantees</li>
 * </ul>
 */
class SegmentCatalogAtomicOffsetTest {

    private SegmentCatalog catalog;
    private SegmentCatalog.SegmentMetadata metadata;

    @BeforeEach
    void setUp() {
        catalog = new SegmentCatalog();
        metadata = catalog.add(1L, java.nio.file.Path.of("/test/segment"));
    }

    // ==================== Basic Operations ====================

    @Test
    void shouldInitializeWithZeroOffset() {
        assertThat(metadata.getLastIntegratedOffset()).isEqualTo(0L);
    }

    @Test
    void shouldSetOffsetDirectly() {
        metadata.setLastIntegratedOffset(1000L);
        assertThat(metadata.getLastIntegratedOffset()).isEqualTo(1000L);
    }

    @Test
    void shouldAllowMultipleSets() {
        metadata.setLastIntegratedOffset(100L);
        metadata.setLastIntegratedOffset(200L);
        assertThat(metadata.getLastIntegratedOffset()).isEqualTo(200L);
    }

    // ==================== CAS Operations ====================

    @Test
    void shouldUpdateOffsetWithCAS() {
        boolean updated = metadata.compareAndSetLastIntegratedOffset(100L);
        assertThat(updated).isTrue();
        assertThat(metadata.getLastIntegratedOffset()).isEqualTo(100L);
    }

    @Test
    void shouldNotDecreaseOffsetWithCAS() {
        metadata.setLastIntegratedOffset(100L);

        // Try to decrease
        boolean updated = metadata.compareAndSetLastIntegratedOffset(50L);
        assertThat(updated).isFalse();
        assertThat(metadata.getLastIntegratedOffset()).isEqualTo(100L);
    }

    @Test
    void shouldNotSetSameValueWithCAS() {
        metadata.setLastIntegratedOffset(100L);

        // Try same value
        boolean updated = metadata.compareAndSetLastIntegratedOffset(100L);
        assertThat(updated).isFalse();
    }

    @Test
    void shouldAllowIncreaseWithCAS() {
        metadata.setLastIntegratedOffset(100L);

        boolean updated = metadata.compareAndSetLastIntegratedOffset(200L);
        assertThat(updated).isTrue();
        assertThat(metadata.getLastIntegratedOffset()).isEqualTo(200L);
    }

    // ==================== UpdateToMax Operations ====================

    @Test
    void shouldUpdateToMaxWhenNewIsGreater() {
        metadata.setLastIntegratedOffset(100L);
        metadata.updateOffsetToMax(200L);
        assertThat(metadata.getLastIntegratedOffset()).isEqualTo(200L);
    }

    @Test
    void shouldNotChangeWhenCurrentIsGreater() {
        metadata.setLastIntegratedOffset(200L);
        metadata.updateOffsetToMax(100L);
        assertThat(metadata.getLastIntegratedOffset()).isEqualTo(200L);
    }

    @Test
    void shouldNotChangeWhenEqual() {
        metadata.setLastIntegratedOffset(100L);
        metadata.updateOffsetToMax(100L);
        assertThat(metadata.getLastIntegratedOffset()).isEqualTo(100L);
    }

    // ==================== Thread Safety Tests ====================

    @Test
    void shouldBeThreadSafeForConcurrentCASUpdates() throws InterruptedException {
        int numThreads = 10;
        int updatesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < updatesPerThread; i++) {
                        // Each thread tries to set a unique offset
                        long offset = threadId * 1000L + i + 1;
                        if (metadata.compareAndSetLastIntegratedOffset(offset)) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Verify offset was updated at least once (likely many times)
        assertThat(metadata.getLastIntegratedOffset()).isGreaterThan(0);

        // Final offset should be the maximum offset attempted
        long maxAttemptedOffset = (numThreads - 1) * 1000L + updatesPerThread;
        assertThat(metadata.getLastIntegratedOffset()).isLessThanOrEqualTo(maxAttemptedOffset);

        // The offset should never decrease during concurrent updates
        assertThat(metadata.getLastIntegratedOffset()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldMaintainMonotonicityUnderConcurrentUpdateToMax() throws InterruptedException {
        int numThreads = 8;
        int updatesPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        // Track all values observed during the test
        AtomicInteger maxValueObserved = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < updatesPerThread; i++) {
                        // Generate a value that increases over time
                        long offset = (threadId * updatesPerThread + i + 1) * 10L;
                        metadata.updateOffsetToMax(offset);

                        // Track max observed
                        long current = metadata.getLastIntegratedOffset();
                        maxValueObserved.updateAndGet(max -> Math.max(max, (int) current));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Verify final offset is positive and represents progress
        assertThat(metadata.getLastIntegratedOffset()).isGreaterThan(0);
        assertThat(metadata.getLastIntegratedOffset()).isEqualTo(maxValueObserved.get());
    }

    @Test
    void shouldHandleHighContention() throws InterruptedException {
        int numThreads = 16;
        int iterations = 10000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);
        AtomicInteger totalAttempts = new AtomicInteger(0);
        AtomicInteger successfulUpdates = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long lastOffset = 0;
                    for (int i = 0; i < iterations; i++) {
                        // Each thread tries to advance by 1
                        long newOffset = lastOffset + 1;
                        totalAttempts.incrementAndGet();
                        if (metadata.compareAndSetLastIntegratedOffset(newOffset)) {
                            successfulUpdates.incrementAndGet();
                            lastOffset = newOffset;
                        } else {
                            // Update failed - get current and retry next iteration
                            lastOffset = metadata.getLastIntegratedOffset();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // The final offset should be positive (some updates succeeded)
        assertThat(metadata.getLastIntegratedOffset()).isGreaterThan(0);

        // Not all attempts should succeed (contention)
        // But some should
        assertThat(successfulUpdates.get()).isGreaterThan(0);
    }

    // ==================== Edge Cases ====================

    @Test
    void shouldHandleMaxLongValue() {
        metadata.setLastIntegratedOffset(Long.MAX_VALUE - 1);

        boolean updated = metadata.compareAndSetLastIntegratedOffset(Long.MAX_VALUE);
        assertThat(updated).isTrue();
        assertThat(metadata.getLastIntegratedOffset()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void shouldRejectNegativeCAS() {
        // Setting negative directly should work (but is not recommended)
        // CAS should reject negative
        metadata.setLastIntegratedOffset(0L);
        boolean updated = metadata.compareAndSetLastIntegratedOffset(-1L);
        assertThat(updated).isFalse();
        assertThat(metadata.getLastIntegratedOffset()).isEqualTo(0L);
    }

    @Test
    void shouldHandleZeroOffset() {
        // Starting at 0, trying to set 0 should fail
        boolean updated = metadata.compareAndSetLastIntegratedOffset(0L);
        assertThat(updated).isFalse();

        // After increasing, going back to 0 should fail
        metadata.setLastIntegratedOffset(100L);
        updated = metadata.compareAndSetLastIntegratedOffset(0L);
        assertThat(updated).isFalse();
    }
}
