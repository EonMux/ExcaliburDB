package io.excalibur.core.exception;

/**
 * Exception thrown when a file operation fails.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Once constructed, the exception's
 * state (message and cause) cannot be modified.</p>
 */
public class FileException extends ExcaliburException {
    
    public FileException(String message) {
        super(message);
    }
    
    public FileException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public FileException(Throwable cause) {
        super(cause);
    }
}
