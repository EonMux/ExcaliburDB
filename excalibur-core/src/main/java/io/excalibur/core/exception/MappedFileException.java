package io.excalibur.core.exception;

/**
 * Exception thrown when a MappedFile operation fails.
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