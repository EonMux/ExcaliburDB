package excalibur.api.protocol;

import java.nio.charset.StandardCharsets;

/**
 * Reading one parameter out of a URL's query string.
 *
 * <h2>Why the raw query</h2>
 * <p>Splitting has to happen before decoding. A {@code &} or {@code =} that arrived
 * percent-encoded is part of a value, not a delimiter, and a string that has already been
 * decoded cannot tell the two apart any more: a source id containing an encoded {@code &} would
 * be split on its own decoded delimiter and the value silently truncated, and whatever survived
 * would then be decoded a second time. Callers pass
 * {@link java.net.URI#getRawQuery()}, never {@code getQuery()}.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless, and safe to call from any thread.</p>
 */
public final class QueryParams {

    private QueryParams() {
        // Utility class
    }

    /**
     * The value of a query parameter.
     *
     * @param rawQuery The undecoded query string, as {@link java.net.URI#getRawQuery()} gives
     *                 it; may be null when the URL carried none
     * @param name     The parameter to look for
     * @return The decoded value, an empty string for a parameter present without one, or null
     *         when the query does not carry it
     */
    public static String value(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                if (pair.substring(0, equals).equals(name)) {
                    return decode(pair.substring(equals + 1));
                }
            } else if (pair.equals(name)) {
                return "";
            }
        }
        return null;
    }

    /**
     * Decodes one percent-encoded value, leaving it as it stands if it will not decode.
     *
     * <p>A malformed escape is the caller's text, not a reason to fail the request: the value
     * is validated by whoever asked for it, and that validation gives a better message than a
     * decoder's would.</p>
     */
    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
