package io.excalibur.core.exception;

/**
 * Exception thrown when checksum validation fails.
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
