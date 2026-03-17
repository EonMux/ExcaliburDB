package io.excalibur.core.exception;

/**
 * Exception thrown when serialization/deserialization operations fail.
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
