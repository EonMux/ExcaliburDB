package io.excalibur.core.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for AtomicLongCounter.
 *
 * Thread-safety: Tests verify atomic operations.
 * Domain correctness: Tests verify counter behavior is correct.
 */
@DisplayName("AtomicLongCounter Tests")
class AtomicLongCounterTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should initialize with given value")
        void shouldInitializeWithGivenValue() {
            AtomicLongCounter counter = new AtomicLongCounter(42);

            assertThat(counter.get()).isEqualTo(42L);
        }

        @Test
        @DisplayName("Should initialize with zero")
        void shouldInitializeWithZero() {
            AtomicLongCounter counter = new AtomicLongCounter(0);

            assertThat(counter.get()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should initialize with negative value")
        void shouldInitializeWithNegative() {
            AtomicLongCounter counter = new AtomicLongCounter(-10);

            assertThat(counter.get()).isEqualTo(-10L);
        }
    }

    @Nested
    @DisplayName("Get and Set Tests")
    class GetSetTests {

        private AtomicLongCounter counter;

        @BeforeEach
        void setUp() {
            counter = new AtomicLongCounter(0);
        }

        @Test
        @DisplayName("Should get current value")
        void shouldGetCurrentValue() {
            counter = new AtomicLongCounter(100);

            assertThat(counter.get()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Should set new value")
        void shouldSetNewValue() {
            counter.set(500);

            assertThat(counter.get()).isEqualTo(500L);
        }

        @Test
        @DisplayName("Should return old value when setting")
        void shouldReturnOldValueWhenSetting() {
            counter = new AtomicLongCounter(100);

            long oldValue = counter.set(200);

            assertThat(oldValue).isEqualTo(100L);
            assertThat(counter.get()).isEqualTo(200L);
        }
    }

    @Nested
    @DisplayName("Add and Increment Tests")
    class AddIncrementTests {

        private AtomicLongCounter counter;

        @BeforeEach
        void setUp() {
            counter = new AtomicLongCounter(0);
        }

        @Test
        @DisplayName("Should add positive value")
        void shouldAddPositiveValue() {
            long result = counter.add(50);

            assertThat(result).isEqualTo(50L);
            assertThat(counter.get()).isEqualTo(50L);
        }

        @Test
        @DisplayName("Should add zero")
        void shouldAddZero() {
            counter = new AtomicLongCounter(100);
            long result = counter.add(0);

            assertThat(result).isEqualTo(100L);
        }

        @Test
        @DisplayName("Should add negative value")
        void shouldAddNegativeValue() {
            counter = new AtomicLongCounter(100);
            long result = counter.add(-30);

            assertThat(result).isEqualTo(70L);
        }

        @Test
        @DisplayName("Should increment by one")
        void shouldIncrementByOne() {
            long result = counter.increment();

            assertThat(result).isEqualTo(1L);
            assertThat(counter.get()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should increment multiple times")
        void shouldIncrementMultipleTimes() {
            counter.increment();
            counter.increment();
            counter.increment();

            assertThat(counter.get()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("Concurrent Operations")
    class ConcurrentTests {

        @Test
        @DisplayName("Should handle concurrent increments")
        void shouldHandleConcurrentIncrements() throws InterruptedException {
            AtomicLongCounter counter = new AtomicLongCounter(0);
            int threads = 10;
            int incrementsPerThread = 1000;
            Thread[] threadArray = new Thread[threads];

            for (int i = 0; i < threads; i++) {
                threadArray[i] = new Thread(() -> {
                    for (int j = 0; j < incrementsPerThread; j++) {
                        counter.increment();
                    }
                });
            }

            for (Thread t : threadArray) t.start();
            for (Thread t : threadArray) t.join();

            assertThat(counter.get()).isEqualTo((long) threads * incrementsPerThread);
        }

        @Test
        @DisplayName("Should handle concurrent adds")
        void shouldHandleConcurrentAdds() throws InterruptedException {
            AtomicLongCounter counter = new AtomicLongCounter(0);
            int threads = 5;
            int addsPerThread = 100;
            Thread[] threadArray = new Thread[threads];

            for (int i = 0; i < threads; i++) {
                threadArray[i] = new Thread(() -> {
                    for (int j = 0; j < addsPerThread; j++) {
                        counter.add(10);
                    }
                });
            }

            for (Thread t : threadArray) t.start();
            for (Thread t : threadArray) t.join();

            assertThat(counter.get()).isEqualTo((long) threads * addsPerThread * 10);
        }
    }
}
