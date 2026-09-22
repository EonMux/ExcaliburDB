package com.eonmux.excalibur.core.exception;

/**
 * Base exception class for ExcaliburDB core operations.
 *
 * All exceptions thrown by the core library extend this class to provide
 * consistent error handling and logging.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Once constructed, the exception's
 * state (message and cause) cannot be modified.</p>
 */
public class ExcaliburException extends RuntimeException {
    
    public ExcaliburException(String message) {
        super(message);
    }
    
    public ExcaliburException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public ExcaliburException(Throwable cause) {
        super(cause);
    }
}
