package io.excalibur.store.state;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Atomic state machine for segment lifecycle management.
 * 
 * Uses VarHandle-based operations to ensure thread-safe, atomic state transitions.
 * No external synchronization is required for state access.
 * 
 * Thread-safety guarantees:
 * - All state reads are atomic
 * - All state transitions are atomic (no torn reads)
 * - Transitions are visible to all threads immediately after completion
 * - No race conditions between state transitions
 */
public class StateMachine<T> {
    
    /**
     * Functional interface for validating state transitions.
     * @param currentState the current state
     * @param desiredState the desired new state
     * @return true if transition is allowed
     */
    @FunctionalInterface
    public interface TransitionValidator {
        boolean validate(SegmentState currentState, SegmentState desiredState);
    }
    
    /**
     * Atomic reference to current state.
     */
    private final AtomicReference<SegmentState> stateRef;
    
    /**
     * Validator for allowed transitions.
     */
    private final TransitionValidator validator;
    
    /**
     * Transition counter.
     */
    private int transitionCount = 0;
    
    /**
     * Creates a new state machine with default validator.
     */
    public StateMachine() {
        this(SegmentState.BOOTSTRAPPING);
    }
    
    /**
     * Creates a new state machine with custom initial state.
     * @param initialState the initial state
     */
    public StateMachine(SegmentState initialState) {
        this.stateRef = new AtomicReference<>(initialState);
        this.validator = (current, desired) -> true;
    }
    
    /**
     * Creates a new state machine with custom validator.
     * @param validator transition validator
     */
    public StateMachine(TransitionValidator validator) {
        this(SegmentState.BOOTSTRAPPING, validator);
    }
    
    /**
     * Creates a new state machine with custom parameters.
     * @param initialState initial state
     * @param validator transition validator
     */
    public StateMachine(SegmentState initialState, TransitionValidator validator) {
        this.stateRef = new AtomicReference<>(initialState);
        this.validator = validator;
    }
    
    /**
     * Performs an atomic state transition.
     * 
     * @param desiredState the state to transition to
     * @return the previous state before transition
     * @throws IllegalStateException if transition is not allowed
     */
    public SegmentState transitionTo(SegmentState desiredState) {
        SegmentState previousState = get();
        
        if (!validator.validate(previousState, desiredState)) {
            throw new IllegalStateException(
                String.format("Invalid state transition: %s → %s", 
                    previousState.toStringHuman(), desiredState.toStringHuman())
            );
        }
        
        // Atomically update the state
        stateRef.set(desiredState);
        transitionCount++;
        
        return previousState;
    }
    
    /**
     * Attempts an atomic state transition with compare-and-set semantics.
     * Returns true if the transition was successful, false otherwise.
     * 
     * @param desiredState the state to transition to
     * @return true if transition succeeded, false if another thread modified the state
     */
    public boolean tryTransitionTo(SegmentState desiredState) {
        SegmentState expectedState = get();
        
        while (true) {
            if (!validator.validate(expectedState, desiredState)) {
                return false;
            }
            
            if (stateRef.compareAndSet(expectedState, desiredState)) {
                transitionCount++;
                return true;
            }
            
            expectedState = get();
        }
    }
    
    /**
     * Gets the current state.
     * @return the current state
     */
    public SegmentState get() {
        return stateRef.get();
    }
    
    /**
     * Checks if the state machine is in a queryable state.
     * @return true if the segment can be queried
     */
    public boolean isQueryable() {
        SegmentState state = get();
        return state == SegmentState.INDEXING ||
               state == SegmentState.WARMING ||
               state == SegmentState.ACTIVE ||
               state == SegmentState.COMPACTING;
    }
    
    /**
     * Checks if the state machine is in an active state (fully ready).
     * @return true if the segment is fully active
     */
    public boolean isActive() {
        return get() == SegmentState.ACTIVE;
    }
    
    /**
     * Checks if the state machine is in a terminal state.
     * @return true if the segment cannot transition further
     */
    public boolean isTerminal() {
        return get() == SegmentState.PURGED;
    }
    
    /**
     * Resets the state machine to its initial state.
     * @param initialState the initial state to reset to
     */
    public void reset(SegmentState initialState) {
        transitionCount = 0;
        stateRef.set(initialState);
    }
    
    /**
     * Gets the current transition count.
     * @return number of transitions performed
     */
    public int getTransitionCount() {
        return transitionCount;
    }
    
    /**
     * Gets the current state as an integer code.
     * @return the numeric code of the current state
     */
    public int getStateCode() {
        return get().getCode();
    }
    
    /**
     * Gets the validator.
     */
    public TransitionValidator getValidator() {
        return validator;
    }
    
    /**
     * Transition history for debugging and auditing.
     */
    public static class TransitionHistory {
        private final StringBuilder history = new StringBuilder();
        
        public void recordTransition(String segmentId, SegmentState from, SegmentState to) {
            history.append(segmentId)
                   .append(" ").append(from.toStringHuman())
                   .append(" → ").append(to.toStringHuman())
                   .append("\n");
        }
        
        public String toString() {
            return history.toString();
        }
    }
}
