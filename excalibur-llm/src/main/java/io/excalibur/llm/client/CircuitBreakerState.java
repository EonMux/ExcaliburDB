package io.excalibur.llm.client;

/**
 * Represents the operational state of a circuit breaker.
 *
 * <p>Circuit breakers protect systems from cascading failures by temporarily
 * blocking requests to a failing service. The state machine transitions are:</p>
 *
 * <h2>State Transition Diagram</h2>
 * <pre>
 *                      failures >= threshold
 *   CLOSED ─────────────────────────────────────► OPEN
 *      ▲                                              │
 *      │                                              │ cooldown elapsed
 *      │ success                                      │
 *      │                                              ▼
 *      └──────────────────────────────────────── HALF_OPEN
 *                         failure  │
 *                                  │
 *                                  ▼
 *                                OPEN
 * </pre>
 *
 * <h2>State Descriptions</h2>
 * <ul>
 *   <li><b>CLOSED</b>: Normal operation. All requests pass through.
 *       Failure count increments on errors. When failures exceed threshold,
 *       transitions to OPEN.</li>
 *   <li><b>OPEN</b>: Circuit is tripped. All requests fail fast without
 *       attempting the operation. Remains in this state until cooldown
 *       period expires.</li>
 *   <li><b>HALF_OPEN</b>: Cooldown expired. A single probe request is
 *       allowed through. Success transitions to CLOSED; failure transitions
 *       back to OPEN with cooldown reset.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This enum is immutable and inherently thread-safe. State transitions
 * must be managed by the containing {@link CircuitBreaker} class with
 * proper synchronization.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>State transitions follow the circuit breaker pattern exactly</li>
 *   <li>OPEN state must have an associated cooldown timestamp (managed by CircuitBreaker)</li>
 *   <li>HALF_OPEN is only valid after cooldown expiration</li>
 *   <li>State is used for fast-failure decisions in concurrent environments</li>
 * </ul>
 *
 * @see CircuitBreaker
 */
public enum CircuitBreakerState {

    /**
     * Circuit is closed - normal operation.
     *
     * <p>In this state, all requests are allowed to pass through to the
     * protected service. Failures are counted, and if the failure threshold
     * is reached, the circuit transitions to OPEN.</p>
     *
     * <p>Domain constraint: This is the initial state of a healthy circuit breaker.</p>
     */
    CLOSED,

    /**
     * Circuit is open - fail fast.
     *
     * <p>In this state, all requests are rejected immediately without
     * attempting to call the protected service. This prevents cascading
     * failures and gives the downstream service time to recover.</p>
     *
     * <p>After the cooldown period elapses, the circuit transitions to
     * HALF_OPEN to test if the service has recovered.</p>
     *
     * <p>Domain constraint: Must track the timestamp when the circuit opened
     * to calculate cooldown expiration (managed by CircuitBreaker).</p>
     */
    OPEN,

    /**
     * Circuit is half-open - testing recovery.
     *
     * <p>In this state, a limited number of requests are allowed through
     * to test if the protected service has recovered. If a request succeeds,
     * the circuit transitions to CLOSED. If it fails, the circuit transitions
     * back to OPEN and the cooldown timer is reset.</p>
     *
     * <p>Domain constraint: Only reachable from OPEN after cooldown
     * expiration. Must handle concurrent probe attempts safely.</p>
     */
    HALF_OPEN;

    /**
     * Checks if requests should be allowed in this state.
     *
     * <p>Domain correctness: This is used for the fast-failure check.
     * Only CLOSED and HALF_OPEN states allow requests to proceed.</p>
     *
     * @return {@code true} if requests can proceed, {@code false} if they should fail fast
     */
    public boolean allowsRequests() {
        return this != OPEN;
    }

    /**
     * Checks if this state represents a healthy circuit.
     *
     * <p>A healthy circuit is one that is fully operational (CLOSED).
     * HALF_OPEN is considered recovering, and OPEN is considered unhealthy.</p>
     *
     * @return {@code true} if the circuit is healthy (CLOSED), {@code false} otherwise
     */
    public boolean isHealthy() {
        return this == CLOSED;
    }

    /**
     * Checks if the circuit is in a recovery probe state.
     *
     * <p>HALF_OPEN indicates the circuit is testing if the downstream
     * service has recovered by allowing a limited number of probe requests.</p>
     *
     * @return {@code true} if in HALF_OPEN state, {@code false} otherwise
     */
    public boolean isRecovering() {
        return this == HALF_OPEN;
    }

    /**
     * Checks if the circuit is tripped (failing fast).
     *
     * <p>OPEN indicates the circuit has detected too many failures and
     * is blocking all requests until the cooldown period expires.</p>
     *
     * @return {@code true} if in OPEN state, {@code false} otherwise
     */
    public boolean isTripped() {
        return this == OPEN;
    }
}
