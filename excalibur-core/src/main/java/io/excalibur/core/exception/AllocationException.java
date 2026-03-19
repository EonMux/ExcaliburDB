package io.excalibur.core.exception;

/**
 * Exception thrown when an allocation fails.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Once constructed, the exception's
 * state (message and cause) cannot be modified.</p>
 */
public class AllocationException extends ExcaliburException {
    
    public AllocationException(String message) {
        super(message);
    }
    
    public AllocationException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public AllocationException(Throwable cause) {
        super(cause);
    }
}
