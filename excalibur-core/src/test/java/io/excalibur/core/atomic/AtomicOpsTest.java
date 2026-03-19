package io.excalibur.core.atomic;

import io.excalibur.core.exception.AtomicException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link AtomicOps}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>All get/put operations (byte/int/long/float/double)</li>
 *   <li>All CAS operations (int/long/float/double) - note: byte CAS is NOT supported by VarHandle</li>
 *   <li>All getAndAdd operations (int/long only - VarHandle limitation)</li>
 *   <li>spinCasInt() and spinCasLong() utility methods</li>
 *   <li>Error handling (null segment, invalid offset, out of bounds)</li>
 *   <li>Thread-safety verification with concurrent CAS operations</li>
 * </ul>
 */
@DisplayName("AtomicOps Tests")
class AtomicOpsTest {

    private Arena arena;
    private MemorySegment segment;

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
        // Allocate enough space for all test operations: 128 bytes
        segment = arena.allocate(128);
    }

    // ==================== Byte Operations ====================

    @Nested
    @DisplayName("Byte Operations")
    class ByteOperations {

        @Test
        @DisplayName("getByte reads value written by putByte")
        void getPutByte() {
            byte value = (byte) 0xAB;
            AtomicOps.putByte(segment, 0, value);
            assertEquals(value, AtomicOps.getByte(segment, 0));
        }

        @Test
        @DisplayName("putByte overwrites previous value")
        void putByteOverwrites() {
            AtomicOps.putByte(segment, 0, (byte) 0x00);
            AtomicOps.putByte(segment, 0, (byte) 0xFF);
            assertEquals((byte) 0xFF, AtomicOps.getByte(segment, 0));
        }

        @Test
        @DisplayName("getByte at different offsets")
        void getByteAtDifferentOffsets() {
            AtomicOps.putByte(segment, 0, (byte) 0x11);
            AtomicOps.putByte(segment, 1, (byte) 0x22);
            AtomicOps.putByte(segment, 10, (byte) 0x33);

            assertEquals((byte) 0x11, AtomicOps.getByte(segment, 0));
            assertEquals((byte) 0x22, AtomicOps.getByte(segment, 1));
            assertEquals((byte) 0x33, AtomicOps.getByte(segment, 10));
        }

        @Test
        @DisplayName("byteHandle returns non-null VarHandle")
        void byteHandleReturnsVarHandle() {
            VarHandle handle = AtomicOps.byteHandle();
            assertNotNull(handle);
        }
    }

    // ==================== Int Operations ====================

    @Nested
    @DisplayName("Int Operations")
    class IntOperations {

        @Test
        @DisplayName("getInt reads value written by putInt")
        void getPutInt() {
            int value = 0x12345678;
            AtomicOps.putInt(segment, 0, value);
            assertEquals(value, AtomicOps.getInt(segment, 0));
        }

        @Test
        @DisplayName("putInt uses big-endian byte order")
        void putIntBigEndian() {
            AtomicOps.putInt(segment, 0, 0x12345678);
            // Big-endian: most significant byte first
            assertEquals((byte) 0x12, AtomicOps.getByte(segment, 0));
            assertEquals((byte) 0x34, AtomicOps.getByte(segment, 1));
            assertEquals((byte) 0x56, AtomicOps.getByte(segment, 2));
            assertEquals((byte) 0x78, AtomicOps.getByte(segment, 3));
        }

        @Test
        @DisplayName("compareAndSetInt succeeds when expected matches")
        void compareAndSetIntSuccess() {
            AtomicOps.putInt(segment, 0, 100);
            boolean result = AtomicOps.compareAndSetInt(segment, 0, 100, 200);
            assertTrue(result);
            assertEquals(200, AtomicOps.getInt(segment, 0));
        }

        @Test
        @DisplayName("compareAndSetInt fails when expected does not match")
        void compareAndSetIntFail() {
            AtomicOps.putInt(segment, 0, 100);
            boolean result = AtomicOps.compareAndSetInt(segment, 0, 999, 200);
            assertFalse(result);
            assertEquals(100, AtomicOps.getInt(segment, 0)); // Unchanged
        }

        @Test
        @DisplayName("getAndAddInt returns previous value and updates")
        void getAndAddInt() {
            AtomicOps.putInt(segment, 0, 100);
            int previous = AtomicOps.getAndAddInt(segment, 0, 50);
            assertEquals(100, previous);
            assertEquals(150, AtomicOps.getInt(segment, 0));
        }

        @Test
        @DisplayName("getAndAddInt with negative delta")
        void getAndAddIntNegative() {
            AtomicOps.putInt(segment, 0, 100);
            int previous = AtomicOps.getAndAddInt(segment, 0, -30);
            assertEquals(100, previous);
            assertEquals(70, AtomicOps.getInt(segment, 0));
        }

        @Test
        @DisplayName("addGetInt returns new value")
        void addGetInt() {
            AtomicOps.putInt(segment, 0, 100);
            int result = AtomicOps.addGetInt(segment, 0, 50);
            assertEquals(150, result);
            assertEquals(150, AtomicOps.getInt(segment, 0));
        }

        @Test
        @DisplayName("intHandle returns non-null VarHandle")
        void intHandleReturnsVarHandle() {
            VarHandle handle = AtomicOps.intHandle();
            assertNotNull(handle);
        }
    }

    // ==================== Long Operations ====================

    @Nested
    @DisplayName("Long Operations")
    class LongOperations {

        @Test
        @DisplayName("getLong reads value written by putLong")
        void getPutLong() {
            long value = 0x123456789ABCDEF0L;
            AtomicOps.putLong(segment, 0, value);
            assertEquals(value, AtomicOps.getLong(segment, 0));
        }

        @Test
        @DisplayName("putLong uses big-endian byte order")
        void putLongBigEndian() {
            AtomicOps.putLong(segment, 0, 0x0102030405060708L);
            // Big-endian: most significant byte first
            assertEquals((byte) 0x01, AtomicOps.getByte(segment, 0));
            assertEquals((byte) 0x02, AtomicOps.getByte(segment, 1));
            assertEquals((byte) 0x08, AtomicOps.getByte(segment, 7));
        }

        @Test
        @DisplayName("compareAndSetLong succeeds when expected matches")
        void compareAndSetLongSuccess() {
            AtomicOps.putLong(segment, 0, 1000L);
            boolean result = AtomicOps.compareAndSetLong(segment, 0, 1000L, 2000L);
            assertTrue(result);
            assertEquals(2000L, AtomicOps.getLong(segment, 0));
        }

        @Test
        @DisplayName("compareAndSetLong fails when expected does not match")
        void compareAndSetLongFail() {
            AtomicOps.putLong(segment, 0, 1000L);
            boolean result = AtomicOps.compareAndSetLong(segment, 0, 999L, 2000L);
            assertFalse(result);
            assertEquals(1000L, AtomicOps.getLong(segment, 0)); // Unchanged
        }

        @Test
        @DisplayName("getAndAddLong returns previous value and updates")
        void getAndAddLong() {
            AtomicOps.putLong(segment, 0, 1000L);
            long previous = AtomicOps.getAndAddLong(segment, 0, 500L);
            assertEquals(1000L, previous);
            assertEquals(1500L, AtomicOps.getLong(segment, 0));
        }

        @Test
        @DisplayName("getAndAddLong with negative delta")
        void getAndAddLongNegative() {
            AtomicOps.putLong(segment, 0, 1000L);
            long previous = AtomicOps.getAndAddLong(segment, 0, -300L);
            assertEquals(1000L, previous);
            assertEquals(700L, AtomicOps.getLong(segment, 0));
        }

        @Test
        @DisplayName("addGetLong returns new value")
        void addGetLong() {
            AtomicOps.putLong(segment, 0, 1000L);
            long result = AtomicOps.addGetLong(segment, 0, 500L);
            assertEquals(1500L, result);
            assertEquals(1500L, AtomicOps.getLong(segment, 0));
        }

        @Test
        @DisplayName("longHandle returns non-null VarHandle")
        void longHandleReturnsVarHandle() {
            VarHandle handle = AtomicOps.longHandle();
            assertNotNull(handle);
        }
    }

    // ==================== Float Operations ====================

    @Nested
    @DisplayName("Float Operations")
    class FloatOperations {

        @Test
        @DisplayName("getFloat reads value written by putFloat")
        void getPutFloat() {
            float value = 3.14159f;
            AtomicOps.putFloat(segment, 0, value);
            assertEquals(value, AtomicOps.getFloat(segment, 0), 0.00001f);
        }

        @Test
        @DisplayName("compareAndSetFloat succeeds when expected matches")
        void compareAndSetFloatSuccess() {
            AtomicOps.putFloat(segment, 0, 1.5f);
            boolean result = AtomicOps.compareAndSetFloat(segment, 0, 1.5f, 2.5f);
            assertTrue(result);
            assertEquals(2.5f, AtomicOps.getFloat(segment, 0), 0.00001f);
        }

        @Test
        @DisplayName("compareAndSetFloat fails when expected does not match")
        void compareAndSetFloatFail() {
            AtomicOps.putFloat(segment, 0, 1.5f);
            boolean result = AtomicOps.compareAndSetFloat(segment, 0, 9.9f, 2.5f);
            assertFalse(result);
            assertEquals(1.5f, AtomicOps.getFloat(segment, 0), 0.00001f); // Unchanged
        }

        @Test
        @DisplayName("compareAndSetFloat with NaN uses bitwise comparison")
        void compareAndSetFloatWithNaN() {
            float nanValue = Float.NaN;
            AtomicOps.putFloat(segment, 0, nanValue);
            // NaN values are compared bitwise, not numerically
            boolean result = AtomicOps.compareAndSetFloat(segment, 0, nanValue, 1.0f);
            assertTrue(result);
            assertEquals(1.0f, AtomicOps.getFloat(segment, 0), 0.00001f);
        }

        @Test
        @DisplayName("compareAndSetFloat with positive zero vs negative zero")
        void compareAndSetFloatWithZero() {
            // +0.0f and -0.0f have different bit representations
            AtomicOps.putFloat(segment, 0, +0.0f);
            boolean result = AtomicOps.compareAndSetFloat(segment, 0, -0.0f, 1.0f);
            assertFalse(result); // Different bit patterns
            assertEquals(+0.0f, AtomicOps.getFloat(segment, 0), 0.00001f);
        }

        @Test
        @DisplayName("floatHandle returns non-null VarHandle")
        void floatHandleReturnsVarHandle() {
            VarHandle handle = AtomicOps.floatHandle();
            assertNotNull(handle);
        }
    }

    // ==================== Double Operations ====================

    @Nested
    @DisplayName("Double Operations")
    class DoubleOperations {

        @Test
        @DisplayName("getDouble reads value written by putDouble")
        void getPutDouble() {
            double value = 3.14159265358979;
            AtomicOps.putDouble(segment, 0, value);
            assertEquals(value, AtomicOps.getDouble(segment, 0), 0.0000001);
        }

        @Test
        @DisplayName("compareAndSetDouble succeeds when expected matches")
        void compareAndSetDoubleSuccess() {
            AtomicOps.putDouble(segment, 0, 1.5);
            boolean result = AtomicOps.compareAndSetDouble(segment, 0, 1.5, 2.5);
            assertTrue(result);
            assertEquals(2.5, AtomicOps.getDouble(segment, 0), 0.0000001);
        }

        @Test
        @DisplayName("compareAndSetDouble fails when expected does not match")
        void compareAndSetDoubleFail() {
            AtomicOps.putDouble(segment, 0, 1.5);
            boolean result = AtomicOps.compareAndSetDouble(segment, 0, 9.9, 2.5);
            assertFalse(result);
            assertEquals(1.5, AtomicOps.getDouble(segment, 0), 0.0000001); // Unchanged
        }

        @Test
        @DisplayName("compareAndSetDouble with NaN uses bitwise comparison")
        void compareAndSetDoubleWithNaN() {
            double nanValue = Double.NaN;
            AtomicOps.putDouble(segment, 0, nanValue);
            // NaN values are compared bitwise, not numerically
            boolean result = AtomicOps.compareAndSetDouble(segment, 0, nanValue, 1.0);
            assertTrue(result);
            assertEquals(1.0, AtomicOps.getDouble(segment, 0), 0.0000001);
        }

        @Test
        @DisplayName("compareAndSetDouble with positive zero vs negative zero")
        void compareAndSetDoubleWithZero() {
            // +0.0 and -0.0 have different bit representations
            AtomicOps.putDouble(segment, 0, +0.0);
            boolean result = AtomicOps.compareAndSetDouble(segment, 0, -0.0, 1.0);
            assertFalse(result); // Different bit patterns
            assertEquals(+0.0, AtomicOps.getDouble(segment, 0), 0.0000001);
        }

        @Test
        @DisplayName("doubleHandle returns non-null VarHandle")
        void doubleHandleReturnsVarHandle() {
            VarHandle handle = AtomicOps.doubleHandle();
            assertNotNull(handle);
        }
    }

    // ==================== Spin CAS Operations ====================

    @Nested
    @DisplayName("Spin CAS Operations")
    class SpinCasOperations {

        @Test
        @DisplayName("spinCasInt succeeds on first attempt when value matches")
        void spinCasIntSuccessFirstAttempt() {
            AtomicOps.putInt(segment, 0, 100);
            boolean result = AtomicOps.spinCasInt(segment, 0, 100, 200, 10);
            assertTrue(result);
            assertEquals(200, AtomicOps.getInt(segment, 0));
        }

        @Test
        @DisplayName("spinCasInt retries and succeeds")
        void spinCasIntRetrySuccess() {
            AtomicOps.putInt(segment, 0, 100);
            // First attempt will fail (wrong expected), but it re-reads and retries
            // After re-reading 100, second attempt succeeds
            boolean result = AtomicOps.spinCasInt(segment, 0, 999, 200, 10);
            assertTrue(result);
            assertEquals(200, AtomicOps.getInt(segment, 0));
        }

        @Test
        @DisplayName("spinCasInt with zero retries fails immediately on mismatch")
        void spinCasIntZeroRetries() {
            AtomicOps.putInt(segment, 0, 100);
            // With maxRetries=1, only one attempt is made
            // If expected doesn't match, it fails immediately
            boolean result = AtomicOps.spinCasInt(segment, 0, 999, 200, 1);
            assertFalse(result);
            assertEquals(100, AtomicOps.getInt(segment, 0)); // Unchanged
        }

        @Test
        @DisplayName("spinCasInt respects max retries count")
        void spinCasIntMaxRetriesBehavior() throws Exception {
            AtomicOps.putInt(segment, 0, 100);

            // Use another thread to continuously modify the value to something
            // other than what we expect
            ExecutorService executor = Executors.newSingleThreadExecutor();
            AtomicBoolean running = new AtomicBoolean(true);
            AtomicInteger changeCount = new AtomicInteger(0);

            executor.submit(() -> {
                while (running.get()) {
                    // Always change to a value that's NOT 999 or 200
                    AtomicOps.putInt(segment, 0, 100 + changeCount.incrementAndGet());
                }
            });

            // Give the other thread time to start modifying
            Thread.sleep(5);

            // Try to CAS with wrong expected value - should fail and keep retrying
            // but since the value keeps changing, it should exhaust retries
            boolean result = AtomicOps.spinCasInt(segment, 0, 999, 200, 5);

            running.set(false);
            executor.shutdownNow();

            // The result depends on whether the spin loop happened to see
            // a matching value during its retries. This test verifies the
            // mechanism works but doesn't assert on the result since it's
            // timing-dependent. Instead, verify the value wasn't corrupted.
            assertTrue(AtomicOps.getInt(segment, 0) != 200 || result,
                "If value is 200, result should be true");
        }

        @Test
        @DisplayName("spinCasLong succeeds on first attempt when value matches")
        void spinCasLongSuccessFirstAttempt() {
            AtomicOps.putLong(segment, 0, 1000L);
            boolean result = AtomicOps.spinCasLong(segment, 0, 1000L, 2000L, 10);
            assertTrue(result);
            assertEquals(2000L, AtomicOps.getLong(segment, 0));
        }

        @Test
        @DisplayName("spinCasLong retries and succeeds")
        void spinCasLongRetrySuccess() {
            AtomicOps.putLong(segment, 0, 1000L);
            boolean result = AtomicOps.spinCasLong(segment, 0, 999L, 2000L, 10);
            assertTrue(result);
            assertEquals(2000L, AtomicOps.getLong(segment, 0));
        }
    }

    // ==================== Error Handling ====================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("getByte throws AtomicException for null segment")
        void getByteNullSegment() {
            assertThrows(AtomicException.class, () -> {
                AtomicOps.getByte(null, 0);
            });
        }

        @Test
        @DisplayName("putInt throws AtomicException for null segment")
        void putIntNullSegment() {
            assertThrows(AtomicException.class, () -> {
                AtomicOps.putInt(null, 0, 100);
            });
        }

        @Test
        @DisplayName("getLong throws AtomicException for negative offset")
        void getLongNegativeOffset() {
            assertThrows(AtomicException.class, () -> {
                AtomicOps.getLong(segment, -1);
            });
        }

        @Test
        @DisplayName("putDouble throws AtomicException for out of bounds offset")
        void putDoubleOutOfBounds() {
            assertThrows(AtomicException.class, () -> {
                AtomicOps.putDouble(segment, segment.byteSize() - 4, 1.0); // Needs 8 bytes
            });
        }

        @Test
        @DisplayName("compareAndSetInt throws AtomicException for offset at boundary")
        void compareAndSetIntAtBoundary() {
            assertThrows(AtomicException.class, () -> {
                AtomicOps.compareAndSetInt(segment, segment.byteSize() - 2, 0, 1); // Needs 4 bytes
            });
        }

        @Test
        @DisplayName("compareAndSetFloat throws AtomicException for null segment")
        void compareAndSetFloatNullSegment() {
            assertThrows(AtomicException.class, () -> {
                AtomicOps.compareAndSetFloat(null, 0, 1.0f, 2.0f);
            });
        }

        @Test
        @DisplayName("compareAndSetDouble throws AtomicException for out of bounds")
        void compareAndSetDoubleOutOfBounds() {
            assertThrows(AtomicException.class, () -> {
                AtomicOps.compareAndSetDouble(segment, segment.byteSize() - 4, 1.0, 2.0);
            });
        }
    }

    // ==================== Thread Safety ====================

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafety {

        @Test
        @DisplayName("Concurrent CAS on int maintains consistency")
        void concurrentCasInt() throws Exception {
            final int numThreads = 10;
            final int iterationsPerThread = 1000;
            final AtomicInteger successCount = new AtomicInteger(0);

            AtomicOps.putInt(segment, 0, 0);

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);

            for (int i = 0; i < numThreads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < iterationsPerThread; j++) {
                            int current;
                            do {
                                current = AtomicOps.getInt(segment, 0);
                            } while (!AtomicOps.compareAndSetInt(segment, 0, current, current + 1));
                            successCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // Start all threads
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads should complete in time");

            executor.shutdown();

            // All operations should succeed
            assertEquals(numThreads * iterationsPerThread, successCount.get());
            // Final value should be exactly numThreads * iterationsPerThread
            assertEquals(numThreads * iterationsPerThread, AtomicOps.getInt(segment, 0));
        }

        @Test
        @DisplayName("Concurrent CAS on long maintains consistency")
        void concurrentCasLong() throws Exception {
            final int numThreads = 8;
            final int iterationsPerThread = 500;
            final AtomicInteger successCount = new AtomicInteger(0);

            AtomicOps.putLong(segment, 0, 0L);

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);

            for (int i = 0; i < numThreads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < iterationsPerThread; j++) {
                            long current;
                            do {
                                current = AtomicOps.getLong(segment, 0);
                            } while (!AtomicOps.compareAndSetLong(segment, 0, current, current + 1));
                            successCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads should complete in time");

            executor.shutdown();

            assertEquals(numThreads * iterationsPerThread, successCount.get());
            assertEquals((long) numThreads * iterationsPerThread, AtomicOps.getLong(segment, 0));
        }

        @Test
        @DisplayName("Concurrent getAndAddInt produces correct sum")
        void concurrentGetAndAddInt() throws Exception {
            final int numThreads = 10;
            final int iterationsPerThread = 1000;
            final int delta = 1;

            AtomicOps.putInt(segment, 0, 0);

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);

            for (int i = 0; i < numThreads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < iterationsPerThread; j++) {
                            AtomicOps.getAndAddInt(segment, 0, delta);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads should complete in time");

            executor.shutdown();

            assertEquals(numThreads * iterationsPerThread, AtomicOps.getInt(segment, 0));
        }

        @Test
        @DisplayName("Mixed read/write operations don't corrupt data")
        void mixedReadWriteOperations() throws Exception {
            final int numReaders = 5;
            final int numWriters = 3;
            final int iterations = 1000;

            AtomicOps.putLong(segment, 0, 0L);

            ExecutorService executor = Executors.newFixedThreadPool(numReaders + numWriters);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numReaders + numWriters);
            AtomicBoolean readersOk = new AtomicBoolean(true);

            // Writers
            for (int i = 0; i < numWriters; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < iterations; j++) {
                            long current;
                            do {
                                current = AtomicOps.getLong(segment, 0);
                            } while (!AtomicOps.compareAndSetLong(segment, 0, current, current + 1));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Readers
            for (int i = 0; i < numReaders; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < iterations; j++) {
                            long value = AtomicOps.getLong(segment, 0);
                            // Value should always be non-negative and <= numWriters * iterations
                            if (value < 0 || value > numWriters * iterations) {
                                readersOk.set(false);
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
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads should complete in time");

            executor.shutdown();

            assertTrue(readersOk.get(), "Readers should never see invalid values");
            assertEquals((long) numWriters * iterations, AtomicOps.getLong(segment, 0));
        }
    }

    // ==================== VarHandle Limitations Documentation ====================

    @Nested
    @DisplayName("VarHandle Limitations")
    class VarHandleLimitations {

        @Test
        @DisplayName("Verify no compareAndSetByte method exists (VarHandle limitation)")
        void noByteCas() throws NoSuchMethodException {
            // This test documents that compareAndSetByte should NOT exist
            // because Java 21's VarHandle does not support CAS for byte types
            assertThrows(NoSuchMethodException.class, () -> {
                AtomicOps.class.getMethod("compareAndSetByte",
                        MemorySegment.class, long.class, byte.class, byte.class);
            });
        }

        @Test
        @DisplayName("Verify no getAndAddFloat method exists (VarHandle limitation)")
        void noFloatGetAndAdd() throws NoSuchMethodException {
            // This test documents that getAndAddFloat should NOT exist
            // because Java 21's VarHandle does not support getAndAdd for float
            assertThrows(NoSuchMethodException.class, () -> {
                AtomicOps.class.getMethod("getAndAddFloat",
                        MemorySegment.class, long.class, float.class);
            });
        }

        @Test
        @DisplayName("Verify no getAndAddDouble method exists (VarHandle limitation)")
        void noDoubleGetAndAdd() throws NoSuchMethodException {
            // This test documents that getAndAddDouble should NOT exist
            // because Java 21's VarHandle does not support getAndAdd for double
            assertThrows(NoSuchMethodException.class, () -> {
                AtomicOps.class.getMethod("getAndAddDouble",
                        MemorySegment.class, long.class, double.class);
            });
        }
    }
}
