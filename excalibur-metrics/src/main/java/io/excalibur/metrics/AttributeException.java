package io.excalibur.metrics;

/**
 * JMX attribute exception.
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
