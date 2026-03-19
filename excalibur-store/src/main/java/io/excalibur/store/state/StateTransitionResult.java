package io.excalibur.store.state;

/**
 * Result of a state transition operation.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Once constructed, all state
 * (previousState, desiredState, successful, errorMessage) cannot be modified.
 * Instances may be safely shared between threads.</p>
 */
public class StateTransitionResult {
    
    /**
     * The previous state before the transition.
     */
    private final SegmentState previousState;
    
    /**
     * The desired/next state.
     */
    private final SegmentState desiredState;
    
    /**
     * Whether the transition was successful.
     */
    private final boolean successful;
    
    /**
     * Error message if the transition failed.
     */
    private final String errorMessage;
    
    /**
     * Creates a new transition result.
     * @param previousState the previous state
     * @param desiredState the desired state
     * @param successful whether the transition succeeded
     * @param errorMessage error message if failed
     */
    public StateTransitionResult(SegmentState previousState, SegmentState desiredState, 
                                  boolean successful, String errorMessage) {
        this.previousState = previousState;
        this.desiredState = desiredState;
        this.successful = successful;
        this.errorMessage = errorMessage;
    }
    
    /**
     * Creates a successful transition result.
     */
    public StateTransitionResult(SegmentState previousState, SegmentState desiredState) {
        this(previousState, desiredState, true, null);
    }
    
    /**
     * Creates a failed transition result.
     */
    public StateTransitionResult(SegmentState previousState, SegmentState desiredState, 
                                  String errorMessage) {
        this(previousState, desiredState, false, errorMessage);
    }
    
    /**
     * Gets the previous state.
     */
    public SegmentState getPreviousState() {
        return previousState;
    }
    
    /**
     * Gets the desired state.
     */
    public SegmentState getDesiredState() {
        return desiredState;
    }
    
    /**
     * Gets whether the transition was successful.
     */
    public boolean isSuccessful() {
        return successful;
    }
    
    /**
     * Gets the error message (null if successful).
     */
    public String getErrorMessage() {
        return errorMessage;
    }
    
    /**
     * Creates a string representation of the transition.
     */
    @Override
    public String toString() {
        if (successful) {
            return String.format("StateTransition[previous=%s → %s SUCCESSFUL]",
                    previousState.toStringHuman(), desiredState.toStringHuman());
        } else {
            return String.format("StateTransition[previous=%s → %s FAILED: %s]",
                    previousState.toStringHuman(), desiredState.toStringHuman(), 
                    errorMessage);
        }
    }
    
    /**
     * Checks if the result indicates a valid transition (regardless of success).
     */
    public boolean hasStates() {
        return previousState != null && desiredState != null;
    }
}
