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
 * <h2>Probing is rationed</h2>
 * <p>Half-opening admits a bounded number of callers - one by default - and keeps failing the
 * rest fast until a probe settles the state. Admitting everyone who happens to be waiting
 * would hand the provider its entire backlog in a single burst at the exact moment it is least
 * able to take it, which is the stampede the breaker exists to prevent; spread over a cooldown
 * those requests were at least arriving one at a time.</p>
 *
 * <p>A probe that never reports back - a caller killed, or stuck in a read that outlives every
 * timeout - would otherwise hold the only permit forever and leave the endpoint unreachable
 * for good. The permits are therefore reissued once a probe has had a full cooldown period to
 * answer, which bounds the damage of a lost caller at one extra cooldown.</p>
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
     * Default number of callers admitted as probes when the circuit half-opens.
     *
     * <p>One. A single answer is all that is needed to tell a recovered provider from one that
     * is still down, and every additional probe is load applied to something that has just
     * demonstrated it cannot take load.</p>
     */
    public static final int DEFAULT_HALF_OPEN_PROBES = 1;

    /**
     * Smallest useful probe allowance.
     *
     * <p>Zero would mean a circuit that opens once and never reopens.</p>
     */
    public static final int MIN_HALF_OPEN_PROBES = 1;

    /**
     * Largest accepted probe allowance.
     *
     * <p>Bounded so that a misconfiguration cannot turn the half-open state back into the
     * unlimited admission this replaces.</p>
     */
    public static final int MAX_HALF_OPEN_PROBES = 100;

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
        /** Probes still admissible in HALF_OPEN; meaningless in the other states. */
        final int probePermits;
        /** When the current half-open window began, used to reissue permits a probe abandoned. */
        final long halfOpenAtMillis;

        InternalState(CircuitBreakerState state, int failureCount, long openedAtMillis,
                      int probePermits, long halfOpenAtMillis) {
            this.state = state;
            this.failureCount = failureCount;
            this.openedAtMillis = openedAtMillis;
            this.probePermits = probePermits;
            this.halfOpenAtMillis = halfOpenAtMillis;
        }

        /** Initial state: CLOSED with 0 failures */
        static InternalState initial() {
            return new InternalState(CircuitBreakerState.CLOSED, 0, 0, 0, 0);
        }

        /** Create a new state with updated failure count */
        InternalState withFailureCount(int newFailureCount) {
            return new InternalState(this.state, newFailureCount, this.openedAtMillis,
                this.probePermits, this.halfOpenAtMillis);
        }

        /** Create a new state in OPEN with new timestamp */
        InternalState asOpenWithNewTimestamp(long timestamp) {
            return new InternalState(CircuitBreakerState.OPEN, this.failureCount, timestamp, 0, 0);
        }

        /** Create a new state in CLOSED with reset counters */
        InternalState asClosed() {
            return new InternalState(CircuitBreakerState.CLOSED, 0, 0, 0, 0);
        }

        /**
         * Opens a half-open window holding {@code permits} probes.
         *
         * <p>openedAtMillis is preserved so callers can still ask how long the circuit has been
         * unhealthy; it is cleared only on the way to CLOSED.</p>
         */
        InternalState asHalfOpen(int permits, long now) {
            return new InternalState(CircuitBreakerState.HALF_OPEN, 0, this.openedAtMillis,
                permits, now);
        }

        /** Consumes one probe permit, leaving the half-open window otherwise untouched. */
        InternalState withProbeConsumed() {
            return new InternalState(this.state, this.failureCount, this.openedAtMillis,
                this.probePermits - 1, this.halfOpenAtMillis);
        }

        @Override
        public String toString() {
            return "InternalState{state=" + state + ", failureCount=" + failureCount +
                   ", openedAtMillis=" + openedAtMillis + ", probePermits=" + probePermits + '}';
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

    /** How many callers may probe a half-open circuit before the rest fail fast again */
    private final int halfOpenProbes;

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
        this(failureThreshold, cooldownPeriod, name, DEFAULT_HALF_OPEN_PROBES);
    }

    /**
     * Creates a circuit breaker that admits a chosen number of probes when it half-opens.
     *
     * <p>Raise the allowance above one only for a provider whose health cannot be read from a
     * single request - one behind a load balancer with a partially failed pool, say, where one
     * success proves only that one backend is up. It is load applied to a service that has just
     * failed, so the burden is on the reason for raising it.</p>
     *
     * @param failureThreshold Consecutive failures that open the circuit, in [1, 100]
     * @param cooldownPeriod   How long to stay open before probing, in [100ms, 1h]
     * @param name             Name for logging and identification
     * @param halfOpenProbes   Callers admitted per half-open window, in [1, 100]
     * @throws IllegalArgumentException if any parameter is outside its valid range
     */
    public CircuitBreaker(int failureThreshold, Duration cooldownPeriod, String name,
                          int halfOpenProbes) {
        if (halfOpenProbes < MIN_HALF_OPEN_PROBES || halfOpenProbes > MAX_HALF_OPEN_PROBES) {
            throw new IllegalArgumentException(
                "Half-open probe allowance must be in range [" + MIN_HALF_OPEN_PROBES + ", "
                    + MAX_HALF_OPEN_PROBES + "], got: " + halfOpenProbes);
        }
        this.halfOpenProbes = halfOpenProbes;

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

        LOG.debug("CircuitBreaker '{}' initialized with failureThreshold={}, cooldownPeriod={}ms, "
                + "halfOpenProbes={}",
                this.name, failureThreshold, cooldownPeriod.toMillis(), halfOpenProbes);
    }

    /**
     * Reports whether this caller may reach the provider, and reserves its place if so.
     *
     * <p>Not a pure query: in HALF_OPEN a {@code true} result consumes one of the probe
     * permits, so every call that returns true must be followed by exactly one
     * {@link #recordSuccess()} or {@link #recordFailure()}. A caller that asks and then does
     * not go costs the circuit one probe window.</p>
     *
     * <p><strong>Thread Safety:</strong> lock-free. Every transition is a compare-and-set on
     * one immutable state object, so two threads racing to half-open the circuit, or to take
     * the last probe permit, cannot both win.</p>
     *
     * @return true if the request should proceed, false if the circuit is refusing traffic
     */
    public boolean allowRequest() {
        while (true) {
            InternalState current = internalState.get();

            switch (current.state) {
                case CLOSED:
                    return true;

                case OPEN: {
                    long now = Instant.now().toEpochMilli();
                    long elapsedMillis = now - current.openedAtMillis;
                    if (elapsedMillis < cooldownPeriod.toMillis()) {
                        LOG.debug("CircuitBreaker '{}' is OPEN, rejecting request "
                                + "({}ms remaining in cooldown)",
                            name, cooldownPeriod.toMillis() - elapsedMillis);
                        return false;
                    }
                    // This caller becomes the first probe, so the window opens one permit short.
                    if (internalState.compareAndSet(current,
                            current.asHalfOpen(halfOpenProbes - 1, now))) {
                        LOG.info("CircuitBreaker '{}' transitioned from OPEN to HALF_OPEN after "
                                + "{}ms cooldown; admitting up to {} probe(s)",
                            name, elapsedMillis, halfOpenProbes);
                        return true;
                    }
                    continue;
                }

                case HALF_OPEN: {
                    if (current.probePermits > 0) {
                        if (internalState.compareAndSet(current, current.withProbeConsumed())) {
                            return true;
                        }
                        continue;
                    }
                    if (reissueProbes(current)) {
                        return true;
                    }
                    LOG.debug("CircuitBreaker '{}' is HALF_OPEN with no probe permit left, "
                        + "rejecting request", name);
                    return false;
                }

                default:
                    LOG.warn("CircuitBreaker '{}' in unknown state: {}", name, current.state);
                    return false;
            }
        }
    }

    /**
     * Starts a fresh half-open window if the outstanding probes have had their chance.
     *
     * <p>Guards against a probe that never reports: its caller may have been killed, or be
     * blocked in a read that outlived every timeout, and nothing else will ever settle the
     * state on its behalf. Without this the first such caller would hold the last permit for
     * the lifetime of the process and the circuit would never reopen for anyone. A probe is
     * given a full cooldown period to answer before its permit is written off.</p>
     *
     * @param current The half-open state observed by the caller
     * @return true if this caller was admitted as a probe in a new window
     */
    private boolean reissueProbes(InternalState current) {
        long now = Instant.now().toEpochMilli();
        if (now - current.halfOpenAtMillis < cooldownPeriod.toMillis()) {
            return false;
        }
        if (!internalState.compareAndSet(current, current.asHalfOpen(halfOpenProbes - 1, now))) {
            return false;
        }
        LOG.warn("CircuitBreaker '{}' reissuing probes; the previous probe never reported within "
            + "{}ms", name, cooldownPeriod.toMillis());
        return true;
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
     * Returns how many callers this breaker admits per half-open window.
     *
     * <p>Exposed for monitoring and for tests that need to know how many probes to expect
     * before the circuit starts refusing again.</p>
     *
     * @return Probe allowance, in [1, 100]
     */
    public int getHalfOpenProbeAllowance() {
        return halfOpenProbes;
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
        InternalState openState = new InternalState(CircuitBreakerState.OPEN, 0, now, 0, 0);
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
