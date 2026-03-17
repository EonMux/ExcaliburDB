package io.excalibur.core.exception;

/**
 * Exception thrown when an allocation fails.
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
