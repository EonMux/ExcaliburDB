package io.excalibur.core.exception;

/**
 * Exception thrown when a file operation fails.
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
