package excalibur.llm.client;

/**
 * Renders an endpoint URL in a form fit to appear in an error message or a log line.
 *
 * <h2>Why</h2>
 * <p>The configured base URL may carry credentials in its userinfo component -
 * {@code https://user:password@provider.example} - which some providers and most authenticating
 * proxies still accept. Nothing in this module rejects that, and rejecting it would break a
 * deployment that legitimately relies on it. But the URL is also the most useful thing to name
 * when a call fails, so it ends up in exception messages that travel to an API caller and in
 * log lines that travel to wherever logs go. Both are places a password must not reach.</p>
 *
 * <p>Only the credentials are removed. The scheme, host, port and path are what make a failure
 * message worth reading, and none of them is a secret.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; safe to call concurrently.</p>
 */
public final class SafeUrl {

    /** Stands in for the credentials that were removed, so their presence is still evident. */
    private static final String REDACTED = "***@";

    private SafeUrl() {
        // Utility class
    }

    /**
     * Returns the URL with any embedded credentials replaced.
     *
     * <p>Parsed by hand rather than with {@link java.net.URI}, because this is called on the
     * failure path and a URL malformed enough to fail parsing is exactly the one whose message
     * must still be produced. Anything unrecognised is returned with the whole authority
     * removed rather than passed through, since a value this cannot parse is a value whose
     * safety it cannot vouch for.</p>
     *
     * @param url The URL as configured, which may be null
     * @return The URL without credentials, or null if the input was null
     */
    public static String redact(String url) {
        if (url == null) {
            return null;
        }

        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            // No authority component, so no userinfo to hide.
            return url;
        }

        int authorityStart = schemeEnd + 3;
        int authorityEnd = endOfAuthority(url, authorityStart);

        int at = url.lastIndexOf('@', authorityEnd - 1);
        if (at < authorityStart) {
            return url;
        }

        return url.substring(0, authorityStart) + REDACTED + url.substring(at + 1);
    }

    /**
     * Returns the index just past the authority component.
     *
     * @param url            The URL being scanned
     * @param authorityStart The first index of the authority
     * @return The index of the first character after the authority, or the URL length
     */
    private static int endOfAuthority(String url, int authorityStart) {
        for (int i = authorityStart; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                return i;
            }
        }
        return url.length();
    }
}
