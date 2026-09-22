package com.eonmux.excalibur.core.exception;

/**
 * Exception thrown when atomic operations fail.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Once constructed, the exception's
 * state (message and cause) cannot be modified.</p>
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
