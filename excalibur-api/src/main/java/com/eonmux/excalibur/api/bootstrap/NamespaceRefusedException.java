package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.api.protocol.ErrorCode;
import com.eonmux.excalibur.tenancy.NamespaceId;

import java.util.Objects;

/**
 * A request named a namespace this node will not bring into being.
 *
 * <p>Thrown wherever a namespace would otherwise be created as a side effect of being named, so
 * every route that can create one refuses it for the same reasons and with the same answer. It is
 * unchecked because the registry's own borrow is, and the routes that reach it already catch the
 * registry's failures; each of them answers it with {@link Reason#statusCode()} and
 * {@link Reason#code()} before its general catch turns it into a 500.</p>
 */
public final class NamespaceRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the namespace was not created. */
    public enum Reason {

        /**
         * {@code excalibur.graph.require-explicit-namespaces} is on and nobody created it.
         *
         * <p>404, because to this node the namespace does not exist - which is the answer a
         * caller with a typo in its header needs to see.</p>
         */
        NOT_CREATED(404, ErrorCode.NAMESPACE_NOT_FOUND),

        /**
         * The node already holds {@code excalibur.graph.max-namespaces} tenant namespaces.
         *
         * <p>429, like the source cap beside it: the request is well-formed and would succeed on a
         * node with room. It clears when a namespace is dropped, not with time.</p>
         */
        LIMIT_REACHED(429, ErrorCode.NAMESPACE_LIMIT_REACHED);

        private final int statusCode;
        private final ErrorCode code;

        Reason(int statusCode, ErrorCode code) {
            this.statusCode = statusCode;
            this.code = code;
        }

        /** @return the HTTP status this refusal is answered with */
        public int statusCode() {
            return statusCode;
        }

        /** @return the machine-readable code this refusal is answered with */
        public ErrorCode code() {
            return code;
        }
    }

    private final transient NamespaceId namespace;
    private final Reason reason;

    /**
     * Creates the refusal.
     *
     * @param namespace The namespace that was not created
     * @param reason    Why
     * @param message   What to tell the caller; it names no path
     */
    public NamespaceRefusedException(NamespaceId namespace, Reason reason, String message) {
        super(message);
        this.namespace = Objects.requireNonNull(namespace, "namespace must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    /** @return the namespace that was not created */
    public NamespaceId namespace() {
        return namespace;
    }

    /** @return why it was not created */
    public Reason reason() {
        return reason;
    }
}
