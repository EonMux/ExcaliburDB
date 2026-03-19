package io.excalibur.core.exception;

/**
 * Exception thrown when a MappedFile operation fails.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Once constructed, the exception's
 * state (message and cause) cannot be modified.</p>
 */
public class MappedFileException extends RuntimeException {
    
    public MappedFileException(String message) {
        super(message);
    }
    
    public MappedFileException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public MappedFileException(Throwable cause) {
        super(cause);
    }
}