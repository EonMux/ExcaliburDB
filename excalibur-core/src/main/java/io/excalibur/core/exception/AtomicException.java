package io.excalibur.core.exception;

/**
 * Exception thrown when atomic operations fail.
 */
public class AtomicException extends ExcaliburException {
    
    public AtomicException(String message) {
        super(message);
    }
    
    public AtomicException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public AtomicException(Throwable cause) {
        super(cause);
    }
}
