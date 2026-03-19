package io.excalibur.core.exception;

/**
 * Exception thrown when checksum validation fails.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Once constructed, the exception's
 * state (message and cause) cannot be modified.</p>
 */
public class ChecksumException extends ExcaliburException {
    
    public ChecksumException(String message) {
        super(message);
    }
    
    public ChecksumException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public ChecksumException(Throwable cause) {
        super(cause);
    }
}
