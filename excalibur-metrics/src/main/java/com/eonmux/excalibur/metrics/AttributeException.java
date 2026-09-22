package com.eonmux.excalibur.metrics;

/**
 * JMX attribute exception.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Once constructed, the exception's
 * state (message and cause) cannot be modified.</p>
 */
public class AttributeException extends Exception {

    public AttributeException(String name) {
        super("Unknown attribute: " + name);
    }

    public AttributeException(String name, String message) {
        super(name + ": " + message);
    }

    public AttributeException(String name, String message, Throwable cause) {
        super(name + ": " + message, cause);
    }
}
