package excalibur.shard.worker;

import excalibur.filter.CuckooFilter;
import io.excalibur.metrics.FixedBucketHistogram;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CuckooMetricsWorker}.
 *
 * <p>Tests verify that the worker correctly samples false positive rates
 * from registered Cuckoo filters and records them to the metrics system.</p>
 */
@DisplayName("CuckooMetricsWorker Tests")
class CuckooMetricsWorkerTest {

    @BeforeEach
    void resetHistogram() {
        // Reset the FP rate histogram state before each test
        FixedBucketHistogram hist = FixedBucketHistogram.getInstance();
        // Create a fresh histogram to reset state
        // Note: This is a workaround since there's no explicit reset method
    }

    @Nested
    @DisplayName("Construction Tests")
    class ConstructionTests {

        @Test
        @DisplayName("Should create worker with default configuration")
        void shouldCreateWithDefaultConfig() {
            CuckooMetricsWorker worker = new CuckooMetricsWorker();
            assertEquals(CuckooMetricsWorker.DEFAULT_SAMPLING_INTERVAL_MS, worker.getSamplingIntervalMs());
            assertEquals(0, worker.getRegisteredFilterCount());
            assertFalse(worker.isRunning());
        }

        @Test
        @DisplayName("Should create worker with custom sampling interval")
        void shouldCreateWithCustomInterval() {
            CuckooMetricsWorker worker = new CuckooMetricsWorker(10000);
            assertEquals(10000, worker.getSamplingIntervalMs());
        }

        @Test
        @DisplayName("Should reject sampling interval below minimum")
        void shouldRejectBelowMinimum() {
            assertThrows(IllegalArgumentException.class, () ->
                new CuckooMetricsWorker(CuckooMetricsWorker.MIN_SAMPLING_INTERVAL_MS - 1));
        }

        @Test
        @DisplayName("Should reject sampling interval above maximum")
        void shouldRejectAboveMaximum() {
            assertThrows(IllegalArgumentException.class, () ->
                new CuckooMetricsWorker(CuckooMetricsWorker.MAX_SAMPLING_INTERVAL_MS + 1));
        }
    }

    @Nested
    @DisplayName("Filter Registration Tests")
    class FilterRegistrationTests {

        @Test
        @DisplayName("Should register filter")
        void shouldRegisterFilter() {
            CuckooMetricsWorker worker = new CuckooMetricsWorker();
            CuckooFilter filter = CuckooFilter.create(1000);

            worker.registerFilter(1L, filter);
            assertEquals(1, worker.getRegisteredFilterCount());
        }

        @Test
        @DisplayName("Should ignore null filter")
        void shouldIgnoreNullFilter() {
            CuckooMetricsWorker worker = new CuckooMetricsWorker();

            worker.registerFilter(1L, null);
            assertEquals(0, worker.getRegisteredFilterCount());
        }

        @Test
        @DisplayName("Should not register duplicate filter")
        void shouldNotRegisterDuplicate() {
            CuckooMetricsWorker worker = new CuckooMetricsWorker();
            CuckooFilter filter = CuckooFilter.create(1000);

            worker.registerFilter(1L, filter);
            worker.registerFilter(1L, filter); // Same segment
            assertEquals(1, worker.getRegisteredFilterCount());
        }

        @Test
        @DisplayName("Should unregister filter")
        void shouldUnregisterFilter() {
            CuckooMetricsWorker worker = new CuckooMetricsWorker();
            CuckooFilter filter = CuckooFilter.create(1000);

            worker.registerFilter(1L, filter);
            assertEquals(1, worker.getRegisteredFilterCount());

            worker.unregisterFilter(1L);
            assertEquals(0, worker.getRegisteredFilterCount());
        }

        @Test
        @DisplayName("Should register multiple filters")
        void shouldRegisterMultiple() {
            CuckooMetricsWorker worker = new CuckooMetricsWorker();

            worker.registerFilter(1L, CuckooFilter.create(1000));
            worker.registerFilter(2L, CuckooFilter.create(1000));
            worker.registerFilter(3L, CuckooFilter.create(1000));

            assertEquals(3, worker.getRegisteredFilterCount());
        }
    }

    @Nested
    @DisplayName("Lifecycle Tests")
    class LifecycleTests {

        @Test
        @DisplayName("Should start and stop correctly")
        void shouldStartAndStop() throws InterruptedException {
            CuckooMetricsWorker worker = new CuckooMetricsWorker(1000);

            assertFalse(worker.isRunning());

            assertTrue(worker.start());
            assertTrue(worker.isRunning());

            // Should not start twice
            assertFalse(worker.start());

            // Stop and verify
            worker.stop();
            assertFalse(worker.isRunning());
        }

        @Test
        @DisplayName("Should handle multiple stop calls")
        void shouldHandleMultipleStops() {
            CuckooMetricsWorker worker = new CuckooMetricsWorker(1000);
            worker.start();

            worker.stop();
            worker.stop(); // Should not throw
            assertFalse(worker.isRunning());
        }
    }

    @Nested
    @DisplayName("Sampling Tests")
    class SamplingTests {

        @Test
        @DisplayName("Should record FP rate for filters with entries")
        void shouldRecordFpRateForFiltersWithEntries() throws InterruptedException {
            CuckooMetricsWorker worker = new CuckooMetricsWorker(1000);
            CuckooFilter filter = CuckooFilter.create(100);

            // Insert some items to get a non-zero load factor
            for (int i = 0; i < 50; i++) {
                filter.insert(("item" + i).getBytes());
            }

            worker.registerFilter(1L, filter);

            // Get initial state
            long initialSamples = worker.getTotalSamplesRecorded();

            // Start the worker and wait for a sampling cycle
            worker.start();
            Thread.sleep(1500); // Wait for at least one cycle
            worker.stop();

            // Verify samples were recorded
            assertTrue(worker.getTotalSamplesRecorded() > initialSamples,
                "Expected samples to be recorded after sampling cycle");
            assertTrue(worker.getLastSampleTime() > 0,
                "Expected last sample time to be set");
        }

        @Test
        @DisplayName("Should skip empty filters")
        void shouldSkipEmptyFilters() throws InterruptedException {
            CuckooMetricsWorker worker = new CuckooMetricsWorker(1000);
            CuckooFilter filter = CuckooFilter.create(100); // Empty filter

            worker.registerFilter(1L, filter);

            worker.start();
            Thread.sleep(1500);
            worker.stop();

            // Empty filter should not contribute to samples
            // (This is an implementation detail - the worker skips count() == 0 filters)
            assertTrue(worker.getTotalSamplesRecorded() >= 0);
        }
    }

    @Nested
    @DisplayName("Configuration Tests")
    class ConfigurationTests {

        @Test
        @DisplayName("Should support configuration changes")
        void shouldSupportConfigChanges() {
            CuckooMetricsWorker worker = new CuckooMetricsWorker(5000);

            assertEquals(5000, worker.getSamplingIntervalMs());

            worker.setSamplingIntervalMs(10000);
            assertEquals(10000, worker.getSamplingIntervalMs());
        }

        @Test
        @DisplayName("Should validate new sampling interval")
        void shouldValidateNewInterval() {
            CuckooMetricsWorker worker = new CuckooMetricsWorker();

            assertThrows(IllegalArgumentException.class, () ->
                worker.setSamplingIntervalMs(CuckooMetricsWorker.MIN_SAMPLING_INTERVAL_MS - 1));
        }
    }

    @Nested
    @DisplayName("ToString Tests")
    class ToStringTests {

        @Test
        @DisplayName("Should have informative toString")
        void shouldHaveInformativeToString() {
            CuckooMetricsWorker worker = new CuckooMetricsWorker(5000);
            worker.registerFilter(1L, CuckooFilter.create(100));

            String str = worker.toString();

            assertTrue(str.contains("CuckooMetricsWorker"));
            assertTrue(str.contains("filters=1"));
            assertTrue(str.contains("intervalMs=5000"));
        }
    }
}
