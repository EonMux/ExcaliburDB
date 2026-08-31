package io.excalibur.store.state;

import java.util.concurrent.atomic.AtomicReference;

import io.excalibur.store.LifecycleState;

/**
 * Atomic state machine for segment lifecycle management.
 *
 * <p>Uses atomic operations to ensure thread-safe, atomic state transitions.
 * No external synchronization is required for state access.
 *
 * <p>All states must be from the canonical {@link LifecycleState} enum,
 * which ensures consistent state management across the entire storage engine.
 *
 * <h2>Thread-safety guarantees:</h2>
 * <ul>
 *   <li>All state reads are atomic</li>
 *   <li>All state transitions are atomic (no torn reads)</li>
 *   <li>Transitions are visible to all threads immediately after completion</li>
 *   <li>No race conditions between state transitions</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 2.0
 */
public class StateMachine {

    /**
     * Functional interface for validating state transitions.
     * @param currentState the current state
     * @param desiredState the desired new state
     * @return true if transition is allowed
     */
    @FunctionalInterface
    public interface TransitionValidator {
        boolean validate(LifecycleState currentState, LifecycleState desiredState);
    }

    /**
     * Default validator that enforces the lifecycle state transition rules.
     */
    private static final TransitionValidator DEFAULT_VALIDATOR = (current, desired) -> {
        if (current == desired) {
            return true; // No-op transition is valid
        }
        try {
            LifecycleState.validateTransition(current, desired);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    };

    /**
     * Atomic reference to current state.
     */
    private final AtomicReference<LifecycleState> stateRef;

    /**
     * Validator for allowed transitions.
     */
    private final TransitionValidator validator;

    /**
     * Transition counter.
     */
    private final java.util.concurrent.atomic.AtomicInteger transitionCount = new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Creates a new state machine with default validator.
     */
    public StateMachine() {
        this(LifecycleState.BOOTSTRAPPING);
    }

    /**
     * Creates a new state machine with custom initial state.
     * @param initialState the initial state (must be from LifecycleState enum)
     * @throws IllegalArgumentException if initialState is null
     */
    public StateMachine(LifecycleState initialState) {
        if (initialState == null) {
            throw new IllegalArgumentException("Initial state cannot be null");
        }
        this.stateRef = new AtomicReference<>(initialState);
        this.validator = DEFAULT_VALIDATOR;
    }

    /**
     * Creates a new state machine with custom validator.
     * @param validator transition validator
     */
    public StateMachine(TransitionValidator validator) {
        this(LifecycleState.BOOTSTRAPPING, validator);
    }

    /**
     * Creates a new state machine with custom initial state and validator.
     * @param initialState initial state (must be from LifecycleState enum)
     * @param validator transition validator
     * @throws IllegalArgumentException if initialState is null
     */
    public StateMachine(LifecycleState initialState, TransitionValidator validator) {
        if (initialState == null) {
            throw new IllegalArgumentException("Initial state cannot be null");
        }
        this.stateRef = new AtomicReference<>(initialState);
        this.validator = validator != null ? validator : DEFAULT_VALIDATOR;
    }

    /**
     * Performs an atomic state transition.
     *
     * @param desiredState the state to transition to (must be from LifecycleState enum)
     * @return the previous state before transition
     * @throws IllegalStateException if transition is not allowed
     * @throws IllegalArgumentException if desiredState is null
     */
    public LifecycleState transitionTo(LifecycleState desiredState) {
        if (desiredState == null) {
            throw new IllegalArgumentException("Desired state cannot be null");
        }

        // Use CAS loop to ensure atomic read-validate-write.
        // A plain get() + set() has a TOCTOU race where another thread
        // could change the state between validation and the set.
        while (true) {
            LifecycleState previousState = get();

            if (!validator.validate(previousState, desiredState)) {
                throw new IllegalStateException(
                    String.format("Invalid state transition: %s → %s",
                        previousState, desiredState)
                );
            }

            // Use the LifecycleState's built-in validation for additional safety
            LifecycleState.validateTransition(previousState, desiredState);

            // Atomically update the state only if it hasn't changed
            if (stateRef.compareAndSet(previousState, desiredState)) {
                transitionCount.incrementAndGet();
                return previousState;
            }
            // State changed concurrently - retry with fresh read
        }
    }

    /**
     * Attempts an atomic state transition with compare-and-set semantics.
     * Returns true if the transition was successful, false otherwise.
     *
     * @param desiredState the state to transition to (must be from LifecycleState enum)
     * @return true if transition succeeded, false if another thread modified the state
     *         or transition is invalid
     */
    public boolean tryTransitionTo(LifecycleState desiredState) {
        if (desiredState == null) {
            return false;
        }

        LifecycleState expectedState = get();

        while (true) {
            if (!validator.validate(expectedState, desiredState)) {
                return false;
            }

            if (stateRef.compareAndSet(expectedState, desiredState)) {
                transitionCount.incrementAndGet();
                return true;
            }

            expectedState = get();
        }
    }

    /**
     * Gets the current state.
     * @return the current state (always from LifecycleState enum)
     */
    public LifecycleState get() {
        return stateRef.get();
    }

    /**
     * Checks if the state machine is in a queryable state.
     * @return true if the segment can be queried
     */
    public boolean isQueryable() {
        return get().isQueryable();
    }

    /**
     * Checks if the state machine is in an active state (fully ready).
     * @return true if the segment is fully active
     */
    public boolean isActive() {
        return get() == LifecycleState.ACTIVE;
    }

    /**
     * Checks if the state machine is in a terminal state.
     * @return true if the segment cannot transition further
     */
    public boolean isTerminal() {
        return get() == LifecycleState.PURGED;
    }

    /**
     * Resets the state machine to its initial state.
     * @param initialState the initial state to reset to (must be from LifecycleState enum)
     * @throws IllegalArgumentException if initialState is null
     */
    public void reset(LifecycleState initialState) {
        if (initialState == null) {
            throw new IllegalArgumentException("Initial state cannot be null");
        }
        transitionCount.set(0);
        stateRef.set(initialState);
    }

    /**
     * Gets the current transition count.
     * @return number of transitions performed
     */
    public int getTransitionCount() {
        return transitionCount.get();
    }

    /**
     * Gets the current state as an integer code.
     * @return the numeric code of the current state
     */
    public int getStateCode() {
        return get().value();
    }

    /**
     * Gets the validator.
     */
    public TransitionValidator getValidator() {
        return validator;
    }

    /**
     * Validates that a state is from the canonical LifecycleState enum.
     *
     * @param state the state to validate
     * @return true if the state is valid
     */
    public static boolean isValidState(Object state) {
        return state instanceof LifecycleState;
    }

    /**
     * Requires that a state is from the canonical LifecycleState enum.
     *
     * @param state the state to validate
     * @throws IllegalArgumentException if state is null or not a LifecycleState
     */
    public static void requireValidState(Object state) {
        if (!(state instanceof LifecycleState)) {
            throw new IllegalArgumentException(
                "State must be from LifecycleState enum, got: " +
                (state == null ? "null" : state.getClass().getName())
            );
        }
    }

    /**
     * Transition history for debugging and auditing.
     */
    public static class TransitionHistory {
        private final StringBuilder history = new StringBuilder();

        public void recordTransition(String segmentId, LifecycleState from, LifecycleState to) {
            history.append(segmentId)
                   .append(" ").append(from)
                   .append(" → ").append(to)
                   .append("\n");
        }

        public String toString() {
            return history.toString();
        }
    }
}
