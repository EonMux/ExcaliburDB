package com.eonmux.excalibur.llm.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Makes a correlation id safe to write into an outbound HTTP header.
 *
 * <h2>Why this exists</h2>
 * <p>A correlation id reaches this module from the API boundary, which means it is whatever the
 * caller sent. It is then copied verbatim into a header on the request the node makes to its
 * model provider. Two things follow from writing unvalidated input into a header: a value
 * carrying a carriage return or newline is an attempt to inject a header of the attacker's
 * choosing into that request, and a value of unbounded length is an unbounded write to a third
 * party repeated on every call.</p>
 *
 * <p>The JDK's request builder happens to reject control characters, so the injection does not
 * reach the wire. It rejects them by throwing {@link IllegalArgumentException} from inside the
 * client, though - an unchecked exception no caller declares, catches or expects, which escapes
 * past the retry loop and past the circuit breaker's accounting. Relying on that is relying on
 * an implementation detail of the HTTP client to produce a bad outcome instead of a worse one.
 * Length it does not bound at all: a hundred thousand characters go out as sent.</p>
 *
 * <h2>Why a bad id is replaced rather than rejected</h2>
 * <p>Nothing authenticates, authorises or routes on this value; it is a label that exists so a
 * human can line up two logs. Refusing the request would convert a cosmetic problem in a trace
 * header into a failed query or a failed ingest, and would hand anyone able to influence that
 * header a one-character denial of service. Substituting a generated id keeps the request
 * working and still leaves the operator something to correlate on, and the substitution is
 * logged so that whatever is emitting malformed ids can be found and fixed.</p>
 *
 * <p>The rejected value is deliberately absent from that log line. It is untrusted text, and
 * writing it into a log is the same injection one step further along.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; every method is safe to call concurrently.</p>
 */
public final class CorrelationId {

    private static final Logger LOG = LoggerFactory.getLogger(CorrelationId.class);

    /**
     * Longest correlation id accepted.
     *
     * <p>Comfortably above a UUID (36) and a W3C traceparent (55), and far below anything that
     * would make the header worth noticing on the wire.</p>
     */
    public static final int MAX_LENGTH = 128;

    /** Marks an id this class generated, so a substitution is recognisable in a log. */
    private static final String GENERATED_PREFIX = "gen-";

    private CorrelationId() {
        // Utility class
    }

    /**
     * Returns a value safe to use as a header, substituting a generated id for a bad one.
     *
     * <p>An id is accepted unchanged when it is at most {@link #MAX_LENGTH} characters and
     * contains only unreserved ASCII: letters, digits and {@code - _ . :}. That covers every
     * identifier format in ordinary use - UUIDs, W3C traceparent, the request ids load
     * balancers add - and excludes whitespace, control characters and the separators that give
     * header syntax its meaning.</p>
     *
     * @param correlationId The caller-supplied id, which may be null, blank or hostile
     * @return A safe id, or null if the caller supplied none and no header should be sent
     */
    public static String sanitize(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            // Absent is absent. Inventing an id for a caller that supplied none would put a
            // meaningless header on every request and correlate nothing with anything.
            return null;
        }

        if (correlationId.length() > MAX_LENGTH) {
            LOG.warn("Replacing a correlation id of {} characters; the limit is {}",
                correlationId.length(), MAX_LENGTH);
            return generate();
        }

        int offending = indexOfDisallowed(correlationId);
        if (offending >= 0) {
            // The value itself is never logged: it is untrusted, and a log is just another
            // place a line break does damage.
            LOG.warn("Replacing a correlation id with a disallowed character at position {}",
                offending);
            return generate();
        }

        return correlationId;
    }

    /**
     * Reports whether an id would be used as supplied.
     *
     * <p>Exposed so a caller that wants to warn its own user about a malformed id can ask,
     * rather than comparing before and after.</p>
     *
     * @param correlationId The id to test, which may be null
     * @return true if the id is non-blank and would pass through unchanged
     */
    public static boolean isValid(String correlationId) {
        return correlationId != null
            && !correlationId.isBlank()
            && correlationId.length() <= MAX_LENGTH
            && indexOfDisallowed(correlationId) < 0;
    }

    /**
     * Returns a fresh correlation id.
     *
     * @return An id that is always valid, prefixed to mark it as generated here
     */
    public static String generate() {
        return GENERATED_PREFIX + UUID.randomUUID();
    }

    /**
     * Returns the position of the first character not allowed in an id, or -1 if there is none.
     *
     * @param value The id to scan
     * @return Index of the first disallowed character, or -1
     */
    private static int indexOfDisallowed(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!isAllowed(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Reports whether a character may appear in a correlation id.
     *
     * @param c The character to test
     * @return true if the character is unreserved ASCII
     */
    private static boolean isAllowed(char c) {
        return (c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9')
            || c == '-' || c == '_' || c == '.' || c == ':';
    }
}
