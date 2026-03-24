package excalibur.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for backpressure mechanism in {@link IndexOrchestrator}.
 *
 * <p>Tests the backpressure API contract and constants.</p>
 */
@DisplayName("IndexOrchestrator Backpressure Tests")
class BackpressureTest {

    @Nested
    @DisplayName("Constants")
    class Constants {

        @Test
        @DisplayName("DEFAULT_BACKPRESSURE_TIMEOUT_MS should be 30 seconds")
        void defaultTimeoutShouldBe30Seconds() {
            assertEquals(30_000L, IndexOrchestrator.DEFAULT_BACKPRESSURE_TIMEOUT_MS);
        }

        @Test
        @DisplayName("DEFAULT_HNSW_QUEUE_DEPTH_LIMIT should be 100000")
        void defaultQueueDepthLimitShouldBe100000() {
            assertEquals(100_000, IndexOrchestrator.DEFAULT_HNSW_QUEUE_DEPTH_LIMIT);
        }

        @Test
        @DisplayName("DEFAULT_EPOCH_TICK_INTERVAL_MS should be 100")
        void defaultEpochTickIntervalShouldBe100() {
            assertEquals(100L, IndexOrchestrator.DEFAULT_EPOCH_TICK_INTERVAL_MS);
        }
    }

    @Nested
    @DisplayName("API Signature Tests")
    class ApiSignatureTests {

        @Test
        @DisplayName("awaitBackpressureRelease() overload exists with correct signature")
        void awaitBackpressureReleaseOverloadExists() {
            // This test verifies the method exists with the correct signature
            // by using reflection to check the method is present
            assertDoesNotThrow(() -> {
                IndexOrchestrator.class.getMethod("awaitBackpressureRelease");
                IndexOrchestrator.class.getMethod("awaitBackpressureRelease", long.class);
            }, "awaitBackpressureRelease methods should exist");
        }

        @Test
        @DisplayName("isBackpressured method exists and returns boolean")
        void isBackpressuredMethodExists() {
            assertDoesNotThrow(() -> {
                var method = IndexOrchestrator.class.getMethod("isBackpressured");
                assertEquals(boolean.class, method.getReturnType(),
                    "isBackpressured should return boolean");
            }, "isBackpressured method should exist");
        }

        @Test
        @DisplayName("getHnswQueueDepth method exists")
        void getHnswQueueDepthMethodExists() {
            assertDoesNotThrow(() -> {
                var method = IndexOrchestrator.class.getMethod("getHnswQueueDepth");
                assertEquals(long.class, method.getReturnType(),
                    "getHnswQueueDepth should return long");
            }, "getHnswQueueDepth method should exist");
        }

        @Test
        @DisplayName("getHnswQueueDepthLimit method exists")
        void getHnswQueueDepthLimitMethodExists() {
            assertDoesNotThrow(() -> {
                var method = IndexOrchestrator.class.getMethod("getHnswQueueDepthLimit");
                assertEquals(int.class, method.getReturnType(),
                    "getHnswQueueDepthLimit should return int");
            }, "getHnswQueueDepthLimit method should exist");
        }

        @Test
        @DisplayName("awaitBackpressureRelease returns boolean")
        void awaitBackpressureReleaseReturnsBoolean() throws NoSuchMethodException {
            var methodWithTimeout = IndexOrchestrator.class.getMethod("awaitBackpressureRelease", long.class);
            assertEquals(boolean.class, methodWithTimeout.getReturnType(),
                "awaitBackpressureRelease(long) should return boolean");

            var methodDefault = IndexOrchestrator.class.getMethod("awaitBackpressureRelease");
            assertEquals(boolean.class, methodDefault.getReturnType(),
                "awaitBackpressureRelease() should return boolean");
        }

        @Test
        @DisplayName("awaitBackpressureRelease(long) takes long parameter")
        void awaitBackpressureReleaseTakesLongParameter() throws NoSuchMethodException {
            var method = IndexOrchestrator.class.getMethod("awaitBackpressureRelease", long.class);
            var paramTypes = method.getParameterTypes();
            assertEquals(1, paramTypes.length, "Should have one parameter");
            assertEquals(long.class, paramTypes[0], "Parameter should be long");
        }
    }
}
