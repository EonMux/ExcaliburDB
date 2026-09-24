package com.eonmux.excalibur.api.endpoint;

/**
 * Signals that a source could not be registered, or its registration could not be replaced.
 *
 * <p>Carries the reason so the endpoint can map it to a status code without
 * inspecting message text.</p>
 */
public class SourceRegistrationException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * Why a registration, or a replacement of one, was refused.
     */
    public enum Reason {
        /** A source with this ID is already registered. */
        DUPLICATE(409),
        /** The registry is at its configured capacity. */
        CAPACITY(429),
        /** No source is registered under the ID a replacement names. */
        UNKNOWN(404),
        /** A crawl or reindex of the source is running, and would go on with the old configuration. */
        IN_FLIGHT(409),
        /** A replacement names a different namespace from the one the source was registered in. */
        NAMESPACE_MISMATCH(409),
        /** The node is shutting down and starts nothing new against its sources. */
        SHUTTING_DOWN(503);

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
