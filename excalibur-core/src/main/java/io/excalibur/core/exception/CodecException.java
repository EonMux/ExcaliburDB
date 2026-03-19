package io.excalibur.core.exception;

/**
 * Exception thrown when serialization/deserialization operations fail.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Once constructed, the exception's
 * state (message and cause) cannot be modified.</p>
 */
public class CodecException extends ExcaliburException {
    
    public CodecException(String message) {
        super(message);
    }
    
    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public CodecException(Throwable cause) {
        super(cause);
    }
}
