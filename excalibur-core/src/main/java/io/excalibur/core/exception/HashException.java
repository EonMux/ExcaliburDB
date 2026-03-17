package io.excalibur.core.exception;

/**
 * Exception thrown when hash operations fail.
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
