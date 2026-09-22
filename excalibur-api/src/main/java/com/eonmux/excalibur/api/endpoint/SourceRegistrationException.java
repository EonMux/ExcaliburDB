package com.eonmux.excalibur.api.endpoint;

/**
 * Signals that a source could not be registered.
 *
 * <p>Carries the reason so the endpoint can map it to a status code without
 * inspecting message text.</p>
 */
public class SourceRegistrationException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * Why a registration was refused.
     */
    public enum Reason {
        /** A source with this ID is already registered. */
        DUPLICATE(409),
        /** The registry is at its configured capacity. */
        CAPACITY(429);

        private final int statusCode;

        Reason(int statusCode) {
            this.statusCode = statusCode;
        }

        /**
         * Returns the HTTP status this reason maps to.
         *
         * @return The status code
         */
        public int statusCode() {
            return statusCode;
        }
    }

    private final Reason reason;

    /**
     * Creates a registration failure.
     *
     * @param reason  Why registration was refused
     * @param message Client-safe message
     */
    public SourceRegistrationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * Returns why registration was refused.
     *
     * @return The reason
     */
    public Reason reason() {
        return reason;
    }
}
