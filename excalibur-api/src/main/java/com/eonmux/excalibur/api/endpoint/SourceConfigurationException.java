package com.eonmux.excalibur.api.endpoint;

/**
 * Signals that a registration's configuration cannot be built into a source, for a reason the
 * caller can act on.
 *
 * <p>The message names the field and what is wrong with it, and is written to be returned to
 * the caller as it stands: it carries no server path, no host address and nothing the caller did
 * not send. That is what separates it from any other {@link IllegalArgumentException} building a
 * source may raise, whose message is written for the node's log and may describe what the node
 * holds.</p>
 */
public class SourceConfigurationException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message What is wrong, naming the field
     */
    public SourceConfigurationException(String message) {
        super(message);
    }

    /**
     * @param message What is wrong, naming the field
     * @param cause   The failure that showed it
     */
    public SourceConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
