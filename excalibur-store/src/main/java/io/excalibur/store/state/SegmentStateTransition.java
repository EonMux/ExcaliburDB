package io.excalibur.store.state;

/**
 * Thread-safe state transition manager for segments.
 * 
 * Provides a simplified API for performing state transitions with validation.
 * All operations are thread-safe and atomic.
 */
public class SegmentStateTransition {
    
    /**
     * Holds the state machine and transition context.
     */
    private final StateMachine<SegmentState> stateMachine;
    
    /**
     * Segment identifier for logging.
     */
    private final String segmentId;
    
    /**
     * Transition history.
     */
    protected TransitionHistory history = new TransitionHistory();
    
    /**
     * Creates a new state transition manager.
     * @param segmentId unique identifier for the segment
     */
    public SegmentStateTransition(String segmentId) {
        this.segmentId = segmentId;
        this.stateMachine = new StateMachine<>(SegmentState.BOOTSTRAPPING);
    }
    
    /**
     * Creates a state transition manager with a custom initial state.
     * @param segmentId unique identifier for the segment
     * @param initialState the initial state
     */
    public SegmentStateTransition(String segmentId, SegmentState initialState) {
        this.segmentId = segmentId;
        this.stateMachine = new StateMachine<>(initialState);
    }
    
    /**
     * Creates a state transition manager with a custom validator.
     * @param segmentId unique identifier for the segment
     * @param validator transition validator
     */
    public SegmentStateTransition(String segmentId, StateMachine.TransitionValidator validator) {
        this.segmentId = segmentId;
        this.stateMachine = new StateMachine<>(validator);
    }
    
    /**
     * Initiates a state transition.
     * @param desiredState the target state
     * @return the previous state
     * @throws IllegalStateException if transition is not allowed
     */
    public SegmentState transitionTo(SegmentState desiredState) {
        SegmentState previousState = stateMachine.get();
        stateMachine.transitionTo(desiredState);
        history.recordTransition(segmentId, previousState, desiredState);
        return previousState;
    }
    
    /**
     * Attempts a state transition without throwing exceptions.
     * @param desiredState the target state
     * @return true if transition succeeded, false if not allowed or concurrent modification
     */
    public boolean tryTransitionTo(SegmentState desiredState) {
        SegmentState previousState = stateMachine.get();
        if (stateMachine.tryTransitionTo(desiredState)) {
            history.recordTransition(segmentId, previousState, desiredState);
            return true;
        }
        return false;
    }
    
    /**
     * Gets the current state.
     * @return the current state
     */
    public SegmentState getState() {
        return stateMachine.get();
    }
    
    /**
     * Checks if the segment is queryable.
     * @return true if the segment can be queried
     */
    public boolean isQueryable() {
        return stateMachine.isQueryable();
    }
    
    /**
     * Checks if the segment is in an active state.
     * @return true if the segment is fully active
     */
    public boolean isActive() {
        return stateMachine.isActive();
    }
    
    /**
     * Checks if the segment is in a terminal state.
     * @return true if the segment cannot transition further
     */
    public boolean isTerminal() {
        return stateMachine.isTerminal();
    }
    
    /**
     * Gets the transition history.
     */
    public TransitionHistory getHistory() {
        return history;
    }
    
    /**
     * Gets the transition count.
     * @return number of transitions performed
     */
    public int getTransitionCount() {
        return stateMachine.getTransitionCount();
    }
    
    /**
     * Validates a transition before attempting it.
     * @param desiredState the desired state
     * @return true if the transition is valid
     */
    public boolean isTransitionValid(SegmentState desiredState) {
        SegmentState currentState = stateMachine.get();
        return stateMachine.getValidator().validate(currentState, desiredState);
    }
    
    /**
     * Gets the numeric state code.
     * @return the numeric code of the current state
     */
    public int getStateCode() {
        return stateMachine.getStateCode();
    }
    
    /**
     * Gets the current state as a human-readable string.
     * @return the state name
     */
    public String getStateHuman() {
        return stateMachine.get().toStringHuman();
    }
    
    /**
     * Gets a summary of the segment's state.
     * @return a summary string
     */
    public String toSummary() {
        return String.format(
            "Segment %s: state=%s (code=%d), transitions=%d, queryable=%s",
            segmentId,
            stateMachine.get().toStringHuman(),
            stateMachine.getStateCode(),
            stateMachine.getTransitionCount(),
            stateMachine.isQueryable()
        );
    }
    
    /**
     * Resets the state transition manager.
     * @param initialState the initial state to reset to
     */
    public void reset(SegmentState initialState) {
        stateMachine.reset(initialState);
        history = new TransitionHistory();
    }
    
    /**
     * Inner class for transition history.
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
