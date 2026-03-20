package io.excalibur.llm.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker for LLM API resilience.
 *
 * <p>Implements the circuit breaker pattern to prevent cascading failures when
 * the LLM endpoint is unavailable. The circuit breaker transitions between three states:</p>
 *
 * <ul>
 *   <li><strong>CLOSED</strong> - Normal operation. Requests pass through. Failures are counted.</li>
 *   <li><strong>OPEN</strong> - Circuit is open. All requests fail fast without calling the LLM.</li>
 *   <li><strong>HALF_OPEN</strong> - Testing if the endpoint has recovered. Limited requests allowed.</li>
 * </ul>
 *
 * <h2>State Transitions</h2>
 * <ul>
 *   <li>CLOSED → OPEN: When consecutive failures reach the failure threshold (default 5)</li>
 *   <li>OPEN → HALF_OPEN: After the cooldown period expires (default 60 seconds)</li>
 *   <li>HALF_OPEN → CLOSED: On successful request</li>
 *   <li>HALF_OPEN → OPEN: On failed request (cooldown resets)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All state transitions are atomic and can be safely
 * called from concurrent threads. The implementation uses:</p>
 * <ul>
 *   <li>A single {@code AtomicReference<InternalState>} containing all mutable state</li>
 *   <li>Compare-and-set (CAS) operations for lock-free state transitions</li>
 *   <li>Immutable internal state objects for consistent snapshots</li>
 * </ul>
 *
 * <h2>Invariants (guaranteed atomically)</h2>
 * <ul>
 *   <li>If state is OPEN, then openedAtMillis &gt; 0 (always)</li>
 *   <li>If state is HALF_OPEN, openedAtMillis retains the timestamp from the last OPEN state</li>
 *   <li>If state is CLOSED, then openedAtMillis == 0</li>
 *   <li>Failure count is always non-negative</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Failure threshold: positive integer in range [1, 100], default 5</li>
 *   <li>Cooldown period: positive duration in range [100ms, 1h], default 60s</li>
 *   <li>Failure count: non-negative integer, reset on state transition</li>
 *   <li>State transitions are atomic and consistent</li>
 *   <li>Sanitized-but-valid responses are NOT counted as failures</li>
 * </ul>
 *
 * @see CircuitBreakerState
 */
public final class CircuitBreaker {

    private static final Logger LOG = LoggerFactory.getLogger(CircuitBreaker.class);

    /**
     * Default failure threshold before opening the circuit.
     * Domain correctness: must be a positive integer.
     */
    public static final int DEFAULT_FAILURE_THRESHOLD = 5;

    /**
     * Minimum allowed failure threshold.
     * Domain correctness: threshold must be at least 1 to be meaningful.
     */
    public static final int MIN_FAILURE_THRESHOLD = 1;

    /**
     * Maximum allowed failure threshold.
     * Domain correctness: upper bound prevents unreasonable configuration.
     */
    public static final int MAX_FAILURE_THRESHOLD = 100;

    /**
     * Default cooldown period before transitioning from OPEN to HALF_OPEN.
     * Domain correctness: 60 seconds is a reasonable default for LLM service recovery.
     */
    public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(60);

    /**
     * Minimum cooldown period.
     * Domain correctness: at least 100ms to prevent rapid state thrashing while allowing testing.
     */
    public static final Duration MIN_COOLDOWN = Duration.ofMillis(100);

    /**
     * Maximum cooldown period.
     * Domain correctness: 1 hour maximum prevents indefinite blocking.
     */
    public static final Duration MAX_COOLDOWN = Duration.ofHours(1);

    /**
     * Immutable internal state container.
     *
     * <p>All mutable state is bundled into a single immutable object, allowing
     * atomic updates via AtomicReference. This ensures that all state variables
     * are always consistent when read together.</p>
     *
     * <p><strong>Important:</strong> openedAtMillis retains the timestamp from the last
     * OPEN state even when transitioning to HALF_OPEN. This allows callers to check
     * how long the circuit has been open. It is reset to 0 only when transitioning to CLOSED.</p>
     */
    private static final class InternalState {
        final CircuitBreakerState state;
        final int failureCount;
        final long openedAtMillis;

        InternalState(CircuitBreakerState state, int failureCount, long openedAtMillis) {
            this.state = state;
            this.failureCount = failureCount;
            this.openedAtMillis = openedAtMillis;
        }

        /** Initial state: CLOSED with 0 failures */
        static InternalState initial() {
            return new InternalState(CircuitBreakerState.CLOSED, 0, 0);
        }

        /** Create a new state with updated failure count */
        InternalState withFailureCount(int newFailureCount) {
            return new InternalState(this.state, newFailureCount, this.openedAtMillis);
        }

        /** Create a new state in OPEN with new timestamp */
        InternalState asOpenWithNewTimestamp(long timestamp) {
            return new InternalState(CircuitBreakerState.OPEN, this.failureCount, timestamp);
        }

        /** Create a new state in CLOSED with reset counters */
        InternalState asClosed() {
            return new InternalState(CircuitBreakerState.CLOSED, 0, 0);
        }

        /** Create a new state in HALF_OPEN (preserves openedAtMillis for timing queries) */
        InternalState asHalfOpen() {
            return new InternalState(CircuitBreakerState.HALF_OPEN, 0, this.openedAtMillis);
        }

        @Override
        public String toString() {
            return "InternalState{state=" + state + ", failureCount=" + failureCount +
                   ", openedAtMillis=" + openedAtMillis + '}';
        }
    }

    /** The complete internal state of the circuit breaker (atomic) */
    private final AtomicReference<InternalState> internalState;

    /** Maximum consecutive failures before opening the circuit */
    private final int failureThreshold;

    /** Time to wait in OPEN state before transitioning to HALF_OPEN */
    private final Duration cooldownPeriod;

    /** Name of this circuit breaker (for logging and metrics) */
    private final String name;

    /**
     * Creates a new CircuitBreaker with default settings.
     *
     * <p>Uses failure threshold of 5 and cooldown period of 60 seconds.</p>
     */
    public CircuitBreaker() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_COOLDOWN, "default");
    }

    /**
     * Creates a new CircuitBreaker with the specified settings.
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Failure threshold must be in range [1, 100]</li>
     *   <li>Cooldown period must be in range [100ms, 1 hour]</li>
     * </ul>
     *
     * @param failureThreshold Maximum consecutive failures before opening circuit
     * @param cooldownPeriod Time to wait before attempting recovery
     * @param name Name for logging and identification
     * @throws IllegalArgumentException if parameters are outside valid ranges
     */
    public CircuitBreaker(int failureThreshold, Duration cooldownPeriod, String name) {
        // Domain correctness: validate failure threshold is positive and in range
        if (failureThreshold < MIN_FAILURE_THRESHOLD || failureThreshold > MAX_FAILURE_THRESHOLD) {
            throw new IllegalArgumentException(
                "Failure threshold must be in range [" + MIN_FAILURE_THRESHOLD + ", " +
                MAX_FAILURE_THRESHOLD + "], got: " + failureThreshold);
        }

        // Domain correctness: validate cooldown period is positive and in range
        if (cooldownPeriod == null) {
            cooldownPeriod = DEFAULT_COOLDOWN;
        }
        if (cooldownPeriod.compareTo(MIN_COOLDOWN) < 0 || cooldownPeriod.compareTo(MAX_COOLDOWN) > 0) {
            throw new IllegalArgumentException(
                "Cooldown period must be in range [" + MIN_COOLDOWN + ", " +
                MAX_COOLDOWN + "], got: " + cooldownPeriod);
        }

        this.failureThreshold = failureThreshold;
        this.cooldownPeriod = cooldownPeriod;
        this.name = name != null ? name : "unnamed";
        this.internalState = new AtomicReference<>(InternalState.initial());

        LOG.debug("CircuitBreaker '{}' initialized with failureThreshold={}, cooldownPeriod={}ms",
                this.name, failureThreshold, cooldownPeriod.toMillis());
    }

    /**
     * Checks if a request is allowed to proceed.
     *
     * <p>If the circuit is OPEN, checks if the cooldown period has elapsed
     * and transitions to HALF_OPEN if so.</p>
     *
     * <p><strong>Thread Safety:</strong> This method is lock-free and thread-safe.
     * State transitions use CAS to ensure atomicity.</p>
     *
     * @return true if the request should proceed, false if the circuit is open
     */
    public boolean allowRequest() {
        InternalState current = internalState.get();

        switch (current.state) {
            case CLOSED:
                return true;

            case OPEN:
                // Check if cooldown period has elapsed
                long elapsedMillis = Instant.now().toEpochMilli() - current.openedAtMillis;

                if (elapsedMillis >= cooldownPeriod.toMillis()) {
                    // Try to transition to HALF_OPEN atomically
                    InternalState halfOpenState = current.asHalfOpen();
                    if (internalState.compareAndSet(current, halfOpenState)) {
                        LOG.info("CircuitBreaker '{}' transitioned from OPEN to HALF_OPEN after {}ms cooldown",
                                name, elapsedMillis);
                        return true;
                    }
                    // Another thread beat us to the transition - re-read and check
                    InternalState newState = internalState.get();
                    return newState.state == CircuitBreakerState.HALF_OPEN;
                }

                LOG.debug("CircuitBreaker '{}' is OPEN, rejecting request ({}ms remaining in cooldown)",
                        name, cooldownPeriod.toMillis() - elapsedMillis);
                return false;

            case HALF_OPEN:
                // In HALF_OPEN, we allow requests through to test the waters
                return true;

            default:
                LOG.warn("CircuitBreaker '{}' in unknown state: {}", name, current.state);
                return false;
        }
    }

    /**
     * Records a successful request.
     *
     * <p>If the circuit is in HALF_OPEN state, transitions to CLOSED.
     * Resets the failure counter in any state.</p>
     *
     * <p><strong>Thread Safety:</strong> This method uses CAS loops to ensure
     * atomic state updates even under concurrent access.</p>
     */
    public void recordSuccess() {
        // Use CAS loop to atomically update state
        InternalState current;
        InternalState updated;
        do {
            current = internalState.get();

            if (current.state == CircuitBreakerState.HALF_OPEN) {
                // Transition from HALF_OPEN to CLOSED
                updated = current.asClosed();
            } else if (current.failureCount > 0) {
                // Just reset failure count
                updated = current.withFailureCount(0);
            } else {
                // No change needed
                return;
            }
        } while (!internalState.compareAndSet(current, updated));

        if (current.state == CircuitBreakerState.HALF_OPEN) {
            LOG.info("CircuitBreaker '{}' transitioned from HALF_OPEN to CLOSED after successful request", name);
        } else if (current.failureCount > 0) {
            LOG.debug("CircuitBreaker '{}' reset failure count from {} to 0 after success",
                    name, current.failureCount);
        }
    }

    /**
     * Records a failed request.
     *
     * <p>If the circuit is CLOSED, increments the failure counter and
     * transitions to OPEN if the threshold is reached.</p>
     *
     * <p>If the circuit is HALF_OPEN, immediately transitions back to OPEN
     * and resets the cooldown timer.</p>
     *
     * <p><strong>Important:</strong> Only call this method for actual failures
     * (network errors, HTTP 5xx, etc.). Sanitized-but-valid responses should
     * NOT be counted as failures - call {@link #recordSuccess()} instead.</p>
     *
     * <p><strong>Thread Safety:</strong> This method uses CAS loops to ensure
     * atomic state updates. Multiple threads incrementing failure count concurrently
     * will not corrupt state.</p>
     */
    public void recordFailure() {
        // Use CAS loop to atomically update state
        InternalState current;
        InternalState updated;
        do {
            current = internalState.get();

            switch (current.state) {
                case CLOSED:
                    int newFailureCount = current.failureCount + 1;
                    if (newFailureCount >= failureThreshold) {
                        // Transition to OPEN with timestamp
                        long now = Instant.now().toEpochMilli();
                        updated = current.asOpenWithNewTimestamp(now);
                    } else {
                        // Just increment failure count
                        updated = current.withFailureCount(newFailureCount);
                    }
                    break;

                case HALF_OPEN:
                    // Failed during recovery test - go back to OPEN with new timestamp
                    long now = Instant.now().toEpochMilli();
                    updated = current.asOpenWithNewTimestamp(now);
                    break;

                case OPEN:
                    // Already open - no change needed
                    LOG.debug("CircuitBreaker '{}' already OPEN, ignoring additional failure", name);
                    return;

                default:
                    LOG.warn("CircuitBreaker '{}' in unknown state during failure: {}", name, current.state);
                    return;
            }
        } while (!internalState.compareAndSet(current, updated));

        // Log the transition
        if (current.state == CircuitBreakerState.CLOSED && updated.state == CircuitBreakerState.OPEN) {
            LOG.warn("CircuitBreaker '{}' transitioned from CLOSED to OPEN after {} consecutive failures (threshold={})",
                    name, updated.failureCount, failureThreshold);
        } else if (current.state == CircuitBreakerState.HALF_OPEN && updated.state == CircuitBreakerState.OPEN) {
            LOG.warn("CircuitBreaker '{}' transitioned from HALF_OPEN back to OPEN after failed test request", name);
        } else if (current.state == CircuitBreakerState.CLOSED) {
            LOG.debug("CircuitBreaker '{}' recorded failure {}/{}",
                    name, updated.failureCount, failureThreshold);
        }
    }

    /**
     * Returns the current state of the circuit breaker.
     *
     * <p>Note: If the circuit is OPEN and cooldown has elapsed, this method
     * returns HALF_OPEN (reflecting the effective state even if the internal
     * state hasn't transitioned yet).</p>
     *
     * @return Current state (never null)
     */
    public CircuitBreakerState getState() {
        InternalState current = internalState.get();
        if (current.state == CircuitBreakerState.OPEN) {
            // Check if cooldown has elapsed
            long elapsedMillis = Instant.now().toEpochMilli() - current.openedAtMillis;
            if (elapsedMillis >= cooldownPeriod.toMillis()) {
                return CircuitBreakerState.HALF_OPEN;
            }
        }
        return current.state;
    }

    /**
     * Returns the current failure count.
     *
     * <p>This is the number of consecutive failures recorded while in CLOSED state.
     * Reset to 0 on success or state transition.</p>
     *
     * @return Current failure count (non-negative)
     */
    public int getFailureCount() {
        return internalState.get().failureCount;
    }

    /**
     * Returns the configured failure threshold.
     *
     * @return Failure threshold (positive integer in range [1, 100])
     */
    public int getFailureThreshold() {
        return failureThreshold;
    }

    /**
     * Returns the configured cooldown period.
     *
     * @return Cooldown period (never null)
     */
    public Duration getCooldownPeriod() {
        return cooldownPeriod;
    }

    /**
     * Returns the name of this circuit breaker.
     *
     * @return Name (never null)
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the timestamp when the circuit opened, or 0 if never opened.
     *
     * @return Opening timestamp in milliseconds since epoch, or 0 if closed
     */
    public long getOpenedAtMillis() {
        return internalState.get().openedAtMillis;
    }

    /**
     * Returns a snapshot of the current internal state for atomic consistency checks.
     *
     * <p>This method returns all state variables atomically, ensuring they are
     * consistent with each other (no TOCTOU race conditions).</p>
     *
     * @return array of [state, failureCount, openedAtMillis]
     */
    public Object[] getStateSnapshot() {
        InternalState current = internalState.get();
        return new Object[]{current.state, current.failureCount, current.openedAtMillis};
    }

    /**
     * Resets the circuit breaker to CLOSED state.
     *
     * <p>This is primarily useful for testing or administrative operations.
     * In normal operation, the circuit breaker should transition naturally.</p>
     *
     * <p><strong>Thread Safety:</strong> This method is atomic. All state variables
     * are updated together in a single CAS operation.</p>
     */
    public void reset() {
        InternalState closed = InternalState.initial();
        InternalState previous = internalState.getAndSet(closed);
        if (previous.state != CircuitBreakerState.CLOSED) {
            LOG.info("CircuitBreaker '{}' manually reset to CLOSED (was {})", name, previous.state);
        }
    }

    /**
     * Forces the circuit breaker to OPEN state.
     *
     * <p>This is useful for testing or manual intervention when the downstream
     * service is known to be unhealthy.</p>
     *
     * <p><strong>Thread Safety:</strong> This method is atomic. All state variables
     * are updated together in a single CAS operation.</p>
     */
    public void trip() {
        long now = Instant.now().toEpochMilli();
        InternalState openState = new InternalState(CircuitBreakerState.OPEN, 0, now);
        internalState.set(openState);
        LOG.warn("CircuitBreaker '{}' manually tripped to OPEN", name);
    }

    @Override
    public String toString() {
        InternalState current = internalState.get();
        return "CircuitBreaker{" +
                "name='" + name + '\'' +
                ", state=" + current.state +
                ", failureCount=" + current.failureCount +
                ", failureThreshold=" + failureThreshold +
                ", cooldownPeriod=" + cooldownPeriod +
                '}';
    }
}
