package com.eonmux.excalibur.llm.client;

import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns an endpoint's 401 or 403 into a message that says what to change.
 *
 * <p>A node calls a keyless endpoint whenever one is configured, because Ollama, vLLM and TGI ask
 * for no key and the only way to learn whether a server does is to call it. The cost of that
 * choice is paid here: a server that does want a key answers 401, and a bare "error status 401"
 * reads like an outage rather than like the one missing setting it is. So the message says
 * whether a key was sent at all, and which settings supply one.</p>
 *
 * <p>The first such failure is also logged once per client at ERROR, because the exception
 * usually lands in a background pass - ingestion, a graph build - whose own log line is about the
 * document it failed on, not about the credential that failed it.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe: the once-only flag is atomic.</p>
 */
public final class AuthFailure {

    private final AtomicBoolean reported = new AtomicBoolean();

    /**
     * Whether an HTTP status is an authentication or authorisation refusal.
     *
     * @param statusCode The response status
     * @return true for 401 and 403
     */
    public static boolean isAuthStatus(int statusCode) {
        return statusCode == HttpStatus.UNAUTHORIZED || statusCode == HttpStatus.FORBIDDEN;
    }

    /**
     * Describes a refusal in terms of the settings that would fix it.
     *
     * @param api        Which API refused, as it should read in the message
     * @param statusCode 401 or 403
     * @param baseUrl    The endpoint that refused, already safe to log
     * @param keySent    Whether a key was attached to the request
     * @return The message
     */
    public static String describe(String api, int statusCode, String baseUrl, boolean keySent) {
        String refusal = api + " at " + baseUrl + " returned " + statusCode + " ("
            + HttpStatus.getReasonPhrase(statusCode) + ")";
        if (!keySent) {
            return refusal + ": the endpoint requires an API key and none is configured. Set "
                + "excalibur.llm.chat-api-key / excalibur.llm.embedding-api-key, or "
                + "excalibur.llm.api-key (EXCALIBUR_LLM_API_KEY) for both";
        }
        return refusal + ": the configured API key was rejected. Check "
            + "excalibur.llm.chat-api-key / excalibur.llm.embedding-api-key, or "
            + "excalibur.llm.api-key (EXCALIBUR_LLM_API_KEY)";
    }

    /**
     * Logs a refusal at ERROR the first time this client sees one, and at DEBUG after that.
     *
     * @param log     The client's logger
     * @param message The message {@link #describe} produced
     */
    public void report(Logger log, String message) {
        if (reported.compareAndSet(false, true)) {
            log.error(message);
        } else if (log.isDebugEnabled()) {
            log.debug(message);
        }
    }
}
