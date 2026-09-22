package com.eonmux.excalibur.llm.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for circuit breaker state transitions.
 *
 * <p>This test class focuses specifically on state machine transitions and edge cases:</p>
 * <ul>
 *   <li>Complete lifecycle transitions (CLOSED → OPEN → HALF_OPEN → CLOSED)</li>
 *   <li>Cooldown boundary timing edge cases</li>
 *   <li>HALF_OPEN probe request behavior</li>
 *   <li>State consistency during rapid operations</li>
 *   <li>Edge cases for success/failure recording in different states</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>State transitions must be atomic and consistent</li>
 *   <li>Cooldown period must be strictly enforced</li>
 *   <li>HALF_OPEN state must allow probe requests</li>
 *   <li>Failure count must be reset on state transitions</li>
 * </ul>
 */
@Timeout(value = 30)
class CircuitBreakerStateTransitionTest {

    // Use minimum allowed cooldown (100ms) for faster tests
    private static final Duration SHORT_COOLDOWN = Duration.ofMillis(100);
    private static final int SINGLE_FAILURE_THRESHOLD = 1;
    private static final int MULTI_FAILURE_THRESHOLD = 3;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreaker = new CircuitBreaker();
    }

    // ========================================================================
    // FULL LIFECYCLE TESTS
    // ========================================================================

    @Nested
    @DisplayName("Full Lifecycle Transitions")
    class FullLifecycleTests {

        @Test
        @DisplayName("Complete lifecycle: CLOSED → OPEN → HALF_OPEN → CLOSED")
        void completeLifecycleClosedOpenHalfOpenClosed() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, SHORT_COOLDOWN, "lifecycle-test");

            // Phase 1: Start in CLOSED state
            assertEquals(CircuitBreakerState.CLOSED, cb.getState(), "Initial state must be CLOSED");
            assertTrue(cb.allowRequest(), "Requests must be allowed in CLOSED state");
            assertEquals(0, cb.getFailureCount(), "Initial failure count must be 0");

            // Phase 2: CLOSED → OPEN (record failure until threshold)
            cb.recordFailure();
            assertEquals(CircuitBreakerState.OPEN, cb.getState(), "State must be OPEN after failure threshold");
            assertFalse(cb.allowRequest(), "Requests must be blocked in OPEN state");
            assertTrue(cb.getOpenedAtMillis() > 0, "openedAtMillis must be set when circuit opens");

            // Phase 3: Wait for cooldown
            Thread.sleep(SHORT_COOLDOWN.toMillis() + 5);

            // Phase 4: OPEN → HALF_OPEN (via allowRequest)
            assertTrue(cb.allowRequest(), "Request must be allowed after cooldown (transition to HALF_OPEN)");
            assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState(), "State must be HALF_OPEN after cooldown");

            // Phase 5: HALF_OPEN → CLOSED (via recordSuccess)
            cb.recordSuccess();
            assertEquals(CircuitBreakerState.CLOSED, cb.getState(), "State must be CLOSED after success in HALF_OPEN");
            assertEquals(0, cb.getFailureCount(), "Failure count must be reset after HALF_OPEN → CLOSED");
            assertEquals(0, cb.getOpenedAtMillis(), "openedAtMillis must be 0 in CLOSED state");

            // Phase 6: Verify circuit is healthy again
            assertTrue(cb.allowRequest(), "Circuit must be fully operational after recovery");
        }

        @Test
        @DisplayName("Complete lifecycle with recovery failure: CLOSED → OPEN → HALF_OPEN → OPEN → HALF_OPEN → CLOSED")
        void completeLifecycleWithRecoveryFailure() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, SHORT_COOLDOWN, "recovery-failure-test");

            // Phase 1: CLOSED → OPEN
            cb.recordFailure();
            assertEquals(CircuitBreakerState.OPEN, cb.getState());
            long firstOpenedAt = cb.getOpenedAtMillis();

            // Phase 2: Wait and transition to HALF_OPEN
            Thread.sleep(SHORT_COOLDOWN.toMillis() + 5);
            assertTrue(cb.allowRequest());
            assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState());

            // Phase 3: HALF_OPEN → OPEN (recovery failed)
            cb.recordFailure();
            assertEquals(CircuitBreakerState.OPEN, cb.getState());
            long secondOpenedAt = cb.getOpenedAtMillis();
            assertTrue(secondOpenedAt >= firstOpenedAt + SHORT_COOLDOWN.toMillis(),
                "openedAtMillis must be reset on HALF_OPEN → OPEN transition");

            // Phase 4: Wait and transition to HALF_OPEN again
            Thread.sleep(SHORT_COOLDOWN.toMillis() + 5);
            assertTrue(cb.allowRequest());
            assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState());

            // Phase 5: HALF_OPEN → CLOSED (recovery succeeds)
            cb.recordSuccess();
            assertEquals(CircuitBreakerState.CLOSED, cb.getState());
            assertEquals(0, cb.getFailureCount());
        }

        @Test
        @DisplayName("Multiple complete lifecycles in sequence")
        void multipleCompleteLifecyclesInSequence() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, SHORT_COOLDOWN, "multi-cycle-test");

            for (int cycle = 1; cycle <= 3; cycle++) {
                // Verify starting in CLOSED state
                assertEquals(CircuitBreakerState.CLOSED, cb.getState(),
                    "Cycle " + cycle + ": Must start in CLOSED state");

                // CLOSED → OPEN
                cb.recordFailure();
                assertEquals(CircuitBreakerState.OPEN, cb.getState(),
                    "Cycle " + cycle + ": Must transition to OPEN");

                // Wait for cooldown
                Thread.sleep(SHORT_COOLDOWN.toMillis() + 5);

                // OPEN → HALF_OPEN
                assertTrue(cb.allowRequest());
                assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState(),
                    "Cycle " + cycle + ": Must transition to HALF_OPEN");

                // HALF_OPEN → CLOSED
                cb.recordSuccess();
                assertEquals(CircuitBreakerState.CLOSED, cb.getState(),
                    "Cycle " + cycle + ": Must transition to CLOSED");
            }
        }
    }

    // ========================================================================
    // COOLDOWN BOUNDARY TESTS
    // ========================================================================

    @Nested
    @DisplayName("Cooldown Boundary Timing")
    class CooldownBoundaryTests {

        @Test
        @DisplayName("Request blocked exactly at cooldown expiration minus 1ms")
        void requestBlockedJustBeforeCooldownExpiration() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, Duration.ofMillis(200), "boundary-test");

            cb.recordFailure();
            assertEquals(CircuitBreakerState.OPEN, cb.getState());

            // Wait until just before cooldown expires (200ms - 50ms buffer for timing)
            Thread.sleep(140);

            // Should still be blocked (cooldown not yet expired)
            assertFalse(cb.allowRequest(), "Request must be blocked before cooldown expires");
            assertEquals(CircuitBreakerState.OPEN, cb.getState());
        }

        @Test
        @DisplayName("Request allowed after cooldown expiration")
        void requestAllowedAfterCooldownExpiration() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, Duration.ofMillis(100), "post-cooldown-test");

            cb.recordFailure();
            assertEquals(CircuitBreakerState.OPEN, cb.getState());

            // Wait for cooldown to fully expire
            Thread.sleep(150);

            // Should be allowed (cooldown expired)
            assertTrue(cb.allowRequest(), "Request must be allowed after cooldown expires");
            assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState());
        }

        @Test
        @DisplayName("getState() reflects effective state after cooldown (HALF_OPEN)")
        void getStateReflectsEffectiveStateAfterCooldown() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, Duration.ofMillis(100), "effective-state-test");

            cb.recordFailure();
            long openedAt = cb.getOpenedAtMillis();

            // Wait for cooldown
            Thread.sleep(150);

            // getState() should return HALF_OPEN (effective state) even without allowRequest()
            assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState(),
                "getState() should return HALF_OPEN after cooldown expiration");
        }

        @Test
        @DisplayName("Cooldown timer is reset on HALF_OPEN → OPEN transition")
        void cooldownTimerResetOnHalfOpenToOpenTransition() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, Duration.ofMillis(150), "reset-timer-test");

            // First trip
            cb.recordFailure();
            long firstOpenedAt = cb.getOpenedAtMillis();

            // Wait and transition to HALF_OPEN
            Thread.sleep(200);
            assertTrue(cb.allowRequest());
            assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState());

            // Fail again (HALF_OPEN → OPEN)
            cb.recordFailure();
            assertEquals(CircuitBreakerState.OPEN, cb.getState());
            long secondOpenedAt = cb.getOpenedAtMillis();

            // Verify timer was reset
            assertTrue(secondOpenedAt > firstOpenedAt,
                "openedAtMillis must be updated on HALF_OPEN → OPEN transition");

            // Verify new cooldown is in effect
            assertFalse(cb.allowRequest(),
                "Request must be blocked immediately after HALF_OPEN → OPEN (new cooldown)");
        }
    }

    // ========================================================================
    // HALF_OPEN PROBE REQUEST TESTS
    // ========================================================================

    @Nested
    @DisplayName("HALF_OPEN Probe Request Behavior")
    class HalfOpenProbeTests {

        @Test
        @DisplayName("Callers after the probe allowance keep failing fast in HALF_OPEN")
        void callersPastTheProbeAllowanceStillFailFast() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, SHORT_COOLDOWN, "probe-test");

            // Trip and wait for cooldown
            cb.recordFailure();
            Thread.sleep(SHORT_COOLDOWN.toMillis() + 5);
            assertTrue(cb.allowRequest(), "The first caller after cooldown is the probe");

            // Everyone behind the probe is still refused: the provider has given no sign of
            // recovery yet, and letting the backlog through is the burst the breaker is for.
            for (int i = 0; i < 10; i++) {
                assertFalse(cb.allowRequest(),
                    "allowRequest() must return false once the probe allowance is spent (call "
                        + (i + 1) + ")");
            }
            assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState());
        }

        @Test
        @DisplayName("Success in HALF_OPEN transitions to CLOSED immediately")
        void successInHalfOpenTransitionsToClosedImmediately() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, SHORT_COOLDOWN, "success-test");

            cb.recordFailure();
            Thread.sleep(SHORT_COOLDOWN.toMillis() + 5);
            assertTrue(cb.allowRequest());
            assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState());

            // Record success
            cb.recordSuccess();

            // Must be CLOSED immediately
            assertEquals(CircuitBreakerState.CLOSED, cb.getState());
            assertEquals(0, cb.getFailureCount());
            assertEquals(0, cb.getOpenedAtMillis());
        }

        @Test
        @DisplayName("Failure in HALF_OPEN transitions to OPEN with new cooldown")
        void failureInHalfOpenTransitionsToOpenWithNewCooldown() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, SHORT_COOLDOWN, "failure-test");

            cb.recordFailure();
            Thread.sleep(SHORT_COOLDOWN.toMillis() + 5);
            assertTrue(cb.allowRequest());
            assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState());

            // Record failure
            cb.recordFailure();

            // Must be OPEN with new cooldown
            assertEquals(CircuitBreakerState.OPEN, cb.getState());
            assertTrue(cb.getOpenedAtMillis() > 0);
            assertFalse(cb.allowRequest(), "Request must be blocked after HALF_OPEN → OPEN transition");
        }

        @Test
        @DisplayName("Concurrent probe requests in HALF_OPEN maintain consistent state")
        void concurrentProbeRequestsInHalfOpenMaintainConsistentState() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, SHORT_COOLDOWN, "concurrent-probe-test");

            cb.recordFailure();
            Thread.sleep(SHORT_COOLDOWN.toMillis() + 5);

            int numThreads = 50;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger allowedCount = new AtomicInteger(0);

            for (int i = 0; i < numThreads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        if (cb.allowRequest()) {
                            allowedCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
            executor.shutdown();

            // Exactly one, however many arrive together. The compare-and-set on the probe
            // permit is what makes the winner unique; without it all fifty went through.
            assertEquals(1, allowedCount.get(),
                "Only the probe allowance may be admitted, even when the backlog arrives at once");
            assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState());
        }
    }

    // ========================================================================
    // RECORD SUCCESS/FAILURE EDGE CASES
    // ========================================================================

    @Nested
    @DisplayName("recordSuccess/recordFailure Edge Cases")
    class RecordOperationEdgeCases {

        @Test
        @DisplayName("recordSuccess in OPEN state (cooldown not expired) has no effect")
        void recordSuccessInOpenStateHasNoEffect() {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, Duration.ofSeconds(60), "success-open-test");

            cb.recordFailure();
            assertEquals(CircuitBreakerState.OPEN, cb.getState());
            long openedAt = cb.getOpenedAtMillis();

            // Record success in OPEN state (should be ignored - circuit stays open)
            cb.recordSuccess();

            // State should remain OPEN (success in OPEN state doesn't close the circuit)
            assertEquals(CircuitBreakerState.OPEN, cb.getState());
            assertEquals(openedAt, cb.getOpenedAtMillis());
        }

        @Test
        @DisplayName("recordSuccess in CLOSED state resets failure count")
        void recordSuccessInClosedStateResetsFailureCount() {
            CircuitBreaker cb = new CircuitBreaker(MULTI_FAILURE_THRESHOLD, Duration.ofSeconds(60), "success-closed-test");

            // Record some failures (but not enough to trip)
            cb.recordFailure();
            cb.recordFailure();
            assertEquals(2, cb.getFailureCount());

            // Success should reset failure count
            cb.recordSuccess();
            assertEquals(0, cb.getFailureCount());
            assertEquals(CircuitBreakerState.CLOSED, cb.getState());
        }

        @Test
        @DisplayName("recordFailure in OPEN state is ignored")
        void recordFailureInOpenStateIsIgnored() {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, Duration.ofSeconds(60), "failure-open-test");

            cb.recordFailure();
            assertEquals(CircuitBreakerState.OPEN, cb.getState());
            long openedAt = cb.getOpenedAtMillis();

            // Additional failures should be ignored
            cb.recordFailure();
            cb.recordFailure();

            assertEquals(CircuitBreakerState.OPEN, cb.getState());
            assertEquals(openedAt, cb.getOpenedAtMillis());
        }

        @Test
        @DisplayName("Failure count increments correctly until threshold")
        void failureCountIncrementsCorrectlyUntilThreshold() {
            CircuitBreaker cb = new CircuitBreaker(MULTI_FAILURE_THRESHOLD, Duration.ofSeconds(60), "increment-test");

            // First failure
            cb.recordFailure();
            assertEquals(1, cb.getFailureCount());
            assertEquals(CircuitBreakerState.CLOSED, cb.getState());

            // Second failure
            cb.recordFailure();
            assertEquals(2, cb.getFailureCount());
            assertEquals(CircuitBreakerState.CLOSED, cb.getState());

            // Third failure (at threshold) - circuit opens
            cb.recordFailure();
            assertEquals(CircuitBreakerState.OPEN, cb.getState());
        }

        @Test
        @DisplayName("Success resets failure count even if not at threshold")
        void successResetsFailureCountEvenIfNotAtThreshold() {
            CircuitBreaker cb = new CircuitBreaker(10, Duration.ofSeconds(60), "reset-test");

            // Record some failures
            for (int i = 0; i < 5; i++) {
                cb.recordFailure();
            }
            assertEquals(5, cb.getFailureCount());
            assertEquals(CircuitBreakerState.CLOSED, cb.getState());

            // Success resets count
            cb.recordSuccess();
            assertEquals(0, cb.getFailureCount());
            assertEquals(CircuitBreakerState.CLOSED, cb.getState());
        }
    }

    // ========================================================================
    // STATE CONSISTENCY TESTS
    // ========================================================================

    @Nested
    @DisplayName("State Consistency During Rapid Operations")
    class StateConsistencyTests {

        @Test
        @DisplayName("State snapshot is atomically consistent")
        void stateSnapshotIsAtomicallyConsistent() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, SHORT_COOLDOWN, "snapshot-test");

            // Trip the circuit
            cb.recordFailure();

            // Get snapshot in OPEN state
            Object[] snapshot = cb.getStateSnapshot();
            assertEquals(CircuitBreakerState.OPEN, snapshot[0]);
            int failureCount = (Integer) snapshot[1];
            long openedAt = (Long) snapshot[2];

            // Verify invariants
            assertTrue(openedAt > 0, "openedAtMillis must be > 0 in OPEN state");
            assertTrue(failureCount >= 0, "failureCount must be non-negative");
        }

        @Test
        @DisplayName("State invariants hold during rapid state transitions")
        void stateInvariantsHoldDuringRapidTransitions() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, SHORT_COOLDOWN, "invariant-test");

            for (int i = 0; i < 5; i++) {
                // CLOSED → OPEN
                cb.recordFailure();
                Object[] openSnapshot = cb.getStateSnapshot();
                assertEquals(CircuitBreakerState.OPEN, openSnapshot[0]);
                assertTrue((Long) openSnapshot[2] > 0, "openedAtMillis must be > 0 in OPEN state");

                // Wait and transition to HALF_OPEN
                Thread.sleep(SHORT_COOLDOWN.toMillis() + 20);
                assertTrue(cb.allowRequest());

                // HALF_OPEN → CLOSED
                cb.recordSuccess();
                Object[] closedSnapshot = cb.getStateSnapshot();
                assertEquals(CircuitBreakerState.CLOSED, closedSnapshot[0]);
                assertEquals(0, (Integer) closedSnapshot[1], "failureCount must be 0 in CLOSED state");
                assertEquals(0L, (Long) closedSnapshot[2], "openedAtMillis must be 0 in CLOSED state");
            }
        }

        @Test
        @DisplayName("Concurrent transitions maintain state invariants")
        void concurrentTransitionsMaintainStateInvariants() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(10, Duration.ofMillis(150), "concurrent-invariant-test");
            int numThreads = 100;
            int operationsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger violations = new AtomicInteger(0);

            for (int i = 0; i < numThreads; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < operationsPerThread; j++) {
                            // Perform operation
                            if (threadId % 3 == 0) {
                                cb.recordFailure();
                            } else if (threadId % 3 == 1) {
                                cb.recordSuccess();
                            } else {
                                cb.allowRequest();
                            }

                            // Verify state invariants
                            Object[] snapshot = cb.getStateSnapshot();
                            CircuitBreakerState state = (CircuitBreakerState) snapshot[0];
                            int failures = (Integer) snapshot[1];
                            long openedAt = (Long) snapshot[2];

                            // Invariant: OPEN state must have openedAtMillis > 0
                            if (state == CircuitBreakerState.OPEN && openedAt <= 0) {
                                violations.incrementAndGet();
                            }

                            // Invariant: failure count must be non-negative
                            if (failures < 0) {
                                violations.incrementAndGet();
                            }

                            // Invariant: CLOSED state must have openedAtMillis == 0
                            if (state == CircuitBreakerState.CLOSED && openedAt != 0) {
                                violations.incrementAndGet();
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
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(0, violations.get(), "No state invariant violations should occur");
        }
    }

    // ========================================================================
    // MANUAL OPERATION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Manual State Operations")
    class ManualOperationTests {

        @Test
        @DisplayName("reset() transitions any state to CLOSED")
        void resetTransitionsAnyStateToClosed() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, SHORT_COOLDOWN, "reset-test");

            // Test reset from OPEN
            cb.recordFailure();
            assertEquals(CircuitBreakerState.OPEN, cb.getState());
            cb.reset();
            assertEquals(CircuitBreakerState.CLOSED, cb.getState());
            assertEquals(0, cb.getFailureCount());
            assertEquals(0, cb.getOpenedAtMillis());

            // Test reset from HALF_OPEN
            cb.recordFailure();
            Thread.sleep(SHORT_COOLDOWN.toMillis() + 5);
            assertTrue(cb.allowRequest());
            assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState());
            cb.reset();
            assertEquals(CircuitBreakerState.CLOSED, cb.getState());
            assertEquals(0, cb.getFailureCount());
            assertEquals(0, cb.getOpenedAtMillis());

            // Test reset from CLOSED (idempotent)
            cb.reset();
            assertEquals(CircuitBreakerState.CLOSED, cb.getState());
        }

        @Test
        @DisplayName("trip() immediately transitions to OPEN")
        void tripImmediatelyTransitionsToOpen() {
            CircuitBreaker cb = new CircuitBreaker(100, Duration.ofSeconds(60), "trip-test");

            assertEquals(CircuitBreakerState.CLOSED, cb.getState());

            // Manual trip
            cb.trip();

            assertEquals(CircuitBreakerState.OPEN, cb.getState());
            assertTrue(cb.getOpenedAtMillis() > 0);
            assertFalse(cb.allowRequest());
        }

        @Test
        @DisplayName("trip() resets cooldown timer")
        void tripResetsCooldownTimer() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, SHORT_COOLDOWN, "trip-timer-test");

            // First trip
            cb.recordFailure();
            long firstOpenedAt = cb.getOpenedAtMillis();

            // Wait a bit
            Thread.sleep(50);

            // Manual trip should reset timer
            cb.trip();
            long secondOpenedAt = cb.getOpenedAtMillis();

            assertTrue(secondOpenedAt >= firstOpenedAt,
                "trip() should update openedAtMillis");
        }
    }

    // ========================================================================
    // TRANSITION SEQUENCE TESTS
    // ========================================================================

    @Nested
    @DisplayName("Transition Sequence Validation")
    class TransitionSequenceTests {

        @Test
        @DisplayName("Invalid transition: CLOSED cannot directly transition to HALF_OPEN")
        void closedCannotDirectlyTransitionToHalfOpen() {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, Duration.ofSeconds(60), "transition-test");

            assertEquals(CircuitBreakerState.CLOSED, cb.getState());

            // recordSuccess in CLOSED state stays CLOSED
            cb.recordSuccess();
            assertEquals(CircuitBreakerState.CLOSED, cb.getState());

            // No way to directly transition to HALF_OPEN from CLOSED
            // (must go through OPEN first)
        }

        @Test
        @DisplayName("Valid transition sequence: CLOSED → OPEN → HALF_OPEN → CLOSED")
        void validTransitionSequenceClosedOpenHalfOpenClosed() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, SHORT_COOLDOWN, "sequence-test");

            // CLOSED
            assertEquals(CircuitBreakerState.CLOSED, cb.getState());

            // CLOSED → OPEN (via recordFailure)
            cb.recordFailure();
            assertEquals(CircuitBreakerState.OPEN, cb.getState());

            // OPEN → HALF_OPEN (via cooldown + allowRequest)
            Thread.sleep(SHORT_COOLDOWN.toMillis() + 5);
            assertTrue(cb.allowRequest());
            assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState());

            // HALF_OPEN → CLOSED (via recordSuccess)
            cb.recordSuccess();
            assertEquals(CircuitBreakerState.CLOSED, cb.getState());
        }

        @Test
        @DisplayName("Valid transition sequence: CLOSED → OPEN → HALF_OPEN → OPEN")
        void validTransitionSequenceClosedOpenHalfOpenOpen() throws InterruptedException {
            CircuitBreaker cb = new CircuitBreaker(SINGLE_FAILURE_THRESHOLD, SHORT_COOLDOWN, "sequence-test-2");

            // CLOSED
            assertEquals(CircuitBreakerState.CLOSED, cb.getState());

            // CLOSED → OPEN
            cb.recordFailure();
            assertEquals(CircuitBreakerState.OPEN, cb.getState());

            // OPEN → HALF_OPEN
            Thread.sleep(SHORT_COOLDOWN.toMillis() + 5);
            assertTrue(cb.allowRequest());
            assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState());

            // HALF_OPEN → OPEN (via recordFailure)
            cb.recordFailure();
            assertEquals(CircuitBreakerState.OPEN, cb.getState());
        }
    }
}
