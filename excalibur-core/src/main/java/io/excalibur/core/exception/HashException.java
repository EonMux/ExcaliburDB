package io.excalibur.core.exception;

/**
 * Exception thrown when hash operations fail.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Once constructed, the exception's
 * state (message and cause) cannot be modified.</p>
 */
public class HashException extends ExcaliburException {
    
    public HashException(String message) {
        super(message);
    }
    
    public HashException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public HashException(Throwable cause) {
        super(cause);
    }
}
